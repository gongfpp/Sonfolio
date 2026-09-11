package com.gongfpp.sonfolio.recording

sealed interface RecordingFeedback {
    data class Marked(val markedAtMillis: Long, val windowMinutes: Int) : RecordingFeedback

    data class Failed(val message: String) : RecordingFeedback
}
