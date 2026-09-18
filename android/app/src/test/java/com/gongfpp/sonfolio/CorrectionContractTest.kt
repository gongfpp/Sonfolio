package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.summary.CorrectionPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LLM 纠错结果解析：优先 JSON，小模型给不出 JSON 时退化为逐行；句数必须与输入一致。 */
class CorrectionContractTest {
    @Test fun parsesJsonArrayObjectAndFencedForms() {
        assertEquals(listOf("今天开会。", "好的。"), CorrectionPrompt.parse("""["今天开会。","好的。"]""", 2))
        assertEquals(listOf("A", "B"), CorrectionPrompt.parse("""{"lines":["A","B"]}""", 2))
        assertEquals(listOf("A", "B"), CorrectionPrompt.parse("```json\n[\"A\", \"B\"]\n```", 2))
        assertEquals(listOf("A", "B"), CorrectionPrompt.parse("""{"texts":["A","B"]}""", 2))
    }

    @Test fun fallsBackToPlainLinesWithNumberingPrefix() {
        assertEquals(listOf("今天开会。", "好的。"), CorrectionPrompt.parse("[1] 今天开会。\n[2] 好的。", 2))
        assertEquals(listOf("今天开会。", "好的。"), CorrectionPrompt.parse("1. 今天开会。\n2. 好的。", 2))
        assertEquals(listOf("今天开会。", "好的。"), CorrectionPrompt.parse("- 今天开会。\n- 好的。", 2))
        assertEquals(listOf("今天开会。", "好的。"), CorrectionPrompt.parse("今天开会。\n好的。", 2))
    }

    @Test fun rejectsUnparseableOrWrongCountOutput() {
        listOf("", "only one line").forEach {
            assertTrue(it, runCatching { CorrectionPrompt.parse(it, 2) }.isFailure)
        }
        // JSON 合法但句数不一致也要失败，避免错位写回。
        assertTrue(runCatching { CorrectionPrompt.parse("""["A"]""", 2) }.isFailure)
        assertTrue(runCatching { CorrectionPrompt.parse("""["A", 12]""", 2) }.isFailure)
    }

    @Test fun leakGuardRejectsPromptEchoAndStructuredOutput() {
        // 小模型会把示例/摘要结构原样吐回来，必须丢弃而不是写回转写。
        assertTrue(CorrectionPrompt.looksLikeLeak("""{"title": "今天天气不错", "brief": "我们下午开会。"}"""))
        assertTrue(CorrectionPrompt.looksLikeLeak("""["A","B"]"""))
        assertTrue(CorrectionPrompt.looksLikeLeak("示例输出：[\"A\"]"))
        assertFalse(CorrectionPrompt.looksLikeLeak("今天下午三点开会。"))
        assertFalse(CorrectionPrompt.looksLikeLeak("他说“好的”，然后就走了。"))
    }

    @Test fun userPromptKeepsOrderAndCount() {
        val prompt = CorrectionPrompt.user(listOf("第一句", "第二句"))
        assertTrue(prompt.contains("[1] 第一句"))
        assertTrue(prompt.contains("[2] 第二句"))
        assertTrue(prompt.contains("共 2 个字符串"))
    }
}
