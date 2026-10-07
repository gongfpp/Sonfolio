package com.gongfpp.sonfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

@Composable
internal fun RealAudioPlayer(
    lines: List<TranscriptLine>,
    chunks: List<AudioChunkPreview>,
    requestedLineId: String?,
    playRequest: Pair<String, Long>?,
    onLocateConsumed: () -> Unit,
    onPlayConsumed: () -> Unit,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as SonfolioApplication
    val gaps by remember(app) { app.database.recordingDao().observeGaps() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val timelineState = remember { mutableStateOf(PlaybackTimeline(emptyList())) }
    LaunchedEffect(lines, chunks, gaps) {
        timelineState.value = withContext(Dispatchers.Default) { PlaybackTimeline.forConversation(lines, chunks, gaps) }
    }
    val timeline = timelineState.value
    val locateTime = requestedLineId?.let { id -> lines.firstOrNull { id in it.mergedIds }?.sourceStarts?.get(id) }
    val playTime = playRequest?.let { (id, _) -> lines.firstOrNull { id in it.mergedIds }?.sourceStarts?.get(id) }
    TimelineAudioPlayer(
        timeline = timeline,
        requestedTime = locateTime,
        playTime = playTime,
        playNonce = playRequest?.second ?: 0L,
        onLocateConsumed = onLocateConsumed,
        onPlayConsumed = onPlayConsumed,
    )
}

@Composable
internal fun TimelineAudioPlayer(
    timeline: PlaybackTimeline,
    requestedTime: Long? = null,
    playTime: Long? = null,
    playNonce: Long = 0L,
    onLocateConsumed: () -> Unit = {},
    onPlayConsumed: () -> Unit = {},
) {
    val audioRoot = java.io.File(LocalContext.current.filesDir, "recordings")
    val controller = remember(timeline.start, timeline.slices.firstOrNull()?.path) { AudioPlaybackController(audioRoot) }
    controller.timeline = timeline
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller) {
        while (true) { controller.tick(); delay(100) }
    }
    LaunchedEffect(requestedTime, timeline.slices) {
        if (requestedTime != null && timeline.slices.isNotEmpty()) {
            controller.seek(requestedTime - timeline.start, autoPlay = false)
            onLocateConsumed()
        }
    }
    LaunchedEffect(playNonce, timeline.slices) {
        if (playNonce > 0 && playTime != null && timeline.slices.isNotEmpty()) {
            controller.seek(playTime - timeline.start, autoPlay = true)
            onPlayConsumed()
        }
    }
    DisposableEffect(controller, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) controller.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.release() }
    }
    val duration = timeline.duration.coerceAtLeast(1L)
    Surface(shape = RoundedCornerShape(14.dp), color = PaleGreen, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
            if (timeline.gaps.isNotEmpty()) Text(
                if (timeline.gaps.any { timeline.start + controller.position >= it.startedAtMillis && timeline.start + controller.position < (it.endedAtMillis ?: Long.MAX_VALUE) })
                    "当前进度位于已知录音缺口，原文件可能只有静音。" else "这段录音含已知缺口，缺失内容无法回听。",
                color = Color(0xFF805900), fontSize = 11.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = controller::toggle,
                    enabled = timeline.slices.isNotEmpty(),
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(ActionFill),
                ) {
                    Icon(
                        if (controller.playing || controller.preparing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (controller.playing || controller.preparing) "暂停" else "播放",
                        tint = Color.White,
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(controller.error ?: if (controller.preparing) "正在定位录音…" else "录音 · 可拖动进度或点击转写行", color = InkSoft, fontSize = 10.sp)
                    Slider(
                        value = (dragPosition ?: controller.position.toFloat()).coerceIn(0f, duration.toFloat()),
                        onValueChange = { dragPosition = it },
                        onValueChangeFinished = {
                            dragPosition?.let { controller.seek(it.toLong()) }
                            dragPosition = null
                        },
                        valueRange = 0f..duration.toFloat(),
                        enabled = timeline.slices.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatPlaybackTime((dragPosition ?: controller.position.toFloat()).toLong()), color = InkSoft, fontSize = 10.sp)
                        Text(formatPlaybackTime(timeline.duration), color = InkSoft, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

internal fun formatPlaybackTime(millis: Long): String {
    val seconds = (millis / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(Locale.US, seconds / 60L, seconds % 60L)
}
