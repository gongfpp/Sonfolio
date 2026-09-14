package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.data.local.RecordingGapEntity
import com.gongfpp.sonfolio.data.local.TranscriptAudioRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 使用本地日历日而不是固定 24 小时，跨日录音在两天均可找到，但统计只计当天部分。 */
internal data class DayWindow(val start: Long, val end: Long) {
    fun overlaps(started: Long, ended: Long): Boolean =
        if (ended <= started) started >= start && started < end else started < end && ended > start

    fun clip(started: Long, ended: Long): LongRange? {
        val from = maxOf(start, started)
        val endExclusive = minOf(end, ended)
        return if (endExclusive > from) from until endExclusive else null
    }

    companion object {
        fun of(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = DayWindow(
            date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }
}

internal fun AudioChunkPreview.savedEndMillis(): Long {
    val bytesPerSecond = sampleRateHz.toLong() * channelCount * 2L
    val duration = if (bytesPerSecond > 0) (byteSize - 44L).coerceAtLeast(0L) * 1_000L / bytesPerSecond else 0L
    return startedAtMillis + duration
}

internal data class DayTimeline(
    val conversations: List<ConversationPreview>,
    val chunks: List<AudioChunkPreview>,
    val gaps: List<RecordingGapEntity>,
    val savedMillis: Long,
    val gapMillis: Long,
) {
    val failedCount get() = chunks.count { it.processingState.endsWith("FAILED") }
    val pendingCount get() = chunks.count { it.processingState !in setOf("ASR_READY", "AUDIO_DELETED", "RECORDING") && !it.processingState.endsWith("FAILED") }

    companion object {
        fun build(
            date: LocalDate,
            conversations: List<ConversationPreview>,
            chunks: List<AudioChunkPreview>,
            gaps: List<RecordingGapEntity>,
            zone: ZoneId = ZoneId.systemDefault(),
            nowMillis: Long = System.currentTimeMillis(),
        ): DayTimeline {
            val window = DayWindow.of(date, zone)
            val dayChunks = chunks.filter { it.processingState != "AUDIO_DELETED" && window.overlaps(it.startedAtMillis, it.savedEndMillis()) }.sortedBy { it.startedAtMillis }
            val dayGaps = gaps.filter { window.overlaps(it.startedAtMillis, it.endedAtMillis ?: nowMillis) }.sortedBy { it.startedAtMillis }
            return DayTimeline(
                conversations.filter { window.overlaps(it.startedAtMillis, it.endedAtMillis) }.sortedBy { it.startedAtMillis },
                dayChunks, dayGaps,
                unionDuration(dayChunks.mapNotNull { window.clip(it.startedAtMillis, it.savedEndMillis()) }),
                unionDuration(dayGaps.mapNotNull { window.clip(it.startedAtMillis, it.endedAtMillis ?: nowMillis) }),
            )
        }
    }
}

/** 相交的文件或缺口不能重复计时；LongRange 最后一个毫秒是闭区间。 */
private fun unionDuration(ranges: List<LongRange>): Long {
    var total = 0L
    var end = Long.MIN_VALUE
    ranges.sortedBy { it.first }.forEach { range ->
        val from = maxOf(range.first, end)
        val until = range.last + 1L
        if (until > from) total += until - from
        end = maxOf(end, until)
    }
    return total
}

internal fun localDateAt(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/** 一句话无法按午夜切开文字，所以在相交的两天保留该句，由回顾注明跨日。 */
internal fun transcriptGroupsByDate(
    groups: List<List<TranscriptAudioRow>>,
    zone: ZoneId,
): Map<LocalDate, List<List<TranscriptAudioRow>>> = groups.flatMap { group ->
    group.flatMap { row ->
        val first = Instant.ofEpochMilli(row.startedAtMillis).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(maxOf(row.startedAtMillis, row.endedAtMillis - 1)).atZone(zone).toLocalDate()
        generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.map { it to row }.toList()
    }.groupBy({ it.first }, { it.second }).toList()
}.groupBy({ it.first }, { it.second })
