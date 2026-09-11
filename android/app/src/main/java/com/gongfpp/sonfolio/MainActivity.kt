package com.gongfpp.sonfolio

import android.Manifest
import android.media.MediaPlayer
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.gongfpp.sonfolio.recording.RecordingFeedback
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gongfpp.sonfolio.recording.RecordingStatus
import kotlinx.coroutines.delay
import org.json.JSONArray

private val Paper = Color(0xFFFBFAF6)
private val Ink = Color(0xFF17201C)
private val InkSoft = Color(0xFF626B65)
private val Line = Color(0xFFE6E5DE)
private val Green = Color(0xFF1E7046)
private val PaleGreen = Color(0xFFE3F0DE)
private val PaleGreenStrong = Color(0xFFDCEFD9)
private val Amber = Color(0xFFDDA50B)
private val AmberPale = Color(0xFFFFF3CB)

internal sealed interface AppScreen {
    data object Today : AppScreen
    data object Search : AppScreen
    data object Settings : AppScreen
    data object Daily : AppScreen
    data object RawRecordings : AppScreen
    data class Conversation(val type: ConversationType, val id: String? = null, val transcriptId: String? = null) : AppScreen
}

internal fun AppScreen.toSavedRoute(): String = when (this) {
    AppScreen.Today -> "today"
    AppScreen.Search -> "search"
    AppScreen.Settings -> "settings"
    AppScreen.Daily -> "daily"
    AppScreen.RawRecordings -> "raw-recordings"
    is AppScreen.Conversation -> id?.let { "conversation-id:$it${transcriptId?.let { value -> "|$value" }.orEmpty()}" } ?: "conversation:${type.name}"
}

internal fun appScreenFromSavedRoute(route: String): AppScreen = when (route) {
    "today" -> AppScreen.Today
    "search" -> AppScreen.Search
    "settings" -> AppScreen.Settings
    "daily" -> AppScreen.Daily
    "raw-recordings" -> AppScreen.RawRecordings
    else -> {
        if (route.startsWith("conversation-id:")) {
            AppScreen.Conversation(
                type = ConversationType.Unknown,
                id = route.removePrefix("conversation-id:").substringBefore('|').takeIf { it.isNotBlank() },
                transcriptId = route.substringAfter('|', "").takeIf { it.isNotBlank() },
            )
        } else {
            val typeName = route.substringAfter("conversation:", missingDelimiterValue = "")
            val type = ConversationType.entries.firstOrNull { it.name == typeName }
            if (type == null) AppScreen.Today else AppScreen.Conversation(type)
        }
    }
}

private val AppScreenSaver = Saver<AppScreen, String>(
    save = { screen -> screen.toSavedRoute() },
    restore = ::appScreenFromSavedRoute,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: SonfolioViewModel = viewModel()
            SonfolioTheme { SonfolioApp(viewModel) }
        }
    }
}

@Composable
private fun SonfolioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Green,
            onPrimary = Color.White,
            secondaryContainer = PaleGreen,
            onSecondaryContainer = Ink,
            background = Paper,
            surface = Color(0xFFFFFEFA),
            onBackground = Ink,
            onSurface = Ink,
            outline = Line,
        ),
        content = content,
    )
}

