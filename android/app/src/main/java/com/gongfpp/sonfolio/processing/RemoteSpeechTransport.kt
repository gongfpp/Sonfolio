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
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) {
    suspend fun transcribe(file: File, windows: List<DetectedSpeechWindow>, config: TranscriptionConfig,
        chunkId: String, startedAt: Long, language: String): List<String> = withContext(Dispatchers.IO) {
        windows.map { window ->
            ensureActive()
            val key = store.apiKey(config, chunkId, startedAt)
            require(window.startOffsetMillis >= 0 && window.endOffsetMillis > window.startOffsetMillis &&
                window.endOffsetMillis - window.startOffsetMillis <= 30_000) { "人声片段超过安全上传范围，请重新检测人声" }
            val samples = WavPcmReader.readWindow(file, 16_000, window.startOffsetMillis, window.endOffsetMillis)
            val request = request(config, wav(samples), language)
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
                        else -> "识别服务返回 HTTP $status；原音已保留，可稍后重试"
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
                    parse(config.provider, response)
                } catch (error: CancellationException) { throw error }
                catch (error: java.io.IOException) { error("识别网络中断或超时，原音已保留；重试可能再次计费") }
                finally { guard.cancel(); connection.disconnect() }
            }
        }
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
    }
}
