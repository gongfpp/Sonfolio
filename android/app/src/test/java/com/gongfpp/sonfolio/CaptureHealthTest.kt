package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.recording.CaptureHealth
import com.gongfpp.sonfolio.recording.pcmLevel
import com.gongfpp.sonfolio.recording.requireRecordingSpace
import com.gongfpp.sonfolio.recording.RECORDING_SPACE_RESERVE
import com.gongfpp.sonfolio.data.local.RecordingGapEntity
import com.gongfpp.sonfolio.summary.*
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class CaptureHealthTest {
    @Test fun storageGuardStopsBeforeDiskIsFull() {
        requireRecordingSpace(RECORDING_SPACE_RESERVE)
        assertTrue(runCatching { requireRecordingSpace(RECORDING_SPACE_RESERVE - 1) }.exceptionOrNull()?.message.orEmpty().contains("不会自动删除"))
    }
    @Test fun healthSeparatesServiceWritingSilencingAndStall() {
        assertTrue(CaptureHealth(serviceActive = true).message(10_000).contains("等待音频"))
        assertTrue(CaptureHealth(true, 10_000, false).message(10_050).contains("持续写入"))
        assertTrue(CaptureHealth(true, 10_000, false).message(13_000).contains("没有更新"))
        assertTrue(CaptureHealth(true, 10_000, true).message(10_050).contains("静音"))
        assertTrue(CaptureHealth(true, 10_000, null).message(10_050).contains("尚未确认"))
        assertEquals(0f, pcmLevel(ByteArray(100), 100), 0f)
        assertTrue(pcmLevel(byteArrayOf(0, 64, 0, -64), 4) > .9f)
        assertEquals(0f, pcmLevel(byteArrayOf(12), 1), 0f)
    }

    @Test fun openGapsContinueAcrossDaysUntilActualResume() {
        val date = LocalDate.of(2026, 9, 13)
        val window = DayWindow.of(date, ZoneOffset.UTC)
        val gap = RecordingGapEntity("g", window.start - 60_000, null, "等待恢复", false)
        val day = DayTimeline.build(date, emptyList(), emptyList(), listOf(gap), ZoneOffset.UTC, window.start + 600_000)
        assertEquals(600_000L, day.gapMillis)
        val resumed = gap.copy(endedAtMillis = window.start + 610_000)
        assertEquals(610_000L, DayTimeline.build(date, emptyList(), emptyList(), listOf(resumed), ZoneOffset.UTC, window.start + 900_000).gapMillis)
    }

    @Test fun gapsInvalidateSummaryAndAreExplainedInPrompt() {
        val input = SummaryInput("conversation:a", listOf(SummaryText("t", 10, 100, "我们讨论了录音", false)))
        val interrupted = input.copy(gaps = listOf(SummaryGap("g", 20, null, "系统静音")))
        assertNotEquals(input.fingerprint, interrupted.fingerprint)
        assertNotEquals(interrupted.fingerprint, interrupted.copy(gaps = listOf(SummaryGap("g", 20, 50, "系统静音"))).fingerprint)
        val prompt = SummaryPrompt.user(interrupted, "已保存的转写", null, 0, 1)
        assertTrue(prompt.contains("不能推断缺失期间"))
        assertTrue(prompt.contains("尚未恢复"))
    }
}
