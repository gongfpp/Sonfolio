package com.gongfpp.sonfolio

/**
 * 结构化转写的可读性整理：把「嗯、啊、嗯嗯」这类没有信息量的语气词碎句并到相邻句子，
 * 避免一个字一行把转写拉得很长。只做展示/导出层的合并，不改动数据库里的原始转写。
 */
internal object TranscriptMerger {
    /** 语气词集合；只有整句都由这些字组成（或纯标点）才会被并入相邻句。 */
    private val FILLERS = setOf(
        '嗯', '啊', '呃', '哦', '噢', '唉', '哎', '呀', '嘛', '吧', '呢', '哈',
        '咦', '唔', '呐', '嘿', '喔', '嗷', '呜', '嘻', '欸', '诶',
    )
    private const val MAX_FILLER_CHARS = 4

    /** 判断一句是否没有信息量：纯标点/空白，或全部由语气词组成。 */
    fun isNonNutritive(text: String): Boolean {
        val core = text.filter { it.isLetterOrDigit() || it in '\u4E00'..'\u9FFF' }
        if (core.isEmpty()) return true
        return core.length <= MAX_FILLER_CHARS && core.all { it in FILLERS }
    }

    /**
     * 合并碎句：非营养句并入上一句；若开头就是非营养句，则并入下一句。
     * 保留第一句的 id 与起始时间，结束时间取并集，文本直接拼接。
     */
    fun merge(lines: List<TranscriptLine>): List<TranscriptLine> {
        if (lines.size <= 1) return lines
        val merged = mutableListOf<TranscriptLine>()
        val pending = mutableListOf<TranscriptLine>() // 开头的非营养句，等待并入下一句
        for (line in lines) {
            if (merged.isEmpty()) {
                if (isNonNutritive(line.text)) { pending += line; continue }
            } else if (isNonNutritive(line.text)) {
                val previous = merged.last()
                merged[merged.lastIndex] = previous.copy(
                    endedAtMillis = maxOf(previous.endedAtMillis, line.endedAtMillis),
                    text = previous.text + line.text,
                    mergedIds = previous.mergedIds + line.id,
                )
                continue
            }
            if (pending.isNotEmpty()) {
                val head = pending.first()
                val joined = pending.joinToString("") { it.text } + line.text
                merged += line.copy(
                    id = head.id,
                    startedAtMillis = head.startedAtMillis,
                    text = joined,
                    mergedIds = pending.map { it.id } + line.id,
                )
                pending.clear()
            } else {
                merged += line
            }
        }
        // 结尾遗留的非营养句并入上一句；没有任何有效句时原样返回。
        if (pending.isNotEmpty()) {
            val tail = pending.joinToString("") { it.text }
            val previous = merged.lastOrNull()
            if (previous != null) {
                merged[merged.lastIndex] = previous.copy(
                    endedAtMillis = maxOf(previous.endedAtMillis, pending.last().endedAtMillis),
                    text = previous.text + tail,
                    mergedIds = previous.mergedIds + pending.map { it.id },
                )
            } else {
                return lines
            }
        }
        return merged
    }
}
