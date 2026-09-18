package com.gongfpp.sonfolio.summary

import android.app.Service
import android.content.*
import android.os.*
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private class SummaryTransportFailure(message: String) : Exception(message)
private inline fun verifyTransport(condition: Boolean, message: () -> String) {
    if (!condition) throw SummaryTransportFailure(message())
}

internal class RemoteSummaryTransport(
    private val store: SummarySettingsStore,
    private val open: (String) -> HttpsURLConnection = { URL(it).openConnection() as HttpsURLConnection },
) {
    suspend fun generate(config: SummaryConfig, system: String, user: String): String = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpsURLConnection?>()
        val future = executor.submit {
            try {
                check(continuation.isActive)
                val secret = try { store.apiKey(config) } catch (_: Exception) {
                    throw SummaryTransportFailure("总结配置已改变或密钥不可用，请检查已保存配置")
                }
                val endpoint = validateSummaryEndpoint(config.endpoint)
                val request = JSONObject().put("model", config.model).put("stream", false).put("max_tokens", 1600)
                    .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                    .put("response_format", JSONObject().put("type", "json_object"))
                when (SummaryProvider.fromEndpoint(endpoint)) {
                    SummaryProvider.DEEPSEEK -> request.put("thinking", JSONObject().put("type", "disabled"))
                    SummaryProvider.QWEN -> request.put("enable_thinking", false)
                    SummaryProvider.CUSTOM -> Unit
                }
                val conn = open(endpoint).also { connection.set(it) }
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000; conn.readTimeout = 90_000
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Authorization", "Bearer $secret")
                verifyTransport(continuation.isActive && store.read().revision == config.revision) { "总结配置已改变，任务已取消" }
                conn.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                verifyTransport(conn.responseCode in 200..299) {
                    when (conn.responseCode) {
                        401, 403 -> "服务密钥无效或没有权限"
                        429 -> "模型服务限流或额度不足，请稍后手动重试"
                        in 300..399 -> "接口发生重定向，请填写最终 HTTPS 地址"
                        else -> "模型服务请求失败（HTTP ${conn.responseCode}），请检查接口和模型名称"
                    }
                }
                val data = conn.inputStream.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buffer); if (n < 0) break
                        verifyTransport(output.size() + n <= 1_048_576) { "模型响应过大" }
                        output.write(buffer, 0, n)
                    }
                    output.toString("UTF-8")
                }
                val choice = JSONObject(data).getJSONArray("choices").getJSONObject(0)
                verifyTransport(choice.optString("finish_reason") != "length") { "模型输出被截断，请重试或更换模型" }
                val text = choice.getJSONObject("message").getString("content")
                if (continuation.isActive) continuation.resume(text)
            } catch (error: Throwable) {
                val message = when (error) {
                    is java.net.SocketTimeoutException -> "模型服务超时，原有小结保留"
                    is java.io.IOException -> "无法连接模型服务，请检查网络或接口地址"
                    is org.json.JSONException -> "模型服务响应格式不兼容"
                    is SummaryTransportFailure -> error.message
                    else -> "模型服务调用失败"
                }
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            } finally { connection.getAndSet(null)?.disconnect() }
        }
        continuation.invokeOnCancellation { connection.getAndSet(null)?.disconnect(); future.cancel(true) }
    }

    companion object { private val executor = Executors.newFixedThreadPool(2) }
}

internal object LocalSummaryNative {
    init { System.loadLibrary("sonfolio_summary") }
    external fun generate(path: String, system: ByteArray, user: ByteArray): ByteArray
}

