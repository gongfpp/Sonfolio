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
)
