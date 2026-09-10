package com.gongfpp.sonfolio.recording

sealed interface RecordingFeedback {
    data class Marked(val markedAtMillis: Long) : RecordingFeedback

    data class Failed(val message: String) : RecordingFeedback
}
