package com.gongfpp.sonfolio

/** 展示标题：用户手工标题优先，其次才是自动生成标题。 */
val com.gongfpp.sonfolio.data.local.ConversationEntity.displayTitle: String
    get() = titleOverride ?: generatedTitle

data class ConversationPreview(
    val id: String,
    val time: String,
    val title: String,
    val duration: String,
    val summary: String,
    val summaryLevel: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val isMarked: Boolean = false,
    /** 小结来源（在线 AI · 模型名／本地 AI），null 表示只有本地提取式小结。 */
    val summarySource: String? = null,
    /** 这场对话包含的转写句数。 */
    val segmentCount: Int = 0,
    /** 为空表示标题仍是自动生成的；展示标题见 title。 */
    val titleOverride: String? = null,
)

/** 小结来源标签；提取式与空值返回 null，表示未生成 AI 总结。 */
internal fun summarySourceLabel(modelVersion: String?): String? = when {
    modelVersion == null -> null
    modelVersion.startsWith("REMOTE:") -> "在线 AI · ${modelVersion.removePrefix("REMOTE:").substringBefore(" @").trim()}"
    modelVersion.startsWith("LOCAL:") -> "本地 AI · ${modelVersion.removePrefix("LOCAL:").substringBefore(" [").trim()}"
    else -> null
}

data class TranscriptLine(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val text: String,
    val localPath: String,
    val chunkStartedAtMillis: Long,
    val isMarked: Boolean = false,
    /** 用户修正前的原始识别文字；为空表示未修正过。 */
    val originalText: String? = null,
    /** 展示层把语气词碎句并入后，这一行实际覆盖的原始转写 id（含自身）。 */
    val mergedIds: List<String> = listOf(id),
    val sourceStarts: Map<String, Long> = mapOf(id to startedAtMillis),
)

data class SearchHit(
    val conversationId: String,
    val title: String,
    /** 命中片段所在句的时间；打开对话时用它定位。 */
    val snippetTranscriptId: String,
    val snippetStartedAtMillis: Long,
    val snippetText: String,
    /** 正文命中的句子数；标题命中但正文没命中时为 0。 */
    val hitCount: Int,
    val titleHit: Boolean,
    val latestHitMillis: Long,
    val isMarked: Boolean = false,
)

data class SearchResults(
    val hits: List<SearchHit>,
    val hasMore: Boolean = false,
    val requestedLimit: Int = 100,
    val errorMessage: String? = null,
)

data class AudioChunkPreview(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val localPath: String,
    val byteSize: Long,
    val processingState: String,
    val errorMessage: String? = null,
    val transcriptCount: Int = 0,
    val visibleTranscriptCount: Int = 0,
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val speechCount: Int = 0,
    /** 可展示的文件大小：原始 WAV 还在时是 WAV 大小，退役后是压缩音大小。 */
    val displayBytes: Long = byteSize,
    /** true 表示原始 WAV 已按保留策略删除，只剩压缩音。 */
    val audioCompressed: Boolean = false,
)
