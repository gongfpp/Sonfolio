package com.gongfpp.sonfolio.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object RecordingController {
    const val ACTION_START = "com.gongfpp.sonfolio.action.START_RECORDING"
    const val ACTION_STOP = "com.gongfpp.sonfolio.action.STOP_RECORDING"
    const val ACTION_MARK = "com.gongfpp.sonfolio.action.MARK_RECORDING"

    private val _feedback = MutableSharedFlow<RecordingFeedback>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val feedback = _feedback.asSharedFlow()

    fun publishFeedback(feedback: RecordingFeedback) {
        _feedback.tryEmit(feedback)
    }

    fun start(context: Context) {
        val intent = Intent(context, RecordingService::class.java)
            .setAction(ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
        )
    }

    fun mark(context: Context) {
        context.startService(
            Intent(context, RecordingService::class.java).setAction(ACTION_MARK),
        )
    }
}
