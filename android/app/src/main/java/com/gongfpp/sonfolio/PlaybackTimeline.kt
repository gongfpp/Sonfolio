package com.gongfpp.sonfolio

/** 对话时间相对起点计时，MediaPlayer 始终使用所属文件的偏移。 */
internal data class PlaybackSlice(val path: String, val chunkStart: Long, val start: Long, val end: Long)

internal class PlaybackTimeline(val slices: List<PlaybackSlice>, val gaps: List<com.gongfpp.sonfolio.data.local.RecordingGapEntity> = emptyList()) {
    val start: Long = slices.firstOrNull()?.start ?: 0L
    val end: Long = slices.lastOrNull()?.end ?: start
    val duration: Long = (end - start).coerceAtLeast(0L)

    data class Target(val index: Int, val fileOffset: Long, val position: Long)

    fun locate(position: Long): Target? {
        if (slices.isEmpty()) return null
        val absolute = start + position.coerceIn(0L, duration)
        val index = slices.indexOfFirst { absolute < it.end }.takeIf { it >= 0 } ?: slices.lastIndex
        val slice = slices[index]
        val clamped = absolute.coerceIn(slice.start, slice.end)
        return Target(index, clamped - slice.chunkStart, clamped - start)
    }

    fun position(index: Int, fileOffset: Long): Long =
        (slices[index].chunkStart + fileOffset - start).coerceIn(0L, duration)

    companion object {
        fun forConversation(lines: List<TranscriptLine>, chunks: List<AudioChunkPreview>, gaps: List<com.gongfpp.sonfolio.data.local.RecordingGapEntity> = emptyList()): PlaybackTimeline {
            val start = lines.minOfOrNull { it.startedAtMillis } ?: return PlaybackTimeline(emptyList())
            val end = lines.maxOf { it.endedAtMillis }
            val slices = chunks.filter { it.endedAtMillis != null && it.startedAtMillis < end && it.endedAtMillis!! > start }
                .sortedBy { it.startedAtMillis }.map {
                    PlaybackSlice(it.localPath, it.startedAtMillis, maxOf(start, it.startedAtMillis), minOf(end, it.endedAtMillis!!))
                }
            return PlaybackTimeline(slices, gaps.filter { it.startedAtMillis < end && (it.endedAtMillis ?: Long.MAX_VALUE) > start })
        }
    }
}
