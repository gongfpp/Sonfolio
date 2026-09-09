package com.gongfpp.sonfolio.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object RecordingController {
    const val ACTION_START = "com.gongfpp.sonfolio.action.START_RECORDING"
    const val ACTION_STOP = "com.gongfpp.sonfolio.action.STOP_RECORDING"
    const val ACTION_MARK = "com.gongfpp.sonfolio.action.MARK_RECORDING"

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
