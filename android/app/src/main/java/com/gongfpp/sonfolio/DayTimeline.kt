package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.data.local.RecordingGapEntity
import com.gongfpp.sonfolio.data.local.TranscriptAudioRow
import com.gongfpp.sonfolio.processing.ChunkProcessing
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

/**
 * 对话合并阈值：整理时相邻语音间隔不超过它才合并为同一场对话。
 * 首页把「还没整理完的切片」临时显示成进行中的对话时复用同一个值，避免两套分组规则漂移。
 */
internal const val CONVERSATION_MERGE_GAP_MILLIS = 2 * 60 * 1_000L

/** WAV 仍在时按已保存字节数计时（墙钟可能包含缺口）；原始 WAV 已按保留策略删除时，
 * byteSize 归零，退回数据库的结束时间。 */
internal fun AudioChunkPreview.savedEndMillis(): Long {
    val bytesPerSecond = sampleRateHz.toLong() * channelCount * 2L
    if (byteSize > 44L && bytesPerSecond > 0) {
        return startedAtMillis + (byteSize - 44L).coerceAtLeast(0L) * 1_000L / bytesPerSecond
    }
    return endedAtMillis ?: startedAtMillis
}

/**
 * 尚未整理完成的切片按「同一场对话」临时聚合出来的展示单元。只用于首页读取层，
 * 不落库；一旦第 4 步整理完成，真实 Conversation 卡片会取代它。
 */
internal data class PendingUnit(
    val id: String,
    val chunkIds: List<String>,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val progress: ChunkProcessing.StageProgress,
    val label: String,
    val errorMessage: String?,
)

/** 首页时间线的一项：已整理的对话，或进行中的录音单元。 */
internal sealed interface TimelineEntry {
    val startedAtMillis: Long
    val endedAtMillis: Long

    data class Conversation(val preview: ConversationPreview) : TimelineEntry {
        override val startedAtMillis: Long get() = preview.startedAtMillis
        override val endedAtMillis: Long get() = preview.endedAtMillis
    }

    data class Pending(val unit: PendingUnit) : TimelineEntry {
        override val startedAtMillis: Long get() = unit.startedAtMillis
        override val endedAtMillis: Long get() = unit.endedAtMillis
    }
}

internal data class DayTimeline(
    val conversations: List<ConversationPreview>,
    val chunks: List<AudioChunkPreview>,
    val pendingUnits: List<PendingUnit>,
    val gaps: List<RecordingGapEntity>,
    val savedMillis: Long,
    val gapMillis: Long,
) {
    /** 已整理完成的切片数 / 当天切片数，用于「3/20」式的处理进度。 */
    val processedChunks: Int get() = chunks.count { it.processingState == ChunkProcessing.ASR_READY }
    val totalChunks: Int get() = chunks.size

    /** 对话与进行中单元混合后按时间倒序；新的在上面。 */
    fun timelineEntries(): List<TimelineEntry> =
        (conversations.map(TimelineEntry::Conversation) + pendingUnits.map(TimelineEntry::Pending))
            .sortedByDescending { it.startedAtMillis }

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
            val dayChunks = chunks.filter { it.processingState != ChunkProcessing.AUDIO_DELETED && window.overlaps(it.startedAtMillis, it.savedEndMillis()) }.sortedBy { it.startedAtMillis }
            val dayGaps = gaps.filter { window.overlaps(it.startedAtMillis, it.endedAtMillis ?: nowMillis) }.sortedBy { it.startedAtMillis }
            return DayTimeline(
                conversations.filter { window.overlaps(it.startedAtMillis, it.endedAtMillis) }.sortedBy { it.startedAtMillis },
                dayChunks,
                buildPendingUnits(dayChunks, dayGaps),
                dayGaps,
                unionDuration(dayChunks.mapNotNull { window.clip(it.startedAtMillis, it.savedEndMillis()) }),
                unionDuration(dayGaps.mapNotNull { window.clip(it.startedAtMillis, it.endedAtMillis ?: nowMillis) }),
            )
        }

        /** 与 ConversationRepository 同源：相邻切片间隔不超过阈值、且中间没有录音缺口才归为一组。 */
        private fun buildPendingUnits(
            dayChunks: List<AudioChunkPreview>,
            gaps: List<RecordingGapEntity>,
        ): List<PendingUnit> {
            val groups = mutableListOf<MutableList<AudioChunkPreview>>()
            dayChunks.filter { it.processingState != ChunkProcessing.ASR_READY }.forEach { chunk ->
                val current = groups.lastOrNull()
                val groupEnd = current?.last()?.savedEndMillis()
                val gapInside = current != null && groupEnd != null && gaps.any {
                    it.startedAtMillis < chunk.startedAtMillis && (it.endedAtMillis ?: Long.MAX_VALUE) > groupEnd
                }
                if (current == null || groupEnd == null || chunk.startedAtMillis - groupEnd > CONVERSATION_MERGE_GAP_MILLIS || gapInside) {
                    groups += mutableListOf(chunk)
                } else {
                    current += chunk
                }
            }
            return groups.map { group ->
                val representative = group.minWithOrNull(
                    compareBy({ ChunkProcessing.progressOf(it.processingState).completed }, { if (ChunkProcessing.progressOf(it.processingState).failed) 0 else 1 }),
                ) ?: group.first()
                PendingUnit(
                    id = "pending-${group.first().id}",
                    chunkIds = group.map { it.id },
                    startedAtMillis = group.first().startedAtMillis,
                    endedAtMillis = group.maxOf { it.savedEndMillis() },
                    progress = ChunkProcessing.progressOf(representative.processingState),
                    label = ChunkProcessing.labelOf(representative.processingState),
                    errorMessage = group.firstNotNullOfOrNull { it.errorMessage },
                )
            }
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

/** 一句话无法按午夜切开文字，所以在相交的两天保留该句，由回顾注明跨日。
 * 日期归属优先使用该行录音发生时的时区（recordedZoneId），fallback 只用于历史空值。 */
internal fun transcriptGroupsByDate(
    groups: List<List<TranscriptAudioRow>>,
    zone: ZoneId,
): Map<LocalDate, List<List<TranscriptAudioRow>>> = groups.flatMap { group ->
    group.flatMap { row ->
        val rowZone = runCatching { ZoneId.of(row.recordedZoneId) }.getOrNull() ?: zone
        val first = Instant.ofEpochMilli(row.startedAtMillis).atZone(rowZone).toLocalDate()
        val last = Instant.ofEpochMilli(maxOf(row.startedAtMillis, row.endedAtMillis - 1)).atZone(rowZone).toLocalDate()
        generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.map { it to row }.toList()
    }.groupBy({ it.first }, { it.second }).toList()
}.groupBy({ it.first }, { it.second })
