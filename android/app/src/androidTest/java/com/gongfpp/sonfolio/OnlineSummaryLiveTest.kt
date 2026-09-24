package com.gongfpp.sonfolio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.summary.AiSummary
import com.gongfpp.sonfolio.summary.RemoteSummaryTransport
import com.gongfpp.sonfolio.summary.SummaryInput
import com.gongfpp.sonfolio.summary.SummaryMode
import com.gongfpp.sonfolio.summary.SummaryPrompt
import com.gongfpp.sonfolio.summary.SummarySettingsStore
import com.gongfpp.sonfolio.summary.SummaryText
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 通过生产 `RemoteSummaryTransport` 真实调用在线总结，验证接口地址、密钥与 JSON 输出协议。
 * 地址、模型与密钥都从 instrumentation 参数注入，不写入仓库；缺少参数时按假设跳过。
 *
 *   adb -s <serial> shell am instrument -w \
 *     -e summaryEndpoint <https://.../chat/completions> -e summaryModel <model> -e summaryKey <key> \
 *     -e class com.gongfpp.sonfolio.OnlineSummaryLiveTest \
 *     com.gongfpp.sonfolio.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class OnlineSummaryLiveTest {
    @Test fun generatesSummaryThroughProductionTransport() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("summaryEndpoint")
        val model = args.getString("summaryModel")
        val key = args.getString("summaryKey")
        if (endpoint.isNullOrBlank() || model.isNullOrBlank() || key.isNullOrBlank()) {
            println("ONLINE_SUMMARY_LIVE_SKIPPED：未提供 summaryEndpoint/summaryModel/summaryKey")
            return@runBlocking
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-summary-live"
        val store = SummarySettingsStore(context, name)
        try {
            store.save(SummaryMode.REMOTE, endpoint, model, key, automatic = false, consent = true)
            val config = store.read()
            val input = SummaryInput(
                "conversation:online-live",
                listOf(SummaryText("t0", 0, 1, "我们决定明天上午检查录音按钮，小李负责整理需求，还有一个问题是要不要支持导出。", false)),
            )
            val startedAt = System.currentTimeMillis()
            val text = RemoteSummaryTransport(store).generate(
                config, SummaryPrompt.SYSTEM, SummaryPrompt.user(input, input.parts(500).single(), null, 0, 1),
            )
            println("ONLINE_SUMMARY_LIVE_MS=${System.currentTimeMillis() - startedAt}")
            println("ONLINE_SUMMARY_LIVE_TEXT=$text")
            val parsed = AiSummary.parse(text)
            assertTrue("在线总结应返回可解析的结构化小结", parsed.json().isNotBlank())
        } finally {
            store.clearKey(); context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("sonfolio-$name") }
        }
    }
}
