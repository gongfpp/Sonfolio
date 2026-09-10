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
)

data class TranscriptLine(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val text: String,
    val localPath: String,
    val chunkStartedAtMillis: Long,
)

data class SearchHit(
    val transcriptId: String,
    val conversationId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val title: String,
    val text: String,
)
