package com.gongfpp.sonfolio

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class UsageStoreIntegrationTest {
    @Test fun detailedUsageSeparatesFeaturesModelsAndPreservesLegacyWithoutDoubleCounting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.gongfpp.sonfolio.qa")
        val name = "qa-usage-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val month = YearMonth.now().toString()
            prefs.edit().putLong("$month|llm-calls|model-a", 2).putLong("$month|llm-tokens|model-a", 40)
                .putLong("$month|asr-calls|QWEN", 1).putLong("$month|asr-seconds|QWEN", 30).commit()
            val store = UsageStore(context, name)
            store.recordLlm("model-a", 6, 4, "服务甲", "对话总结")
            store.recordLlm("model-a", 12, 8, "服务甲", "转写纠错")
            store.recordLlm("model-a", 3, 4, "服务乙", "连接测试")
            store.recordAsr("QWEN", 12, "asr|v2", "转文字")
            store.recordAsr("QWEN", 1, "asr|v2", "连接测试")
            val rows = store.details()
            assertEquals(8L, rows.sumOf { it.calls })
            assertEquals(77L, rows.sumOf { it.tokens })
            assertEquals(43L, rows.sumOf { it.seconds })
            assertEquals(2L, rows.single { it.feature == "总结/纠错（旧记录未区分）" }.calls)
            assertEquals(2, rows.count { it.model == "asr|v2" })
            assertEquals(1L, rows.single { it.feature == "转写纠错" }.calls)
            val totals = store.monthlyHistory(1).single()
            assertEquals(rows.sumOf { it.calls }, totals.calls)
            assertEquals(rows.sumOf { it.tokens }, totals.tokens)
            assertEquals(rows.sumOf { it.seconds }, totals.seconds)
            assertEquals(rows, UsageStore(context, name).details())
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun costFollowsOfficialPricesAndLeavesUnpricedCallsVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.gongfpp.sonfolio.qa")
        val name = "qa-usage-cost-${System.nanoTime()}"
        try {
            val store = UsageStore(context, name)
            val beijing = ZoneId.of("Asia/Shanghai")
            val night = ZonedDateTime.of(2026, 11, 25, 3, 0, 0, 0, beijing).toInstant().toEpochMilli()
            val morning = ZonedDateTime.of(2026, 11, 25, 10, 0, 0, 0, beijing).toInstant().toEpochMilli()
            // 同一模型按调用时刻计价：空闲时段 1.5 + 4.5，高峰时段 3.0。
            store.recordLlm("deepseek-v4-flash", 1_000_000, 1_000_000, "DeepSeek", "对话总结", night)
            store.recordLlm("deepseek-v4-flash", 1_000_000, 0, "DeepSeek", "对话总结", morning)
            // 识别按秒计价：3600 秒 × 0.00022。
            store.recordAsr("QWEN", 3600, "qwen3-asr-flash", "转文字")
            // 订阅制与未收录模型只记 token，不猜金额。
            store.recordLlm("glm-5.3-flash", 900, 100, "OpenCode Go", "对话总结")
            val rows = store.details()
            assertEquals(9.792, rows.sumOf { it.cost }, 0.0005)
            assertEquals(1L, rows.sumOf { it.unpricedCalls })
            assertEquals(9.792, store.lifetime().cost, 0.0005)
            assertEquals(store.monthlyHistory(1).single().cost, store.lifetime().cost, 0.0005)
        } finally { context.deleteSharedPreferences(name) }
    }
}
