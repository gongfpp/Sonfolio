package com.gongfpp.sonfolio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.processing.*
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 通过生产 `RemoteSpeechTransport` 真实调用豆包识别，验证 APP ID + Access Token、submit/query
 * 与结果解析。凭据与音频都从 instrumentation 参数注入，不写入仓库；缺少参数时按假设跳过，
 * 避免把付费网络请求混进常规套件。
 *
 * 用法（凭据与素材仅存在于本次命令）：
 *   adb -s <serial> shell am instrument -w \
 *     -e doubaoAppId <APP_ID> -e doubaoToken <ACCESS_TOKEN> -e wavPath <设备上 WAV 路径> \
 *     -e class com.gongfpp.sonfolio.DoubaoSpeechLiveTest \
 *     com.gongfpp.sonfolio.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class DoubaoSpeechLiveTest {
    @Test fun transcribesRealSpeechThroughProductionTransport() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val appId = args.getString("doubaoAppId")
        val token = args.getString("doubaoToken")
        val wavPath = args.getString("wavPath")
        if (appId.isNullOrBlank() || token.isNullOrBlank() || wavPath.isNullOrBlank()) {
            println("DOUBAO_LIVE_SKIPPED：未提供 doubaoAppId/doubaoToken/wavPath")
            return@runBlocking
        }
        val file = File(wavPath)
        require(file.isFile && file.length() > 44L) { "WAV 素材不存在或为空：$wavPath" }
        val durationMillis = (file.length() - 44L) / 2L / 16L
        require(durationMillis >= 1_000) { "素材时长至少 1 秒，当前 ${durationMillis}ms" }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-doubao-live"
        val store = TranscriptionSettingsStore(context, name)
        try {
            store.save(TranscriptionMode.REMOTE, SpeechProvider.DOUBAO, SpeechProvider.DOUBAO.models.first(),
                token, true, appId = appId)
            val config = store.read()
            store.authorizeChunk("live", config.revision)
            val transport = RemoteSpeechTransport(store)
            // 与生产一致：把音频切成 ≤30 秒窗口逐个上传。
            val startedAt = System.currentTimeMillis()
            val pieces = mutableListOf<String>()
            var start = 0L
            while (start < durationMillis) {
                val end = minOf(start + 30_000L, durationMillis)
                pieces += transport.transcribeWindow(
                    file, DetectedSpeechWindow(start, end), config, "live", startedAt, "zh",
                )
                start = end
            }
            val text = pieces.joinToString("")
            println("DOUBAO_LIVE_MS=${System.currentTimeMillis() - startedAt}")
            println("DOUBAO_LIVE_WINDOWS=${pieces.size}")
            println("DOUBAO_LIVE_TEXT=$text")
            assertTrue("豆包未返回文字", text.isNotBlank())
        } finally {
            store.clearKey(); context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("sonfolio-$name") }
        }
    }
}
