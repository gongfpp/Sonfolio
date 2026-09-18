package com.gongfpp.sonfolio.processing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class InferenceClient(
    private val context: Context,
    private val connectionTimeoutMillis: Long = 30_000L,
    private val processingTimeoutMillis: Long = 8 * 60_000L,
) {
    suspend fun detect(file: File): List<DetectedSpeechWindow> {
        val response = call(InferenceService.DETECT, Bundle().apply { putString("path", file.path) })
        val starts = requireNotNull(response.getLongArray("starts"))
        val ends = requireNotNull(response.getLongArray("ends"))
        require(starts.size == ends.size)
        return starts.indices.map { DetectedSpeechWindow(starts[it], ends[it]) }
    }

    suspend fun transcribe(
        file: File,
        windows: List<DetectedSpeechWindow>,
        language: String,
        engine: LocalAsrEngine = LocalAsrEngine.DEFAULT,
        hotwords: String = "",
    ): List<String> {
        if (windows.isEmpty()) return emptyList()
        val response = call(InferenceService.TRANSCRIBE, Bundle().apply {
            putString("path", file.path)
            putString("language", language)
            putString("engine", engine.name)
            putString("hotwords", hotwords)
            putLongArray("starts", windows.map { it.startOffsetMillis }.toLongArray())
            putLongArray("ends", windows.map { it.endOffsetMillis }.toLongArray())
        })
        return requireNotNull(response.getStringArrayList("texts")).also { require(it.size == windows.size) }
    }

    private suspend fun call(operation: Int, input: Bundle): Bundle = inferenceMutex.withLock { withContext(Dispatchers.Main) {
        // If a prior teardown could not be confirmed, do not bind another service or overlap
        // native work. Keep the death signal until the old process has actually exited.
        awaitingProcessExit?.let { previous ->
            check(withTimeoutOrNull(5_000) { previous.await(); true } == true) {
                "上一项模型进程尚未退出，请稍后重试"
            }
            awaitingProcessExit = null
        }
        val connected = CompletableDeferred<Messenger>()
        val result = CompletableDeferred<Bundle>()
        val processEnded = CompletableDeferred<Unit>()
        var binder: IBinder? = null
        val death = IBinder.DeathRecipient { processEnded.complete(Unit) }
        fun fail(message: String) {
            val error = IllegalStateException(message)
            connected.completeExceptionally(error)
            result.completeExceptionally(error)
        }
        val reply = Messenger(Handler(Looper.getMainLooper()) { response ->
            val error = response.data.getString("error")
            if (error == null) result.complete(response.data) else fail(error)
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service
                try { service.linkToDeath(death, 0); connected.complete(Messenger(service)) }
                catch (_: android.os.RemoteException) { processEnded.complete(Unit); fail("模型进程已退出，请重试") }
            }
            override fun onServiceDisconnected(name: ComponentName) = fail("模型进程已退出，录音不受影响，请重试")
            override fun onBindingDied(name: ComponentName) = fail("模型连接中断，请重试")
            override fun onNullBinding(name: ComponentName) = fail("无法启动本地模型")
        }
        var bound = false
        try {
            bound = context.bindService(Intent(context, InferenceService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND)
            check(bound) { "无法连接本地模型" }
            // 冷启动即退出可能没有断开回调，连接等待必须单独限时。
            val remote = withTimeoutOrNull(connectionTimeoutMillis) { connected.await() }
                ?: error("本地模型启动超时，录音仍保留，请重试")
            remote.send(Message.obtain(null, operation).apply { data = input; replyTo = reply })
            // 自身超时是可重试的处理失败；外部取消仍正常向上传递。
            withTimeoutOrNull(processingTimeoutMillis) { result.await() }
                ?: error("本地模型处理超时，录音仍保留，请重试")
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
            if (binder != null) withContext(NonCancellable) {
                awaitingProcessExit = processEnded
                if (withTimeoutOrNull(5_000) { processEnded.await(); true } == true) {
                    awaitingProcessExit = null
                    runCatching { binder?.unlinkToDeath(death, 0) }
                } else {
                    // Preserve the original timeout/cancellation; the gate above stops the
                    // next request until this process dies, even after the mutex is released.
                    Log.w("InferenceClient", "模型进程尚未确认退出，后续推理暂停连接")
                }
            }
            connected.cancel()
            result.cancel()
        }
    } }
    companion object {
        private val inferenceMutex = Mutex()
        private var awaitingProcessExit: CompletableDeferred<Unit>? = null
    }
}
