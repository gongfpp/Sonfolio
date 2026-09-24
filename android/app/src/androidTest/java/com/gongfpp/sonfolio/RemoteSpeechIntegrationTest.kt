package com.gongfpp.sonfolio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.processing.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fake HTTPS connections: no personal audio, credentials or paid network calls. */
@RunWith(AndroidJUnit4::class)
class RemoteSpeechIntegrationTest {
    @Test fun consentIsSeparateHistoryIsBlockedAndProviderChangeDropsKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-speech-${System.nanoTime()}"
        val store = TranscriptionSettingsStore(context, name)
        val file = File(context.cacheDir, "$name.wav")
        try {
            file.writeBytes(RemoteSpeechTransport.wav(FloatArray(16_000)))
            assertEquals(TranscriptionMode.LOCAL, store.read().mode)
            assertThrows(IllegalArgumentException::class.java) { store.save(TranscriptionMode.REMOTE, SpeechProvider.QWEN, "qwen3-asr-flash", "qa-only", false) }
            store.save(TranscriptionMode.REMOTE, SpeechProvider.QWEN, "qwen3-asr-flash", "qa-only", true)
            val config = store.read()
            var requests = 0
            val connection = FakeConnection(URL(config.provider.endpoint), 200, "{\"choices\":[{\"message\":{\"content\":\"测试转写\"}}]}")
            val transport = RemoteSpeechTransport(store) { requests++; connection }
            val windows = listOf(DetectedSpeechWindow(0, 1_000))
            try { transport.transcribe(file, windows, config, "old", 0, "zh"); fail("历史音频必须阻止") }
            catch (_: IllegalStateException) { }
            assertEquals(0, requests)
            store.authorizeChunk("old", config.revision)
            assertEquals(listOf("测试转写"), transport.transcribe(file, windows, config, "old", 0, "zh"))
            assertEquals(1, requests)
            assertFalse(connection.instanceFollowRedirects)
            val body = JSONObject(connection.sent.toString("UTF-8"))
            assertEquals("zh", body.getJSONObject("asr_options").getString("language"))
            val data = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("input_audio").getString("data")
            assertTrue(data.startsWith("data:audio/wav;base64,"))
            assertFalse(connection.sent.toString("UTF-8").contains(file.name))
            store.save(TranscriptionMode.LOCAL, SpeechProvider.SILICONFLOW, "FunAudioLLM/SenseVoiceSmall", "", false)
            assertFalse(store.read().hasKey)
            assertFalse(store.isAuthorized(config, "old", 0))
        } finally {
            store.clearKey(); context.deleteSharedPreferences(name); file.delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("sonfolio-$name") }
        }
    }

    @Test fun doubaoSubmitsThenPollsAndKeepsAppIdOutOfTheAudioField() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-doubao-${System.nanoTime()}"
        val store = TranscriptionSettingsStore(context, name)
        val file = File(context.cacheDir, "$name.wav")
        try {
            file.writeBytes(RemoteSpeechTransport.wav(FloatArray(16_000)))
            assertThrows(IllegalArgumentException::class.java) {
                store.save(TranscriptionMode.REMOTE, SpeechProvider.DOUBAO, "volc.seedasr.auc", "qa-token", true)
            }
            store.save(TranscriptionMode.REMOTE, SpeechProvider.DOUBAO, "volc.seedasr.auc", "qa-token", true, appId = "1000000001")
            val config = store.read()
            assertTrue(config.hasKey)
            assertEquals("1000000001", config.appId)
            store.authorizeChunk("chunk", config.revision)
            val submit = FakeConnection(URL(config.provider.endpoint), 200, "{}", mapOf("X-Api-Status-Code" to "20000000"))
            val query = FakeConnection(URL(config.provider.queryEndpoint!!), 200, "{\"result\":{\"text\":\"豆包转写\"}}", mapOf("X-Api-Status-Code" to "20000000"))
            val connections = ArrayDeque(listOf(submit, query))
            val transport = RemoteSpeechTransport(store) { connections.removeFirst() }
            assertEquals(listOf("豆包转写"), transport.transcribe(file, listOf(DetectedSpeechWindow(0, 1_000)), config, "chunk", 0, "zh"))
            assertEquals("1000000001", submit.sentHeaders["X-Api-App-Key"])
            assertEquals("volc.seedasr.auc", submit.sentHeaders["X-Api-Resource-Id"])
            assertEquals(submit.sentHeaders["X-Api-Request-Id"], query.sentHeaders["X-Api-Request-Id"])
            val body = JSONObject(submit.sent.toString("UTF-8"))
            assertTrue(body.getJSONObject("audio").getString("data").isNotBlank())
            assertEquals("bigmodel", body.getJSONObject("request").getString("model_name"))
            assertFalse(submit.sent.toString("UTF-8").contains(file.name))
            assertEquals("豆包转写", RemoteSpeechTransport.parseDoubao("{\"result\":{\"text\":\"豆包转写\"}}"))
            try { RemoteSpeechTransport.parseDoubao("{\"header\":{\"message\":\"secret-server-echo\"}}"); fail() }
            catch (error: IllegalStateException) { assertFalse(error.message.orEmpty().contains("secret-server-echo")) }
        } finally {
            store.clearKey(); context.deleteSharedPreferences(name); file.delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("sonfolio-$name") }
        }
    }

    @Test fun connectivityTestSendsSilenceAndReportsSuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-conn-${System.nanoTime()}"
        val store = TranscriptionSettingsStore(context, name)
        try {
            store.save(TranscriptionMode.REMOTE, SpeechProvider.QWEN, "qwen3-asr-flash", "qa-only", true)
            val qwen = store.read()
            store.authorizeChunk("chunk", qwen.revision)
            val qwenConn = FakeConnection(URL(qwen.provider.endpoint), 200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}")
            assertTrue(RemoteSpeechTransport(store) { qwenConn }.test(qwen, "chunk", 0).startsWith("连通成功"))
            assertTrue(qwenConn.sent.size() > 44) // 发送了 1 秒静音 WAV

            store.save(TranscriptionMode.REMOTE, SpeechProvider.DOUBAO, "volc.seedasr.auc", "qa-token", true, appId = "1000000001")
            val doubao = store.read()
            store.authorizeChunk("chunk2", doubao.revision)
            val submit = FakeConnection(URL(doubao.provider.endpoint), 200, "{}", mapOf("X-Api-Status-Code" to "20000000"))
            val query = FakeConnection(URL(doubao.provider.queryEndpoint!!), 200, "{\"result\":{\"text\":\"\"}}", mapOf("X-Api-Status-Code" to "20000003"))
            val conns = ArrayDeque(listOf(submit, query))
            assertTrue(RemoteSpeechTransport(store) { conns.removeFirst() }.test(doubao, "chunk2", 0).startsWith("连通成功"))
        } finally {
            store.clearKey(); context.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("sonfolio-$name") }
        }
    }

    @Test fun doubaoLongAudioMapsUtterancesBackToOriginalTimeline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-doubao-long-${System.nanoTime()}"
        val store = TranscriptionSettingsStore(context, name)
        val file = File(context.cacheDir, "$name.wav")
        try {
            file.writeBytes(RemoteSpeechTransport.wav(FloatArray(16_000 * 3)))
            store.save(TranscriptionMode.REMOTE, SpeechProvider.DOUBAO, "volc.seedasr.auc", "qa-token", true, appId = "1000000001")
            val config = store.read()
            store.authorizeChunk("chunk", config.revision)
            val submit = FakeConnection(URL(config.provider.endpoint), 200, "{}", mapOf("X-Api-Status-Code" to "20000000"))
            // 拼接后：窗口A 0–1000ms、间隔 200ms、窗口B 1200–2200ms（原 2000–3000ms）。
            val queryBody = "{\"result\":{\"text\":\"第一句第二句\",\"utterances\":[" +
                "{\"start_time\":0,\"end_time\":1000,\"text\":\"第一句\"}," +
                "{\"start_time\":1300,\"end_time\":2000,\"text\":\"第二句\"}]}}"
            val query = FakeConnection(URL(config.provider.queryEndpoint!!), 200, queryBody, mapOf("X-Api-Status-Code" to "20000000"))
            val connections = ArrayDeque(listOf(submit, query))
            val transport = RemoteSpeechTransport(store) { connections.removeFirst() }
            val utterances = transport.transcribeLong(
                file,
                listOf(DetectedSpeechWindow(0, 1_000), DetectedSpeechWindow(2_000, 3_000)),
                config, "chunk", 0,
            )
            assertEquals(2, utterances.size)
            assertEquals("第一句", utterances[0].text)
            assertEquals(0L, utterances[0].startMillis)
            assertEquals(1_000L, utterances[0].endMillis)
            // 第二句在拼接后的 1300ms 落在窗口B（原 2000ms 起），映射回 2100–2800ms。
            assertEquals("第二句", utterances[1].text)
            assertEquals(2_100L, utterances[1].startMillis)
            assertEquals(2_800L, utterances[1].endMillis)
        } finally {
            store.clearKey(); context.deleteSharedPreferences(name); file.delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("sonfolio-$name") }
        }
    }

    @Test fun multipartAndResponseErrorsDoNotExposeServerPayloads() {
        val config = TranscriptionConfig(provider = SpeechProvider.SILICONFLOW, model = "FunAudioLLM/SenseVoiceSmall")
        val request = RemoteSpeechTransport.request(config, RemoteSpeechTransport.wav(floatArrayOf(0f, .5f, -.5f)), "zh")
        assertTrue(request.first.startsWith("multipart/form-data; boundary="))
        val body = String(request.second, Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"model\"")); assertTrue(body.contains("filename=\"speech.wav\""))
        assertFalse(body.contains("name=\"language\""))
        assertEquals("你好", RemoteSpeechTransport.parse(SpeechProvider.SILICONFLOW, "{\"text\":\"你好\"}"))
        try { RemoteSpeechTransport.parse(SpeechProvider.QWEN, "{\"error\":\"secret-server-echo\"}"); fail() }
        catch (error: IllegalStateException) { assertFalse(error.message.orEmpty().contains("secret-server-echo")) }
    }

    private class FakeConnection(url: URL, private val status: Int, private val response: String,
        private val headers: Map<String, String> = emptyMap()) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        val sentHeaders = mutableMapOf<String, String>()
        override fun getOutputStream() = sent
        override fun getInputStream() = response.byteInputStream()
        override fun getResponseCode() = status
        override fun setRequestProperty(key: String, value: String) { sentHeaders[key] = value }
        override fun getHeaderField(name: String?): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
