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
    val note: String? = null,
    /** 为空表示标题仍是自动生成的；展示标题见 title。 */
    val titleOverride: String? = null,
)

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
