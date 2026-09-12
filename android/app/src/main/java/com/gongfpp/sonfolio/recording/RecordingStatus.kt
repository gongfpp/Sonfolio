package com.gongfpp.sonfolio.recording

data class RecordingStatus(
    val isRecording: Boolean = false,
    val startedAtMillis: Long? = null,
    val activeChunkId: String? = null,
    val health: CaptureHealth = CaptureHealth(),
    val interruptionPending: Boolean = false,
)
