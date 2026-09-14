package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.data.local.RecordingGapEntity
import com.gongfpp.sonfolio.data.local.TranscriptAudioRow
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DayTimelineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 9, 12)
    private val midnight = DayWindow.of(date, zone).start

    private fun chunk(id: String, start: Long, duration: Long, state: String = "ASR_READY") = AudioChunkPreview(
        id, start, start + duration, "/test/$id.wav", 44L + duration * 32L, state,
    )

    @Test fun cleanedAudioNoLongerCountsOnHomeButConversationRemains() {
        val audio = chunk("removed", midnight, 1_000, "AUDIO_DELETED").copy(byteSize = 0)
        val conversation = ConversationPreview("c", ConversationType.Unknown, "00:00", "保留文字", "1秒", "摘要", "BRIEF", midnight, midnight + 1_000)
        val day = DayTimeline.build(date, listOf(conversation), listOf(audio), emptyList(), zone)
        assertTrue(day.chunks.isEmpty())
        assertEquals(0L, day.savedMillis)
        assertEquals(listOf(conversation), day.conversations)
    }

    @Test fun crossMidnightAudioAndConversationAppearOnBothDaysWithClippedTotals() {
        val audio = chunk("cross", midnight - 60_000, 180_000)
        val conversation = ConversationPreview("c", ConversationType.Unknown, "23:59", "跨日讨论", "3分钟", "摘要", "BRIEF", audio.startedAtMillis, audio.endedAtMillis!!)
        val gap = RecordingGapEntity("gap", midnight - 10_000, midnight + 10_000, "测试中断", false)
        val before = DayTimeline.build(date.minusDays(1), listOf(conversation), listOf(audio), listOf(gap), zone)
        val after = DayTimeline.build(date, listOf(conversation), listOf(audio), listOf(gap), zone)
        assertEquals(listOf(conversation), before.conversations)
        assertEquals(listOf(conversation), after.conversations)
        assertEquals(listOf(audio), before.chunks)
        assertEquals(listOf(audio), after.chunks)
        assertEquals(60_000L, before.savedMillis)
        assertEquals(120_000L, after.savedMillis)
        assertEquals(10_000L, before.gapMillis)
        assertEquals(10_000L, after.gapMillis)
    }

    @Test fun recordingTimeUsesSavedBytesRatherThanWallClockEnd() {
        val audio = chunk("a", midnight, 5_000).copy(endedAtMillis = midnight + 600_000)
        val day = DayTimeline.build(date, emptyList(), listOf(audio), emptyList(), zone)
        assertEquals(5_000L, day.savedMillis)
        assertEquals(0L, day.gapMillis)
    }

    @Test fun headerOnlyActiveAudioIsVisibleButAddsNoDuration() {
        val audio = chunk("active", midnight, 0, "RECORDING").copy(endedAtMillis = null)
        val day = DayTimeline.build(date, emptyList(), listOf(audio), emptyList(), zone)
        assertEquals(listOf(audio), day.chunks)
        assertEquals(0L, day.savedMillis)
        assertEquals(0, day.pendingCount)
    }

    @Test fun audioEndingExactlyAtMidnightBelongsOnlyToPreviousDay() {
        val audio = chunk("previous", midnight - 60_000, 60_000)
        assertTrue(DayTimeline.build(date, emptyList(), listOf(audio), emptyList(), zone).chunks.isEmpty())
    }

    @Test fun overlappingFilesAndGapsAreNotDoubleCounted() {
        val audio = listOf(chunk("a", midnight, 20_000), chunk("b", midnight + 10_000, 20_000))
        val gaps = listOf(
            RecordingGapEntity("g1", midnight + 60_000, midnight + 80_000, "测试", false),
            RecordingGapEntity("g2", midnight + 70_000, midnight + 90_000, "测试", false),
        )
        val day = DayTimeline.build(date, emptyList(), audio, gaps, zone)
        assertEquals(30_000L, day.savedMillis)
        assertEquals(30_000L, day.gapMillis)
    }

    @Test fun countsOnlyPendingAndFailedChunksFromSelectedDay() {
        val audio = listOf(
            chunk("old", midnight - 60_000, 1_000, "VAD_FAILED"),
            chunk("ready", midnight, 1_000),
            chunk("pending", midnight + 2_000, 1_000, "RECORDED"),
            chunk("failed", midnight + 4_000, 1_000, "ASR_FAILED"),
        )
        val day = DayTimeline.build(date, emptyList(), audio, emptyList(), zone)
        assertEquals(3, day.chunks.size)
        assertEquals(1, day.pendingCount)
        assertEquals(1, day.failedCount)
    }

    @Test fun savedDurationRespectsActualSampleRateAndChannels() {
        val audio = chunk("stereo", midnight, 1_000).copy(sampleRateHz = 32_000, channelCount = 2, byteSize = 128_044)
        assertEquals(midnight + 1_000, audio.savedEndMillis())
    }

    @Test fun dayWindowRespectsDaylightSavingInsteadOfAssuming24Hours() {
        val window = DayWindow.of(LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York"))
        assertEquals(23 * 3_600_000L, window.end - window.start)
    }

    @Test fun journalGroupsKeepOnlyRowsOverlappingTheDateWithoutSplittingConversation() {
        fun row(id: String, start: Long, end: Long) = TranscriptAudioRow(id, "c", start, end, id, "/test.wav", start, false)
        val before = row("before", midnight - 60_000, midnight - 10_000)
        val crossing = row("crossing", midnight - 5_000, midnight + 5_000)
        val after = row("after", midnight + 10_000, midnight + 20_000)
        val grouped = transcriptGroupsByDate(listOf(listOf(before, crossing, after)), zone)
        assertEquals(listOf(listOf(before, crossing)), grouped[date.minusDays(1)])
        assertEquals(listOf(listOf(crossing, after)), grouped[date])
        assertEquals(2, grouped.size)
        val exactMidnight = row("boundary", midnight - 1_000, midnight)
        assertFalse(transcriptGroupsByDate(listOf(listOf(exactMidnight)), zone).containsKey(date))
    }
}
