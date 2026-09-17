package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.summary.*
import org.junit.Assert.*
import org.junit.Test

class SummaryContractTest {
    @Test fun onlyExplicitHttpsChatEndpointsAreAccepted() {
        assertEquals("https://example.com/v1/chat/completions", validateSummaryEndpoint(" https://example.com/v1/chat/completions "))
        listOf("http://example.com/chat/completions", "https://key@example.com/chat/completions",
            "https://example.com/chat/completions?key=secret", "https://example.com/chat/completions#fragment",
            "https://example.com/v1", "not a url").forEach {
            assertTrue(it, runCatching { validateSummaryEndpoint(it) }.isFailure)
        }
    }

    @Test fun longPartsPreserveEveryCharacterIncludingEmojiAndFinalRows() {
        val rows = listOf(SummaryText("a", 0, 1, "中文🌿".repeat(500), true), SummaryText("b", 2, 3, "最后决定保留原音", false))
        val input = SummaryInput("conversation:a", rows)
        val parts = input.parts(100)
        assertTrue(parts.size > 20)
        assertTrue(parts.all { it.isNotEmpty() && it.length <= 100 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertEquals(input.parts(10_000).single(), parts.joinToString(""))
        assertTrue(parts.last().contains("最后决定保留原音"))
        assertTrue(parts.first().contains("★重点"))
    }

    @Test fun summaryTitleIsNormalizedInsteadOfFailingTheWholeRun() {
        fun json(title: String) = """{"title":"$title","brief":"一段小结","keyPoints":[],"decisions":[],"followUps":[],"questions":[]}"""
        // 小模型常见的过长/带空格标题：截断规范化，而不是让整份小结失败。
        assertEquals("今天讨论了", AiSummary.parse(json("今天讨论了录音与长期保存的方案")).title)
        assertEquals("录音", AiSummary.parse(json("录 音")).title)
        assertEquals("未识别", AiSummary.parse(json("   ")).title)
    }

    @Test fun fingerprintInvalidatesChangedTextMarksTimeAndSource() {
        val row = SummaryText("a", 10, 20, "原文", false)
        val source = SummaryInput("conversation:a", listOf(row))
        assertEquals(source.fingerprint, source.copy().fingerprint)
        listOf(source.copy(key = "day:2026-09-12"), source.copy(rows = listOf(row.copy(text = "修改"))),
            source.copy(rows = listOf(row.copy(marked = true))), source.copy(rows = listOf(row.copy(end = 21))),
            source.copy(rows = listOf(row, row.copy(id = "b")))).forEach { assertNotEquals(source.fingerprint, it.fingerprint) }
    }
}
