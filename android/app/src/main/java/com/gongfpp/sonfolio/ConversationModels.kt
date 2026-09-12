package com.gongfpp.sonfolio

enum class ConversationType {
    Release,
    Lunch,
    Game,
    Unknown,
}

data class ConversationPreview(
    val id: String,
    val type: ConversationType,
    val time: String,
    val title: String,
    val duration: String,
    val summary: String,
    val summaryLevel: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val isMarked: Boolean = false,
)

data class TranscriptLine(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val text: String,
    val localPath: String,
    val chunkStartedAtMillis: Long,
    val isMarked: Boolean = false,
)

data class SearchHit(
    val transcriptId: String,
    val conversationId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val title: String,
    val text: String,
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
)
