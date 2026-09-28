package com.gongfpp.sonfolio.recording

sealed interface RecordingFeedback {
    data class Marked(val markedAtMillis: Long, val windowMinutes: Int, val requestId: String? = null) : RecordingFeedback

    data class Failed(val message: String, val requestId: String? = null) : RecordingFeedback
}

sealed interface MarkerSaveState {
    data object Idle : MarkerSaveState
    data class Saving(val requestId: String, val minutes: Int) : MarkerSaveState
    data class Saved(val requestId: String?, val markedAtMillis: Long, val minutes: Int) : MarkerSaveState
    data class Failed(val requestId: String?, val message: String) : MarkerSaveState
}

internal fun markerResult(state: MarkerSaveState, feedback: RecordingFeedback): MarkerSaveState {
    val requestId = when (feedback) { is RecordingFeedback.Marked -> feedback.requestId; is RecordingFeedback.Failed -> feedback.requestId }
    // A recording/start/stop failure or another notification action is not this mark's result.
    if (state is MarkerSaveState.Saving && state.requestId != requestId) return state
    return when (feedback) {
        is RecordingFeedback.Marked -> MarkerSaveState.Saved(requestId, feedback.markedAtMillis, feedback.windowMinutes)
        is RecordingFeedback.Failed -> if (requestId != null) MarkerSaveState.Failed(requestId, feedback.message) else state
    }
}
