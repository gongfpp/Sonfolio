package com.gongfpp.sonfolio.summary

import com.gongfpp.sonfolio.DayWindow
import com.gongfpp.sonfolio.data.local.MarkerEntity
import com.gongfpp.sonfolio.data.local.TranscriptAudioRow
import com.gongfpp.sonfolio.data.local.RecordingGapEntity
import java.time.LocalDate
import java.time.ZoneId

internal fun summaryInput(key: String, rows: List<TranscriptAudioRow>, markers: List<MarkerEntity>, gaps: List<RecordingGapEntity> = emptyList(), zone: ZoneId = ZoneId.systemDefault()): SummaryInput {
    val filtered = if (key.startsWith("day:")) {
        val day = DayWindow.of(LocalDate.parse(key.removePrefix("day:")), zone)
        rows.filter { it.conversationId != null && day.overlaps(it.startedAtMillis, it.endedAtMillis) }
    } else rows.filter { it.conversationId == key.removePrefix("conversation:") }
    val markedIds = rows.filter { row -> markers.any { m ->
        row.startedAtMillis <= m.markedAtMillis + m.windowAfterMillis && row.endedAtMillis >= m.markedAtMillis - m.windowBeforeMillis
    } }.mapNotNull { it.conversationId }.toSet()
    val window = if (key.startsWith("day:")) DayWindow.of(LocalDate.parse(key.removePrefix("day:")), zone) else
        filtered.takeIf { it.isNotEmpty() }?.let { DayWindow(it.minOf { row -> row.startedAtMillis } - 120_000, it.maxOf { row -> row.endedAtMillis } + 120_000) }
    val relevant = gaps.filter { window != null && window.overlaps(it.startedAtMillis, it.endedAtMillis ?: Long.MAX_VALUE) }
    return SummaryInput(key, filtered.map { SummaryText(it.transcriptId, it.startedAtMillis, it.endedAtMillis, it.text, it.conversationId in markedIds) },
        relevant.map { SummaryGap(it.id, it.startedAtMillis, it.endedAtMillis, it.reason) })
}