/** 和录音、ASR 分开的进程，只接收文本和私有模型路径，不接收录音。 */
class LocalSummaryService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger
    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("sonfolio-summary", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        messenger = Messenger(Handler(thread.looper) { request ->
            val reply = Message.obtain(null, 1)
            reply.data = try {
                val model = File(requireNotNull(request.data.getString("model"))).canonicalFile
                val catalogModel = com.gongfpp.sonfolio.models.ModelCatalog.file(filesDir, com.gongfpp.sonfolio.models.ModelCatalog.summary).canonicalFile
                require((model.parentFile == File(filesDir, "summary-models").canonicalFile || model == catalogModel) && model.extension == "gguf" && model.isFile)
                val system = requireNotNull(request.data.getString("system"))
                val user = requireNotNull(request.data.getString("user"))
                require(system.length + user.length <= 24_000)
                val output = LocalSummaryNative.generate(model.path, system.toByteArray(Charsets.UTF_8), user.toByteArray(Charsets.UTF_8))
                Bundle().apply { putString("text", String(output, Charsets.UTF_8)) }
            } catch (_: Throwable) {
                Bundle().apply { putString("error", "本地模型未能完成：请检查模型兼容性、内存或文本长度；原有小结保留") }
            }
            runCatching { request.replyTo?.send(reply) }
            true
        })
    }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { stopSelf(); return false }
    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
        // 解绑代表完成/取消/超时。销毁独立进程，确保 native 不在后台继续占用内存。
        if (android.app.Application.getProcessName().endsWith(":summary")) Process.killProcess(Process.myPid())
    }
}

internal class LocalSummaryTransport(private val context: Context) {
    suspend fun generate(model: File, system: String, user: String): String = withSession(model) { send -> send(system, user) }

    /** A single task holds the connection across all parts. Unbinding releases native memory. */
    suspend fun <T> withSession(model: File, block: suspend (suspend (String, String) -> String) -> T): T = withContext(Dispatchers.Main) {
        awaitingProcessExit?.let { previous ->
            check(withTimeoutOrNull(5_000) { previous.await(); true } == true) { "上一项总结模型尚未退出，请稍后重试" }
            awaitingProcessExit = null
        }
        val connected = CompletableDeferred<Messenger>()
        var result: CompletableDeferred<String>? = null
        val processEnded = CompletableDeferred<Unit>()
        var serviceBinder: IBinder? = null
        val death = IBinder.DeathRecipient { processEnded.complete(Unit) }
        fun fail() {
            val error = IllegalStateException("本地总结进程退出，录音仍可继续，原有小结保留")
            connected.completeExceptionally(error); result?.completeExceptionally(error)
        }
        val reply = Messenger(Handler(Looper.getMainLooper()) {
            val current = result
            if (current != null) {
                it.data.getString("error")?.let { error -> current.completeExceptionally(IllegalStateException(error)) }
                    ?: current.complete(it.data.getString("text").orEmpty())
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                serviceBinder = binder
                try { binder.linkToDeath(death, 0); connected.complete(Messenger(binder)) }
                catch (_: RemoteException) { processEnded.complete(Unit); fail() }
            }
            override fun onServiceDisconnected(name: ComponentName) = fail()
            override fun onBindingDied(name: ComponentName) = fail()
            override fun onNullBinding(name: ComponentName) = fail()
        }
        var bound = false
        try {
            bound = context.bindService(Intent(context, LocalSummaryService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND)
            check(bound) { "无法启动本地总结模型" }
            val remote = withTimeoutOrNull(30_000) { connected.await() } ?: error("本地模型启动超时")
            block { system, user ->
                check(result == null) { "本地模型不能并发生成" }
                val pending = CompletableDeferred<String>()
                result = pending
                try {
                    check(remote.binder.isBinderAlive) { "本地总结进程已退出" }
                    remote.send(Message.obtain(null, 1).apply {
                        replyTo = reply
                        data = Bundle().apply { putString("model", model.path); putString("system", system); putString("user", user) }
                    })
                    withTimeoutOrNull(180_000) { pending.await() } ?: error("本地总结超时，原有小结保留")
                } finally { pending.cancel(); result = null }
            }
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
            if (serviceBinder != null) withContext(NonCancellable) {
                awaitingProcessExit = processEnded
                if (withTimeoutOrNull(5_000) { processEnded.await(); true } == true) {
                    awaitingProcessExit = null
                    runCatching { serviceBinder?.unlinkToDeath(death, 0) }
                }
            }
            connected.cancel(); result?.cancel()
        }
    }
    companion object { private var awaitingProcessExit: CompletableDeferred<Unit>? = null }
}
