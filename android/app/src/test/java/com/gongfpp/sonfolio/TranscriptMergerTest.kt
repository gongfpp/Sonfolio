package com.gongfpp.sonfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 结构化转写合并：把语气词碎句并入相邻句，避免一个字一行。 */
class TranscriptMergerTest {
    private fun line(id: String, start: Long, text: String) = TranscriptLine(
        id = id, startedAtMillis = start, endedAtMillis = start + 1_000, text = text,
        localPath = "/tmp/$id.wav", chunkStartedAtMillis = start,
    )

    @Test fun fillerAfterSentenceIsMergedIntoIt() {
        val lines = listOf(line("a", 0, "我们今天讨论录音方案"), line("b", 2_000, "嗯"), line("c", 3_000, "再确认一下"))
        val merged = TranscriptMerger.merge(lines)
        assertEquals(listOf("a", "c"), merged.map { it.id })
        assertEquals("我们今天讨论录音方案嗯", merged[0].text)
        assertEquals(listOf("a", "b"), merged[0].mergedIds)
        assertEquals(3_000L, merged[0].endedAtMillis)
    }

    @Test fun leadingFillerIsMergedIntoNextSentence() {
        val lines = listOf(line("a", 0, "嗯"), line("b", 1_000, "啊？"), line("c", 2_000, "我说的是录音"))
        val merged = TranscriptMerger.merge(lines)
        assertEquals(listOf("a"), merged.map { it.id })
        assertEquals("嗯啊？我说的是录音", merged.single().text)
        assertEquals(listOf("a", "b", "c"), merged.single().mergedIds)
        assertEquals(0L, merged.single().startedAtMillis)
    }

    @Test fun punctuationOnlyAndRepeatedFillersAreNonNutritive() {
        assertTrue(TranscriptMerger.isNonNutritive(""))
        assertTrue(TranscriptMerger.isNonNutritive("……"))
        assertTrue(TranscriptMerger.isNonNutritive("嗯嗯"))
        assertTrue(TranscriptMerger.isNonNutritive("啊啊啊"))
        // 有信息量的短回复不合并。
        assertFalse(TranscriptMerger.isNonNutritive("好"))
        assertFalse(TranscriptMerger.isNonNutritive("对，是的"))
        assertFalse(TranscriptMerger.isNonNutritive("OK"))
    }

    @Test fun meaningfulShortRepliesStayOnTheirOwnLine() {
        val lines = listOf(line("a", 0, "你觉得这样可以吗"), line("b", 2_000, "可以"), line("c", 3_000, "那就这样"))
        assertEquals(3, TranscriptMerger.merge(lines).size)
    }

    @Test fun trailingFillersAreMergedIntoPreviousSentence() {
        val lines = listOf(line("a", 0, "先这样"), line("b", 2_000, "呃"), line("c", 3_000, "嗯嗯"))
        val merged = TranscriptMerger.merge(lines)
        assertEquals(listOf("a"), merged.map { it.id })
        assertEquals("先这样呃嗯嗯", merged.single().text)
        assertEquals(4_000L, merged.single().endedAtMillis)
    }

    @Test fun allFillerConversationIsLeftUnchangedRatherThanEmptied() {
        val lines = listOf(line("a", 0, "嗯"), line("b", 1_000, "啊"))
        val merged = TranscriptMerger.merge(lines)
        assertEquals(2, merged.size)
    }
}
