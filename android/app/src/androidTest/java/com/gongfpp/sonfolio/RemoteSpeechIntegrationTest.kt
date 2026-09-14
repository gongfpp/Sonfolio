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

    private class FakeConnection(url: URL, private val status: Int, private val response: String) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getInputStream() = response.byteInputStream()
        override fun getResponseCode() = status
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
