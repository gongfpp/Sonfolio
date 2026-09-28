package com.gongfpp.sonfolio.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object RecordingController {
    const val ACTION_START = "com.gongfpp.sonfolio.action.START_RECORDING"
    const val ACTION_STOP = "com.gongfpp.sonfolio.action.STOP_RECORDING"
    const val ACTION_MARK = "com.gongfpp.sonfolio.action.MARK_RECORDING"

    private val _feedback = MutableSharedFlow<RecordingFeedback>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val feedback = _feedback.asSharedFlow()
    private val markerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val _markerState = MutableStateFlow<MarkerSaveState>(MarkerSaveState.Idle)
    val markerState = _markerState.asStateFlow()
    private val _health = MutableStateFlow(CaptureHealth())
    val health = _health.asStateFlow()
    internal fun publishHealth(value: CaptureHealth) { _health.value = value }
    internal fun updateHealth(change: (CaptureHealth) -> CaptureHealth) { _health.update(change) }

    fun publishFeedback(feedback: RecordingFeedback) {
        _markerState.update { markerResult(it, feedback) }
        _feedback.tryEmit(feedback)
    }

    fun start(context: Context) {
        _markerState.value = MarkerSaveState.Idle
        val intent = Intent(context, RecordingService::class.java)
            .setAction(ACTION_START)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { publishFeedback(RecordingFeedback.Failed("无法开始录音：${it.message ?: "请检查麦克风权限"}")) }
    }

    fun stop(context: Context) {
        runCatching { context.startService(
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
        ) }.onFailure { publishFeedback(RecordingFeedback.Failed("停止录音失败，请重试")) }
    }

    fun mark(context: Context, windowMinutes: Int = 3) {
        if (_markerState.value is MarkerSaveState.Saving) return
        val requestId = java.util.UUID.randomUUID().toString()
        _markerState.value = MarkerSaveState.Saving(requestId, windowMinutes)
        markerScope.launch {
            kotlinx.coroutines.delay(15_000)
            _markerState.update { state ->
                if (state is MarkerSaveState.Saving && state.requestId == requestId)
                    MarkerSaveState.Failed(requestId, "暂未收到保存回执，请到对话详情确认标记") else state
            }
        }
        runCatching { context.startService(
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_MARK)
                .putExtra(RecordingService.EXTRA_MARK_REQUEST_ID, requestId)
                .putExtra(RecordingService.EXTRA_MARK_WINDOW_MINUTES, windowMinutes),
        ) }.onFailure { publishFeedback(RecordingFeedback.Failed("标记未保存，请重试", requestId)) }
    }
}
