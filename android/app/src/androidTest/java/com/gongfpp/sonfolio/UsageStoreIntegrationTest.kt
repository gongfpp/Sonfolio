package com.gongfpp.sonfolio

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.time.YearMonth
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
            store.recordLlm("model-a", 10, "服务甲", "对话总结")
            store.recordLlm("model-a", 20, "服务甲", "转写纠错")
            store.recordLlm("model-a", 7, "服务乙", "连接测试")
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
}
