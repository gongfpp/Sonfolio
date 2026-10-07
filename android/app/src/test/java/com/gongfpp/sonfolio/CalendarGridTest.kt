package com.gongfpp.sonfolio

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarGridTest {
    @Test fun `selecting inside window retains later dates`() {
        val today = LocalDate.of(2026, 9, 29)
        (0..13).forEach { assertEquals(today, calendarWindowEnd(today, today.minusDays(it.toLong()), today)) }
    }

    @Test fun `jumping from month calendar reveals selected date without future days`() {
        val today = LocalDate.of(2026, 9, 29)
        val selected = LocalDate.of(2025, 12, 31)
        val end = calendarWindowEnd(today, selected, today)
        assertTrue(selected in end.minusDays(13)..end)
        assertTrue(end <= today)
        assertEquals(today, calendarWindowEnd(end, today, today))
    }
    /** 从任意一天出发的「最近 14 天」都必须铺满整行，任何一行都不能少于 7 格。 */
    @Test fun `two week window always fills whole rows`() {
        val start = LocalDate.of(2026, 1, 1)
        repeat(60) { offset ->
            val end = start.plusDays(offset.toLong())
            val days = (13 downTo 0).map { end.minusDays(it.toLong()) }
            val weeks = weekAlignedWeeks(days)
            assertTrue("weeks should not be empty", weeks.isNotEmpty())
            weeks.forEach { week -> assertEquals("$end 的某一行不足 7 格：$week", 7, week.size) }
            // 连续内容不能丢：去掉空位后应与输入一致。
            assertEquals(days, weeks.flatten().filterNotNull())
        }
    }

    /** 复现用户反馈的场景：窗口起点落在周二（leading=1）时最后一行只剩 1 格，必须补满 7 格。 */
    @Test fun `single cell last row is padded to seven`() {
        val tuesday = generateSequence(LocalDate.of(2026, 1, 1)) { it.plusDays(1) }.first { it.dayOfWeek == DayOfWeek.TUESDAY }
        val days = (0 until 14).map { tuesday.plusDays(it.toLong()) }
        val weeks = weekAlignedWeeks(days)
        assertEquals(3, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(null, weeks.first().first())
        assertEquals(1, weeks.last().count { it != null })
        assertEquals(6, weeks.last().count { it == null })
    }

    @Test fun `empty input yields no rows`() {
        assertEquals(emptyList<List<LocalDate?>>(), weekAlignedWeeks(emptyList()))
    }
}
