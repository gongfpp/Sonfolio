package com.gongfpp.sonfolio.processing

import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Uploads only bounded VAD windows, never the whole original recording or its filename. */
internal class RemoteSpeechTransport(
    private val store: TranscriptionSettingsStore,
    private val usage: com.gongfpp.sonfolio.UsageStore? = null,
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) {
    suspend fun transcribe(file: File, windows: List<DetectedSpeechWindow>, config: TranscriptionConfig,
        chunkId: String, startedAt: Long, language: String): List<String> = withContext(Dispatchers.IO) {
        windows.map { transcribeWindow(file, it, config, chunkId, startedAt, language) }
    }

    /** 上传单个 ≤30 秒的人声窗口并返回转写文字；失败只影响该窗口，由调用方决定重试范围。 */
    suspend fun transcribeWindow(file: File, window: DetectedSpeechWindow, config: TranscriptionConfig,
        chunkId: String, startedAt: Long, language: String): String = withContext(Dispatchers.IO) {
        ensureActive()
        require(window.startOffsetMillis >= 0 && window.endOffsetMillis > window.startOffsetMillis &&
            window.endOffsetMillis - window.startOffsetMillis <= 30_000) { "人声片段超过安全上传范围，请重新检测人声" }
        val samples = WavPcmReader.readWindow(file, 16_000, window.startOffsetMillis, window.endOffsetMillis)
        val wav = wav(samples)
        // 豆包录音文件识别走异步 submit/query，两次请求共用同一个 X-Api-Request-Id。
        if (config.provider == SpeechProvider.DOUBAO) {
            val text = transcribeDoubao(config, wav, chunkId, startedAt)
            usage?.recordAsr(config.provider.name, window.durationMillis / 1_000)
            return@withContext text
        }
        val key = store.apiKey(config, chunkId, startedAt)
        val request = request(config, wav, language)
        coroutineScope {
            val connection = open(URL(config.provider.endpoint))
            val guard = launch(Dispatchers.IO) {
                try { store.config.first { it.revision != config.revision } }
                finally { connection.disconnect() }
            }
            try {
                ensureActive()
                check(store.isAuthorized(config, chunkId, startedAt)) { "转文字配置已改变，已停止后续上传" }
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000; connection.readTimeout = 60_000
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $key")
                connection.setRequestProperty("Content-Type", request.first)
                connection.setFixedLengthStreamingMode(request.second.size)
                connection.outputStream.use { it.write(request.second) }
                val status = connection.responseCode
                check(status in 200..299) { when (status) {
                    401, 403 -> "识别密钥无效、地域不匹配或无模型权限，请检查转文字设置"
                    429 -> "识别服务限流或额度不足，稍后在原始录音中重试"
                    else -> "识别服务返回 HTTP $status；录音已保留，可稍后重试"
                } }
                val response = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= 1_048_576) { "识别响应超过限制" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
                check(store.isAuthorized(config, chunkId, startedAt)) { "转文字配置已改变，请重新处理；已发出的请求无法撤回" }
                val text = parse(config.provider, response)
                usage?.recordAsr(config.provider.name, window.durationMillis / 1_000)
                text
            } catch (error: CancellationException) { throw error }
            catch (error: java.io.IOException) { error("识别网络中断或超时，录音已保留；重试可能再次计费") }
            finally { guard.cancel(); connection.disconnect() }
        }
    }

    private class HttpResult(val status: Int, val apiCode: String?, val body: String)

    /**
     * 豆包录音文件识别：先 submit 提交音频，再轮询 query 直到出结果；两次请求共用同一个
     * X-Api-Request-Id。单个窗口失败由调用方按窗口重试，不会重复提交已成功的窗口。
     */
    private suspend fun transcribeDoubao(config: TranscriptionConfig, wav: ByteArray, chunkId: String, startedAt: Long): String {
        val appId = store.appId(config, chunkId, startedAt)
        val token = store.apiKey(config, chunkId, startedAt)
        val headers = mapOf(
            "X-Api-App-Key" to appId,
            "X-Api-Access-Key" to token,
            "X-Api-Resource-Id" to config.model,
            "X-Api-Request-Id" to UUID.randomUUID().toString(),
            "X-Api-Sequence" to "-1",
        )
        val body = JSONObject()
            .put("user", JSONObject().put("uid", appId))
            .put("audio", JSONObject().put("format", "wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP)))
            .put("request", JSONObject().put("model_name", "bigmodel").put("enable_itn", true).put("enable_punc", true))
            .toString().toByteArray(Charsets.UTF_8)
        val submitted = post(config, config.provider.endpoint, "application/json; charset=utf-8", headers, body)
        check(submitted.status in 200..299 && submitted.apiCode == "20000000") { doubaoError(submitted) }
        return pollDoubao(config, config.provider.queryEndpoint!!, headers, chunkId, startedAt)
    }

    /**
     * 连通性自检：只发送 1 秒静音，验证密钥、地域与模型权限，不读取真实录音。返回可直接展示的结果。
     * 在线调用可能产生极少量费用；豆包对静音返回「静音音频」但同样验证了鉴权与资源。
     */
    suspend fun test(config: TranscriptionConfig, chunkId: String, startedAt: Long): String = withContext(Dispatchers.IO) {
        val wav = wav(FloatArray(16_000))
        if (config.provider == SpeechProvider.DOUBAO) {
            transcribeDoubao(config, wav, chunkId, startedAt)
            usage?.recordAsr(config.provider.name, 1)
            "连通成功：豆包识别服务已响应（静音样例，未上传真实录音）"
        } else {
            val key = store.apiKey(config, chunkId, startedAt)
            val request = request(config, wav, "zh")
            val result = post(config, config.provider.endpoint, request.first, mapOf("Authorization" to "Bearer $key"), request.second)
            check(result.status in 200..299) { when (result.status) {
                401, 403 -> "连通失败：识别密钥无效、地域不匹配或无模型权限"
                429 -> "连通失败：识别服务限流或额度不足"
                else -> "连通失败：识别服务返回 HTTP ${result.status}"
            } }
            usage?.recordAsr(config.provider.name, 1)
            "连通成功：${config.provider.label} 识别服务已响应（静音样例，未上传真实录音）"
        }
    }

    private suspend fun pollDoubao(config: TranscriptionConfig, url: String, headers: Map<String, String>,
        chunkId: String, startedAt: Long): String {
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            currentCoroutineContext().ensureActive()
            check(store.isAuthorized(config, chunkId, startedAt)) { "转文字配置已改变，请重新处理；已发出的请求无法撤回" }
            val result = post(config, url, "application/json; charset=utf-8", headers, "{}".toByteArray(Charsets.UTF_8))
            check(result.status in 200..299) { doubaoError(result) }
            when (result.apiCode) {
                "20000000" -> return parseDoubao(result.body)
                "20000003" -> return ""
                "20000001", "20000002" -> {
                    check(System.currentTimeMillis() < deadline) { "识别等待超时，录音已保留；重试可能再次计费" }
                    delay(700)
                }
                else -> error(doubaoError(result))
            }
        }
    }

    /** 单次 HTTPS POST；沿用与单请求路径一致的取消、超时和响应大小限制。 */
    private suspend fun post(config: TranscriptionConfig, url: String, contentType: String,
        headers: Map<String, String>, body: ByteArray): HttpResult =
        coroutineScope {
            val connection = open(URL(url))
            val guard = launch(Dispatchers.IO) {
                try { store.config.first { it.revision != config.revision } }
                finally { connection.disconnect() }
            }
            try {
                ensureActive()
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000; connection.readTimeout = 60_000
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val response = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= 1_048_576) { "识别响应超过限制" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }.orEmpty()
                HttpResult(status, connection.getHeaderField("X-Api-Status-Code"), response)
            } catch (error: CancellationException) { throw error }
            catch (error: java.io.IOException) { error("识别网络中断或超时，录音已保留；重试可能再次计费") }
            finally { guard.cancel(); connection.disconnect() }
        }

    private fun doubaoError(result: HttpResult): String = when {
        result.status == 401 || result.status == 403 || result.apiCode == "45000030" ->
            "识别密钥无效、APP ID 不匹配或账号未开通该识别资源，请检查转文字设置"
        result.status == 429 || result.apiCode == "55000031" -> "识别服务限流或额度不足，稍后在原始录音中重试"
        result.apiCode == "45000002" -> "人声片段为空，已跳过"
        result.apiCode == "45000151" -> "音频格式不被豆包支持，录音已保留"
        else -> "识别服务返回错误（${result.apiCode ?: "HTTP ${result.status}"}）；录音已保留，可稍后重试"
    }

    companion object {
        internal fun wav(samples: FloatArray): ByteArray {
            require(samples.size <= 16_000 * 30)
            return ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + samples.size * 2); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(samples.size * 2)
                samples.forEach { putShort((it * 32768).toInt().coerceIn(-32768, 32767).toShort()) }
            }.array()
        }

        internal fun request(config: TranscriptionConfig, wav: ByteArray, language: String): Pair<String, ByteArray> {
            require(config.model in config.provider.models)
            return if (config.provider == SpeechProvider.QWEN) {
                val options = JSONObject().put("enable_itn", true)
                if (language in listOf("zh", "yue", "en", "ja", "ko")) options.put("language", language)
                val audio = JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
                    .put("data", "data:audio/wav;base64," + Base64.encodeToString(wav, Base64.NO_WRAP)))
                val json = JSONObject().put("model", config.model).put("stream", false).put("asr_options", options)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(audio))))
                "application/json; charset=utf-8" to json.toString().toByteArray(Charsets.UTF_8)
            } else {
                val boundary = "sonfolio-${UUID.randomUUID()}"
                val output = ByteArrayOutputStream()
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n${config.model}\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                output.write(wav); output.write("\r\n--$boundary--\r\n".toByteArray())
                "multipart/form-data; boundary=$boundary" to output.toByteArray()
            }
        }

        internal fun parse(provider: SpeechProvider, response: String): String {
            val text = try {
                val json = JSONObject(response)
                if (provider == SpeechProvider.QWEN) json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                else json.getString("text")
            } catch (_: Exception) { error("识别服务响应格式不兼容，未覆盖原有文字") }
            require(text.length <= 20_000) { "识别文字超过单片段限制" }
            return text.trim()
        }

        internal fun parseDoubao(response: String): String {
            val text = try {
                JSONObject(response).getJSONObject("result").optString("text", "")
            } catch (_: Exception) { error("识别服务响应格式不兼容，未覆盖原有文字") }
            require(text.length <= 20_000) { "识别文字超过单片段限制" }
            return text.trim()
        }
    }
}