@Composable
private fun SonfolioApp(viewModel: SonfolioViewModel) {
    var screen by rememberSaveable(stateSaver = AppScreenSaver) {
        mutableStateOf<AppScreen>(AppScreen.Today)
    }
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val recordingStatus by viewModel.recordingStatus.collectAsStateWithLifecycle()
    val recordingChunks by viewModel.recordingChunks.collectAsStateWithLifecycle()
    val recordingGaps by viewModel.recordingGaps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val application = context.applicationContext as SonfolioApplication
    LaunchedEffect(Unit) {
        viewModel.recordingFeedback.collectLatest { feedback ->
            val message = when (feedback) {
                is RecordingFeedback.Marked -> "已标记前 ${feedback.windowMinutes} 分钟涉及的整段对话"
                is RecordingFeedback.Failed -> feedback.message
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val microphoneGranted = results[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED)
        if (microphoneGranted) {
            viewModel.startRecording()
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                results[Manifest.permission.POST_NOTIFICATIONS] == false
            ) {
                Toast.makeText(context, "通知未开启，通知栏标记按钮不可见", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "需要麦克风权限才能开始记录", Toast.LENGTH_LONG).show()
        }
    }
    val requestRecordingStart = {
        val permissions = buildList {
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isEmpty()) {
            viewModel.startRecording()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
    val isMainScreen = screen is AppScreen.Today || screen is AppScreen.Search || screen is AppScreen.Settings

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            if (isMainScreen) {
                NavigationBar(containerColor = Paper) {
                    NavigationBarItem(
                        selected = screen is AppScreen.Today,
                        onClick = { screen = AppScreen.Today },
                        icon = { Icon(Icons.Default.Home, contentDescription = "声迹") },
                        label = { Text("声迹") },
                    )
                    NavigationBarItem(
                        selected = screen is AppScreen.Search,
                        onClick = { screen = AppScreen.Search },
                        icon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                        label = { Text("搜索") },
                    )
                    NavigationBarItem(
                        selected = screen is AppScreen.Settings,
                        onClick = { screen = AppScreen.Settings },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                        label = { Text("设置") },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = screen) {
                AppScreen.Today -> TodayScreen(
                    conversations = conversations,
                    recordingChunks = recordingChunks,
                    recordingStatus = recordingStatus,
                    gaps = recordingGaps,
                    onStartRecording = requestRecordingStart,
                    onStopRecording = viewModel::stopRecording,
                    onMark = viewModel::markCurrentMoment,
                    onOpen = { screen = it },
                )
                AppScreen.Search -> SearchScreen(viewModel = viewModel, onOpen = { screen = it })
                AppScreen.Settings -> SettingsScreen(
                    preferences = application.preferences,
                    recordingStatus = recordingStatus,
                    chunks = recordingChunks,
                    onOpenRawRecordings = { screen = AppScreen.RawRecordings },
                    onRebuildConversations = viewModel::rebuildConversations,
                )
                AppScreen.Daily -> DailyScreen(viewModel = viewModel, onBack = { screen = AppScreen.Today })
                AppScreen.RawRecordings -> RawRecordingsScreen(
                    chunks = recordingChunks,
                    onRetry = viewModel::retryProcessing,
                    onBack = { screen = AppScreen.Settings },
                )
                is AppScreen.Conversation -> {
                    if (current.id != null) {
                        RealConversationScreen(
                            conversation = conversations.firstOrNull { it.id == current.id },
                            conversationId = current.id,
                            initialTranscriptId = current.transcriptId,
                            viewModel = viewModel,
                            onBack = { screen = AppScreen.Today },
                        )
                    } else if (current.type == ConversationType.Game) {
                        GameSummaryScreen(onBack = { screen = AppScreen.Today })
                    } else {
                        ConversationScreen(current.type, onBack = { screen = AppScreen.Today })
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayScreen(
    conversations: List<ConversationPreview>,
    recordingChunks: List<AudioChunkPreview>,
    recordingStatus: RecordingStatus,
    gaps: List<com.gongfpp.sonfolio.data.local.RecordingGapEntity>,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onMark: (Int) -> Unit,
    onOpen: (AppScreen) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text("声迹", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "${SimpleDateFormat("M月d日", Locale.CHINA).format(Date())} · 共${conversations.size}场对话 · ${if (recordingStatus.isRecording) "正在记录" else "录音已停止"}",
            color = InkSoft,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))
        RecordingCard(
            status = recordingStatus,
            onStart = onStartRecording,
            onStop = onStopRecording,
            onMark = onMark,
        )
        val pendingChunks = recordingChunks.filter { it.processingState != "ASR_READY" }
        if (recordingChunks.isNotEmpty()) {
            ProcessingSummaryCard(recordingChunks) { onOpen(AppScreen.RawRecordings) }
        }
        gaps.firstOrNull()?.let { gap ->
            Surface(Modifier.fillMaxWidth().padding(top = 12.dp), RoundedCornerShape(12.dp), color = AmberPale) {
                Column(Modifier.padding(12.dp)) {
                    Text("录音中断 · ${formatDateTime(gap.startedAtMillis)} — ${formatClock(gap.endedAtMillis)}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("${formatReadableDuration(gap.endedAtMillis - gap.startedAtMillis)}缺失，已写入的原音仍保留。${gap.reason}", color = InkSoft, fontSize = 11.sp)
                }
            }
        }
        SectionTitle("对话时间线")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pendingChunks.forEach { chunk ->
                RawAudioCard(chunk) { onOpen(AppScreen.RawRecordings) }
            }
            conversations.forEach { item ->
                TimelineCard(item) { onOpen(AppScreen.Conversation(type = item.type, id = item.id)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { onOpen(AppScreen.Daily) },
            shape = RoundedCornerShape(15.dp),
            color = PaleGreen,
        ) {
            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Green, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("一日回顾", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("已整理 ${conversations.size} 场对话 · 查看这一天的总结", color = InkSoft, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Green)
            }
        }
    }
}

@Composable
private fun RecordingCard(
    status: RecordingStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMark: (Int) -> Unit,
) {
    var nowMillis by remember(status.startedAtMillis) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(status.isRecording, status.startedAtMillis) {
        while (status.isRecording) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = status.startedAtMillis
        ?.let { startedAt -> formatElapsed(nowMillis - startedAt) }
        ?: "00:00:00"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = Color(0xFFF0F7EE),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB5CDB3)),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { if (status.isRecording) onStop() else onStart() }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Green),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (status.isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (status.isRecording) "停止记录" else "开始记录",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Column(Modifier.padding(start = 10.dp)) {
                    Text(
                        if (status.isRecording) "正在记录" else "开始记录",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                    )
                    Text(
                        if (status.isRecording) elapsed else "点击后持续在后台录音",
                        color = InkSoft,
                        fontSize = 12.sp,
                    )
                }
            }
            if (status.isRecording) {
                AnimatedWaveform(accent = Green, modifier = Modifier.width(42.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier
                        .alpha(if (status.isRecording) 1f else .45f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(AmberPale)
                        .clickable(enabled = status.isRecording) { onMark(3) }
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("★", color = Amber, fontSize = 18.sp)
                    Text("标记刚才", color = Color(0xFF694E00), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(10, 20).forEach { minutes ->
                        Box(
                            Modifier
                                .size(23.dp)
                                .clip(CircleShape)
                                .background(if (status.isRecording) AmberPale else Color(0xFFE8E8E3))
                                .clickable(enabled = status.isRecording) { onMark(minutes) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(minutes.toString(), color = Color(0xFF694E00), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun formatElapsed(durationMillis: Long): String {
    val totalSeconds = maxOf(0, durationMillis / 1_000)
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return listOf(hours, minutes, seconds)
        .joinToString(":") { value -> value.toString().padStart(2, '0') }
}

private fun formatReadableDuration(durationMillis: Long): String {
    val minutes = durationMillis.coerceAtLeast(0L) / 60_000L
    return if (minutes == 0L) "不足1分钟" else "${minutes}分钟"
}

@Composable
private fun ProcessingSummaryCard(chunks: List<AudioChunkPreview>, onOpen: () -> Unit) {
    val recording = chunks.count { it.processingState == "RECORDING" }
    val processing = chunks.count { it.processingState in setOf("VAD_RUNNING", "VAD_READY", "ASR_RUNNING") }
    val waiting = chunks.count { it.processingState == "RECORDED" || it.processingState == "RECOVERED" }
    val failed = chunks.count { it.processingState.endsWith("FAILED") }
    val ready = chunks.count { it.processingState == "ASR_READY" }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFF7DD),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEAD9A4)),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = Amber, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("总结对话", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    when {
                        processing > 0 -> "正在本地识别，已完成${ready}段${if (recording > 0) " · 同时继续录音" else ""}"
                        failed > 0 -> "${failed}段处理失败，点击查看原音并重试"
                        recording > 0 -> "原音持续保存，每5分钟或停止时开始整理"
                        waiting > 0 -> "原语音已保存，等待本地处理"
                        else -> "${ready}段原音已保存并处理 · 未识别或过滤的录音可在这里查看"
                    },
                    color = InkSoft,
                    fontSize = 11.5.sp,
                )
            }
            Text("${chunks.size}段", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RawAudioCard(chunk: AudioChunkPreview, onClick: () -> Unit) {
    val elapsed = chunk.endedAtMillis?.let { formatElapsed(it - chunk.startedAtMillis) } ?: "录音中"
    val processingLabel = when (chunk.processingState) {
        "RECORDING" -> "原始对话 · 正在录音"
        "RECORDED", "RECOVERED" -> "原始对话 · 等待识别"
        "VAD_RUNNING", "VAD_READY" -> "原始对话 · 正在找人声"
        "ASR_RUNNING" -> "原始对话 · 正在转写"
        "VAD_FAILED", "ASR_FAILED", "FAILED" -> "原始对话 · 处理失败，原音仍保留"
        else -> "原始对话"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = Color(0xFFFFFCF0),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8DFC2)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Amber))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    processingLabel,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                    color = Color(0xFF725B18),
                )
                Text(
                    "${formatDateTime(chunk.startedAtMillis)} · $elapsed · ${formatBytes(chunk.byteSize)}",
                    color = InkSoft,
                    fontSize = 11.5.sp,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "查看原始录音", tint = Amber)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "待写入"
    bytes < 1_024L * 1_024L -> "${bytes / 1_024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

@Composable
private fun AnimatedWaveform(accent: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "recording-waveform")
    Row(modifier.height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(12) { index ->
            val scale by transition.animateFloat(
                initialValue = .35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 430 + index * 32,
                        easing = FastOutSlowInEasing,
                        delayMillis = index * 27,
                    ),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 21),
                ),
                label = "recording-wave-$index",
            )
            Box(
                Modifier
                    .width(2.dp)
                    .height(22.dp)
                    .graphicsLayer(scaleY = scale)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, modifier = Modifier.padding(top = 20.dp, bottom = 9.dp), fontWeight = FontWeight.Bold, fontSize = 17.sp)
}

@Composable
private fun TimelineCard(item: ConversationPreview, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Green))
        Spacer(Modifier.width(10.dp))
        Surface(
            modifier = Modifier.weight(1f).clickable(onClick = onClick),
            shape = RoundedCornerShape(13.dp),
            color = Color(0xFFFFFEFA),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.time, color = InkSoft, fontSize = 12.sp)
                    if (item.isMarked) {
                        Text("★", modifier = Modifier.padding(start = 5.dp), color = Amber, fontSize = 13.sp)
                    }
                    Text(item.title, modifier = Modifier.padding(start = 9.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.duration, color = InkSoft, fontSize = 12.sp)
                }
                Text(item.summary, modifier = Modifier.padding(top = 4.dp), color = InkSoft, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DetailTopBar(title: String, meta: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text(meta, modifier = Modifier.padding(start = 46.dp), color = InkSoft, fontSize = 14.sp)
}

@Composable
private fun RealConversationScreen(
    conversation: ConversationPreview?,
    conversationId: String,
    initialTranscriptId: String? = null,
    viewModel: SonfolioViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val lines by remember(conversationId) {
        viewModel.observeTranscript(conversationId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val structuredSummary by remember(conversationId) {
        viewModel.observeConversationSummary(conversationId)
    }.collectAsStateWithLifecycle(initialValue = null)
    var transcriptOpen by rememberSaveable(conversationId) { mutableStateOf(false) }
    var seekLineId by remember(conversationId) { mutableStateOf(initialTranscriptId) }
    val chunks by viewModel.recordingChunks.collectAsStateWithLifecycle()
    val title = conversation?.title ?: "未识别"
    val meta = conversation?.let { formatConversationMeta(it) } ?: "正在整理原始对话"
    val summary = conversation?.summary ?: "正在从本地转写中生成本段小结。"
    val detailFields = listOf(
        "识别片段" to "${lines.size} 段",
        "整理方式" to if (conversation?.summary?.contains("暂时没有") == true) "仅保留时间线" else "本地提取式小结",
        "说话人" to "暂不区分",
    )

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            RealAudioPlayer(
                lines = lines,
                chunks = chunks,
                requestedLineId = seekLineId,
                onRequestConsumed = { seekLineId = null },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            DetailTopBar(title, meta, onBack)
            Spacer(Modifier.height(15.dp))
            val marked = lines.filter { it.isMarked }
            if (marked.isNotEmpty()) {
                Surface(Modifier.fillMaxWidth().padding(bottom = 10.dp), RoundedCornerShape(10.dp), color = AmberPale) {
                    Text(
                        "★ 已高亮 ${formatReadableDuration(marked.maxOf { it.endedAtMillis } - marked.minOf { it.startedAtMillis })} · 标记覆盖整段连续对话",
                        modifier = Modifier.padding(10.dp), color = Color(0xFF694E00), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            SummaryCard(summary, detailFields)
            if (conversation?.summaryLevel == "DETAILED") {
                val detailText = structuredSummary?.let { summary ->
                    listOf("讨论要点" to summary.keyPointsJson, "提到的决定" to summary.decisionsJson,
                        "提到的安排" to summary.followUpsJson, "提出的问题" to summary.openQuestionsJson)
                        .mapNotNull { (label, json) -> parseJsonLines(json).takeIf { it.isNotBlank() }?.let { "$label\n$it" } }
                        .joinToString("\n\n")
                }.orEmpty()
                if (detailText.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    StructuredCard("重点整理", detailText, Icons.AutoMirrored.Filled.List)
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(Modifier.fillMaxWidth().height(1.dp), color = Line) {}
            Row(
                Modifier.fillMaxWidth().clickable { transcriptOpen = !transcriptOpen }.padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Description, contentDescription = null, tint = Green, modifier = Modifier.size(19.dp))
                Text("结构化转写", modifier = Modifier.padding(start = 9.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(if (lines.isEmpty()) "处理中" else "${lines.size}段", color = InkSoft, fontSize = 12.sp)
                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = Ink)
            }
            if (transcriptOpen) {
                if (lines.isEmpty()) {
                    Text("本段还没有可显示的文字，后台处理完成后会自动刷新。", color = InkSoft, fontSize = 12.5.sp)
                } else {
                    lines.forEach { line ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { seekLineId = line.id },
                            shape = RoundedCornerShape(8.dp),
                            color = if (line.isMarked) AmberPale else Color.Transparent,
                        ) {
                            Row(Modifier.padding(vertical = 6.dp, horizontal = 5.dp)) {
                                Text(
                                    if (line.isMarked) "★ ${formatClock(line.startedAtMillis)}" else formatClock(line.startedAtMillis),
                                    modifier = Modifier.width(64.dp),
                                    color = if (line.isMarked) Amber else InkSoft,
                                    fontSize = 12.sp,
                                )
                                Text(line.text, color = Color(0xFF3E4A42), fontSize = 12.5.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("点击任意转写行可跳到对应录音位置", color = InkSoft, fontSize = 11.sp)
        }
    }
}

private fun formatConversationMeta(item: ConversationPreview): String =
    "${formatDateTime(item.startedAtMillis)}–${formatClock(item.endedAtMillis)} · ${item.duration}"

private fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

private fun formatClock(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
private fun RealAudioPlayer(
    lines: List<TranscriptLine>,
    chunks: List<AudioChunkPreview>,
    requestedLineId: String?,
    onRequestConsumed: () -> Unit,
) {
    val timeline = remember(lines, chunks) { PlaybackTimeline.forConversation(lines, chunks) }
    TimelineAudioPlayer(timeline, requestedLineId?.let { id -> lines.firstOrNull { it.id == id }?.startedAtMillis }, onRequestConsumed)
}

@Composable
private fun TimelineAudioPlayer(
    timeline: PlaybackTimeline,
    requestedTime: Long? = null,
    onRequestConsumed: () -> Unit = {},
) {
    val controller = remember(timeline.start, timeline.slices.firstOrNull()?.path) { AudioPlaybackController() }
    controller.timeline = timeline
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller) {
        while (true) { controller.tick(); delay(100) }
    }
    LaunchedEffect(requestedTime, timeline.slices) {
        if (requestedTime != null && timeline.slices.isNotEmpty()) {
            controller.seek(requestedTime - timeline.start, autoPlay = true)
            onRequestConsumed()
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = controller::toggle,
                    enabled = timeline.slices.isNotEmpty(),
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(Green),
                ) {
                    Icon(
                        if (controller.playing || controller.preparing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (controller.playing || controller.preparing) "暂停" else "播放",
                        tint = Color.White,
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(controller.error ?: if (controller.preparing) "正在定位原音…" else "原音 · 可拖动进度或点击转写行", color = InkSoft, fontSize = 10.sp)
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

private fun formatPlaybackTime(millis: Long): String {
    val seconds = (millis / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(Locale.US, seconds / 60L, seconds % 60L)
}

@Composable
private fun ConversationScreen(type: ConversationType, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val info = when (type) {
        ConversationType.Release -> Triple("与同事讨论系统投产", "09:32–09:44 · 12分钟", "确认今晚十点开始投产，先完成数据库备份，再按回滚方案逐项复核。双方确认由我负责上线前检查。")
        ConversationType.Lunch -> Triple("午饭多人聊天", "12:11–12:39 · 28分钟", "午饭时聊了最近的工作节奏和周末安排，整体是轻松的日常交流，没有需要跟进的明确事项。")
        ConversationType.Unknown -> Triple("与未知人物对话", "18:20–18:27 · 7分钟", "围绕晚餐和回家时间进行了简短交流，内容以确认今晚安排为主。")
        ConversationType.Game -> Triple("游戏机制讨论", "14:40–15:18 · 38分钟", "")
    }
    val fields = when (type) {
        ConversationType.Release -> listOf("讨论主题" to "投产安排与回滚准备", "已确认" to "十点开始；先备份数据库", "后续关注" to "上线前再检查一次回滚方案")
        ConversationType.Lunch -> listOf("交流主题" to "工作节奏与周末安排", "主要内容" to "分享最近的工作状态", "后续关注" to "暂无明确后续事项")
        ConversationType.Unknown -> listOf("交流主题" to "晚餐与回家时间", "已确认" to "今晚的回家安排", "后续关注" to "暂无明确后续事项")
        ConversationType.Game -> emptyList()
    }
    var transcriptOpen by rememberSaveable { mutableStateOf(false) }
    var playing by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        DetailTopBar(info.first, info.second, onBack)
        Spacer(Modifier.height(15.dp))
        SummaryCard(info.third, fields)
        Spacer(Modifier.height(16.dp))
        TranscriptSection(transcriptOpen, { transcriptOpen = !transcriptOpen }, type)
        Spacer(Modifier.height(12.dp))
        AudioPlayer(playing = playing, onToggle = { playing = !playing })
    }
}

@Composable
private fun SummaryCard(summary: String, fields: List<Pair<String, String>>) {
    Surface(shape = RoundedCornerShape(15.dp), color = PaleGreen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, contentDescription = null, tint = Green, modifier = Modifier.size(19.dp))
                Text("本段小结", modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text(summary, modifier = Modifier.padding(top = 12.dp), color = Color(0xFF344039), fontSize = 13.sp, lineHeight = 22.sp)
            Column(Modifier.padding(top = 12.dp)) {
                fields.forEach { (label, value) ->
                    Row(Modifier.padding(top = 7.dp)) {
                        Text(label, modifier = Modifier.width(67.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(value, color = Color(0xFF4D5A51), fontSize = 12.sp)
                    }
                }
            }
            Surface(modifier = Modifier.padding(top = 10.dp), shape = CircleShape, color = Color.White.copy(alpha = .55f)) {
                Text("⌁  本地生成", modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Green, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun TranscriptSection(expanded: Boolean, onToggle: () -> Unit, type: ConversationType) {
    Column {
        Surface(Modifier.fillMaxWidth().height(1.dp), color = Line) {}
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, contentDescription = null, tint = Green, modifier = Modifier.size(19.dp))
            Text("原始转写", modifier = Modifier.padding(start = 9.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandMore, contentDescription = null, tint = Ink)
        }
        if (expanded) {
            val rows = when (type) {
                ConversationType.Release -> listOf("09:32" to "我们今晚十点可以开始投产。", "09:35" to "先把数据库备份好。", "★ 09:38" to "然后按回滚方案逐项复核。", "09:41" to "没问题，我来负责上线前的检查。")
                ConversationType.Lunch -> listOf("12:11" to "最近工作节奏还好吗？", "12:18" to "这周比较忙，周末想安排一点轻松的活动。", "★ 12:31" to "那周末再看看天气，找时间一起吃饭。")
                ConversationType.Unknown -> listOf("18:20" to "晚饭已经准备好了吗？", "18:23" to "还没有，回去路上再决定吃什么。", "★ 18:26" to "好，那到家再联系。")
                ConversationType.Game -> emptyList()
            }
            rows.forEach { (time, text) ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(time, modifier = Modifier.width(55.dp), color = if (time.startsWith("★")) Amber else InkSoft, fontSize = 12.sp)
                    Text(text, color = Color(0xFF3E4A42), fontSize = 12.5.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun AudioPlayer(playing: Boolean, onToggle: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = PaleGreen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle, modifier = Modifier.size(36.dp).clip(CircleShape).background(Green)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "暂停" else "播放", tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Waveform(accent = Color(0xFF53966C), modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (playing) "00:07" else "00:00", color = InkSoft, fontSize = 10.sp)
                    Text("12:00", color = InkSoft, fontSize = 10.sp)
                }
            }
            Text("1.0x", color = InkSoft, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Waveform(accent: Color, modifier: Modifier = Modifier) {
    Row(modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(26) { index ->
            val barHeight = (5 + ((index * 7) % 17)).dp
            Box(Modifier.width(2.dp).height(barHeight).clip(CircleShape).background(accent))
        }
    }
}

@Composable
private fun GameSummaryScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var transcriptOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        DetailTopBar("游戏机制讨论", "14:40–15:18 · 38分钟", onBack)
        Row(Modifier.padding(top = 15.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("详细总结", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
            Surface(shape = CircleShape, color = AmberPale) { Text("信息量较大", color = Color(0xFF7C5D11), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            StructuredCard("讨论主题", "电梯断电时如何让玩家先感知危险", Icons.AutoMirrored.Filled.List)
            StructuredCard("关键观点", "• 先用继电器断开的声音建立预警\n• 黑暗中保留短暂的方向提示", Icons.Default.Lightbulb)
            StructuredCard("共识与决定", "声音提示先于画面提示，作为第一版实验方案", Icons.Default.CheckCircle, PaleGreenStrong)
            StructuredCard("未决问题", "不同电梯材质是否需要不同音色", Icons.AutoMirrored.Filled.HelpOutline, Color(0xFFEDF3E6))
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable { transcriptOpen = true },
            shape = RoundedCornerShape(13.dp),
            color = PaleGreen,
        ) {
            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (transcriptOpen) "已展开原始转写" else "展开全部转写", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Green)
            }
        }
        if (transcriptOpen) {
            Spacer(Modifier.height(10.dp))
            TranscriptSection(expanded = true, onToggle = { transcriptOpen = !transcriptOpen }, type = ConversationType.Game)
        }
        Text("已从 38 分钟语音中提炼", modifier = Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = InkSoft, fontSize = 11.sp)
    }
}

@Composable
private fun StructuredCard(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color = PaleGreen) {
    Surface(shape = RoundedCornerShape(14.dp), color = color, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
                Text(title, modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(body, modifier = Modifier.padding(top = 9.dp), color = Color(0xFF3D4B41), fontSize = 12.5.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun DailyScreen(viewModel: SonfolioViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var localDate by rememberSaveable { mutableStateOf(java.time.LocalDate.now().toString()) }
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val dates = (listOf(java.time.LocalDate.now().toString()) + conversations.map {
        Instant.ofEpochMilli(it.startedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }).distinct().sortedDescending()
    val journal by remember(localDate) {
        viewModel.observeDailyJournal(localDate)
    }.collectAsStateWithLifecycle(initialValue = null)
    val narrative = journal?.narrative
    val sourceCount = journal?.sourceConversationCount ?: 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        DetailTopBar("一日回顾", localDate, onBack)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dates.forEach { date -> FilterChip(selected = localDate == date, onClick = { localDate = date }, label = { Text(date) }) }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 15.dp), RoundedCornerShape(15.dp), color = PaleGreen) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = Green, modifier = Modifier.size(19.dp))
                    Text("这一天发生了什么", modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text(
                    narrative ?: "这一天暂时还没有足够的已整理内容。完成录音和本地转写后，这里会生成一日回顾。",
                    modifier = Modifier.padding(top = 13.dp),
                    color = Color(0xFF39483E),
                    fontSize = 13.sp,
                    lineHeight = 23.sp,
                )
            }
        }
        AuxiliaryCard("值得记住", parseJsonLines(journal?.memorableJson).ifBlank { "这一天还没有标记重点对话" }, Icons.Default.Star, Amber)
        AuxiliaryCard("可能需要处理", parseJsonLines(journal?.possibleActionsJson).ifBlank { "暂未从转写中提取明确安排" }, Icons.Default.Warning, Color(0xFFC59016))
        Text("基于 $sourceCount 场对话整理 · 原始录音仍按你的保留策略保存", modifier = Modifier.padding(top = 20.dp), color = InkSoft, fontSize = 11.sp)
        Text("当前为本地提取式回顾，内容来自转写原句。", color = InkSoft, fontSize = 11.sp)
        TextButton(onClick = viewModel::rebuildConversations) { Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("重新整理") }
    }
}

private fun parseJsonLines(value: String?): String = runCatching {
    val array = JSONArray(value ?: "[]")
    (0 until array.length()).map { array.getString(it) }.joinToString("\n") { "• $it" }
}.getOrDefault("")

@Composable
private fun AuxiliaryCard(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Surface(Modifier.fillMaxWidth().padding(top = 10.dp), RoundedCornerShape(14.dp), color = Color(0xFFEAF3E7)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                Text(title, modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(body, modifier = Modifier.padding(start = 28.dp, top = 8.dp), color = Color(0xFF4A574E), fontSize = 12.5.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(viewModel: SonfolioViewModel, onOpen: (AppScreen) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    val displayedHits by remember(query, filter) {
        viewModel.observeSearch(query, filter)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text("搜索记忆", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 17.dp),
            placeholder = { Text("搜索转写内容或对话主题") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "清空搜索") } },
            shape = RoundedCornerShape(11.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "今天", "本周", "仅标记").forEach { value ->
                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value, fontSize = 12.sp) })
            }
        }
        Text(
            if (query.isBlank() && filter == "全部") "输入文字后搜索本地转写"
            else "找到 ${displayedHits.size} 条相关内容",
            color = InkSoft,
            fontSize = 13.sp,
        )
        if (displayedHits.isEmpty()) {
            Text(
                if (query.isBlank() && filter == "全部") "搜索不会自动列出全部记录；你可以输入主题、关键词或人名。"
                else if (query.isBlank()) "此筛选条件下没有已整理的内容。" else "没有找到包含“$query”的转写。",
                modifier = Modifier.padding(top = 20.dp),
                color = InkSoft,
                fontSize = 13.sp,
            )
        } else {
            displayedHits.forEach { hit ->
                SearchResult(
                    date = formatDateTime(hit.startedAtMillis).substringBefore(' '),
                    title = if (hit.isMarked) "★ ${hit.title}" else hit.title,
                    excerpt = hit.text,
                    trailing = formatClock(hit.startedAtMillis),
                ) {
                    onOpen(AppScreen.Conversation(ConversationType.Unknown, hit.conversationId, hit.transcriptId))
                }
            }
        }
    }
}

@Composable
private fun SearchResult(date: String, title: String, excerpt: String, trailing: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onClick), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, color = InkSoft, fontSize = 13.sp)
                Text(title, modifier = Modifier.padding(start = 8.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(trailing, color = InkSoft, fontSize = 11.sp)
            }
            Text(excerpt, modifier = Modifier.padding(top = 9.dp), color = Color(0xFF3E4A42), fontSize = 12.5.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun SettingsScreen(
    preferences: SonfolioPreferences,
    recordingStatus: RecordingStatus,
    chunks: List<AudioChunkPreview>,
    onOpenRawRecordings: () -> Unit,
    onRebuildConversations: () -> Unit,
) {
    var chargeOnly by remember { mutableStateOf(preferences.chargeOnly) }
    var retentionDays by remember { mutableStateOf(preferences.retentionDays) }
    val context = LocalContext.current
    val available = remember(chunks) { android.os.StatFs(context.filesDir.path).availableBytes }
    val used = chunks.sumOf { it.byteSize }
    val bytesPerDay = 16_000L * 2L * 86_400L
    var language by remember { mutableStateOf(preferences.preferredLanguage) }
    var minimumSpeechSeconds by remember { mutableStateOf(preferences.minimumSpeechSeconds.toFloat()) }
    var minimumTextCharacters by remember { mutableStateOf(preferences.minimumTextCharacters.toFloat()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text("录音与存储", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Surface(Modifier.fillMaxWidth().padding(top = 17.dp), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("录音服务", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (recordingStatus.isRecording) "正在记录" else "已停止", color = Green, fontSize = 13.sp)
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(13.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StorageValue("录音已使用", String.format(Locale.US, "%.2f", used / 1_073_741_824.0), "GB", Modifier.weight(1f))
                    StorageValue("预计还可记录", (available / bytesPerDay).toString(), "天", Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(10.dp).clip(CircleShape).background(Color(0xFFE3E3DF))) {
                    Box(Modifier.fillMaxWidth((used.toFloat() / (used + available).coerceAtLeast(1)).coerceIn(0f, 1f)).fillMaxSize().clip(CircleShape).background(Green))
                }
                Text("连续录音约${formatBytes(bytesPerDay)}/天，剩余${formatBytes(available)}；模型和系统也占用存储。", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(13.dp)) {
                Text("原音保留提醒", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 7, 30).forEach { days ->
                        FilterChip(selected = retentionDays == days, onClick = { retentionDays = days; preferences.setRetentionDays(days) },
                            label = { Text(if (days == 0) "永久保留" else "${days}天", fontSize = 11.sp) })
                    }
                }
                val expired = chunks.count { retentionDays > 0 && it.endedAtMillis != null && it.endedAtMillis < System.currentTimeMillis() - retentionDays * 86_400_000L }
                Text(if (expired > 0) "${expired}段原音已到提醒期限，可进入原始录音查看或导出。" else "到期只提醒，由你决定如何处理原音。人声和标记目前引用同一份完整录音。", color = InkSoft, fontSize = 11.sp)
            }
        }
        Surface(
            Modifier.fillMaxWidth().padding(top = 13.dp).clickable(onClick = onOpenRawRecordings),
            RoundedCornerShape(14.dp),
            color = Color(0xFFFFFEFA),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        ) {
            Row(Modifier.padding(horizontal = 13.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("原始录音", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("查看本地文件并导出到系统存储", color = InkSoft, fontSize = 11.sp)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = InkSoft)
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
                Text("识别语言", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("新录音将按所选语言识别，已有转写保持原样", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = language == "zh",
                        onClick = {
                            language = "zh"
                            preferences.setPreferredLanguage("zh")
                        },
                        label = { Text("中文优先", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = language == "auto",
                        onClick = {
                            language = "auto"
                            preferences.setPreferredLanguage("auto")
                        },
                        label = { Text("自动识别", fontSize = 12.sp) },
                    )
                }
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
                Text("短录音过滤", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("有效人声和文字同时低于阈值才隐藏；原音不删除，标记片段豁免。文字设为0可关闭过滤。", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                Text("最短有效人声：${minimumSpeechSeconds.toInt()} 秒", modifier = Modifier.padding(top = 12.dp), fontSize = 12.sp)
                Slider(
                    value = minimumSpeechSeconds,
                    onValueChange = {
                        minimumSpeechSeconds = it
                    },
                    onValueChangeFinished = {
                        preferences.setMinimumSpeechSeconds(minimumSpeechSeconds.toInt())
                        onRebuildConversations()
                    },
                    valueRange = 1f..60f,
                    steps = 58,
                )
                Text("最少有效文字：${minimumTextCharacters.toInt()} 个字", modifier = Modifier.padding(top = 5.dp), fontSize = 12.sp)
                Slider(
                    value = minimumTextCharacters,
                    onValueChange = {
                        minimumTextCharacters = it
                    },
                    onValueChangeFinished = {
                        preferences.setMinimumTextCharacters(minimumTextCharacters.toInt())
                        onRebuildConversations()
                    },
                    valueRange = 0f..40f,
                    steps = 39,
                )
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column {
                ToggleRow("仅充电时处理新录音", chargeOnly) { chargeOnly = it; preferences.setChargeOnly(it) }
                Text("此设置用于新加入的处理任务，已经开始的任务会继续。", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp))
                Text("原始音频仅保存在本机，当前版本没有上传功能。", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(13.dp))
            }
        }
        Row(Modifier.padding(top = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, contentDescription = null, tint = InkSoft, modifier = Modifier.size(19.dp))
            Text("所有核心处理默认在本机完成", modifier = Modifier.padding(start = 8.dp), color = InkSoft, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RawRecordingsScreen(
    chunks: List<AudioChunkPreview>,
    onRetry: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri ->
        val sourcePath = exportPath
        if (uri == null || sourcePath == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val source = File(sourcePath)
                require(source.exists()) { "原始录音文件不存在" }
                FileInputStream(source).use { input ->
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        input.copyTo(output)
                    } ?: error("无法打开导出目标")
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (result.isSuccess) "原始录音已导出" else "导出失败：${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val selected = chunks.firstOrNull { it.id == selectedId && it.endedAtMillis != null }
    Scaffold(containerColor = Paper, bottomBar = {
        selected?.let { chunk ->
            TimelineAudioPlayer(PlaybackTimeline(listOf(PlaybackSlice(chunk.localPath, chunk.startedAtMillis, chunk.startedAtMillis, chunk.endedAtMillis!!))))
        }
    }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        DetailTopBar("原始录音", "本机保存 · ${chunks.size} 段", onBack)
        Text(
            "录音默认保存在声迹的本地空间。这里可以查看文件状态，也可以导出到系统存储；应用不会自动删除。",
            modifier = Modifier.padding(start = 46.dp, top = 4.dp),
            color = InkSoft,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
        )
        if (chunks.isEmpty()) {
            Text("还没有原始录音。开始记录后，录音切片会立即出现在这里。", modifier = Modifier.padding(top = 24.dp), color = InkSoft, fontSize = 13.sp)
        } else {
            chunks.forEach { chunk ->
                RawRecordingRow(
                    chunk = chunk,
                    selected = selectedId == chunk.id,
                    onPlay = { selectedId = chunk.id },
                    onRetry = { onRetry(chunk.id) },
                    onExport = {
                        exportPath = chunk.localPath
                        exportLauncher.launch(File(chunk.localPath).name)
                    },
                )
            }
        }
    }
    }
}

@Composable
private fun RawRecordingRow(chunk: AudioChunkPreview, selected: Boolean, onPlay: () -> Unit, onRetry: () -> Unit, onExport: () -> Unit) {
    val file = remember(chunk.localPath) { File(chunk.localPath) }
    val stateLabel = when (chunk.processingState) {
        "RECORDING" -> "正在录音"
        "RECORDED", "RECOVERED" -> "等待整理"
        "VAD_RUNNING", "VAD_READY" -> "正在检测人声"
        "ASR_RUNNING" -> "正在转写"
        "ASR_READY" -> when {
            chunk.transcriptCount == 0 -> "未识别 · 可回听原音"
            chunk.visibleTranscriptCount == 0 -> "短录音已过滤 · 原音保留"
            else -> "已整理"
        }
        else -> "原音已保留"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) PaleGreen else Color(0xFFFFFEFA),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, contentDescription = null, tint = Green, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(formatDateTime(chunk.startedAtMillis), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("${stateLabel} · ${formatBytes(chunk.byteSize)} · ${file.name}", color = InkSoft, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                chunk.errorMessage?.let { Text(it, color = InkSoft, fontSize = 11.sp) }
                Row {
                    TextButton(onClick = onPlay, enabled = chunk.endedAtMillis != null && file.exists()) { Text(if (selected) "已选中" else "回听") }
                    if (chunk.processingState.endsWith("FAILED")) TextButton(onClick = onRetry) { Text("重试处理") }
                    TextButton(onClick = onExport, enabled = chunk.endedAtMillis != null && file.exists()) { Text("导出") }
                }
            }
        }
    }
}

@Composable
private fun StorageValue(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = InkSoft, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, modifier = Modifier.padding(top = 5.dp), fontSize = 23.sp)
            Text(unit, modifier = Modifier.padding(start = 3.dp, bottom = 3.dp), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
