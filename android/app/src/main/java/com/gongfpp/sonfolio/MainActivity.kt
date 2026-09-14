package com.gongfpp.sonfolio

import android.Manifest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import android.media.MediaPlayer
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.gongfpp.sonfolio.data.local.ConversationSummaryEntity
import com.gongfpp.sonfolio.recording.RecordingFeedback
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

private val NavigationSaver = listSaver<AppNavigation, String>(
    save = { state -> state.stack.map { it.toSavedRoute() } },
    restore = { routes -> AppNavigation(routes.map(::appScreenFromSavedRoute).ifEmpty { listOf(AppScreen.Today) }) },
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
    var navigation by rememberSaveable(stateSaver = NavigationSaver) {
        mutableStateOf(AppNavigation())
    }
    val screen = navigation.current
    val screenStates = rememberSaveableStateHolder()
    val openScreen: (AppScreen) -> Unit = { navigation = navigation.open(it) }
    val goBack: () -> Unit = {
        if (!screen.isMainScreen) screenStates.removeState(screen.toSavedRoute())
        navigation = navigation.back()
    }
    BackHandler(enabled = navigation.canGoBack, onBack = goBack)
    val aliases by viewModel.conversationAliases.collectAsStateWithLifecycle()
    val recordingStatus by viewModel.recordingStatus.collectAsStateWithLifecycle()
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
    val isMainScreen = screen.isMainScreen

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            if (isMainScreen) {
                NavigationBar(containerColor = Paper) {
                    NavigationBarItem(
                        selected = screen is AppScreen.Today,
                        onClick = { navigation = navigation.selectTab(AppScreen.Today) },
                        icon = { Icon(Icons.Default.Home, contentDescription = "声迹") },
                        label = { Text("声迹") },
                    )
                    NavigationBarItem(
                        selected = screen is AppScreen.Search,
                        onClick = { navigation = navigation.selectTab(AppScreen.Search) },
                        icon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                        label = { Text("搜索") },
                    )
                    NavigationBarItem(
                        selected = screen is AppScreen.Settings,
                        onClick = { navigation = navigation.selectTab(AppScreen.Settings) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                        label = { Text("设置") },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            screenStates.SaveableStateProvider(screen.toSavedRoute()) {
                when (val current = screen) {
                    AppScreen.Today -> TodayScreen(
                        viewModel = viewModel,
                        recordingStatus = recordingStatus,
                        gaps = recordingGaps,
                        onStartRecording = requestRecordingStart,
                        onStopRecording = viewModel::stopRecording,
                        onMark = viewModel::markCurrentMoment,
                        onRecoverRecording = viewModel::recoverRecording,
                        onEndInterruptedRecording = viewModel::endInterruptedRecording,
                        onOpen = openScreen,
                    )
                    AppScreen.Search -> SearchScreen(viewModel = viewModel, onOpen = openScreen)
                    AppScreen.Settings -> SettingsScreen(
                        preferences = application.preferences,
                        recordingStatus = recordingStatus,
                        onOpenRawRecordings = { openScreen(AppScreen.RawRecordings()) },
                        onRebuildConversations = viewModel::rebuildConversations,
                    )
                    is AppScreen.Daily -> DailyScreen(viewModel = viewModel, initialDate = current.date, onBack = goBack)
                    is AppScreen.RawRecordings -> RawRecordingsScreen(
                        viewModel = viewModel,
                        date = current.date,
                        onRetry = viewModel::retryProcessing,
                        onBack = goBack,
                        onDeleteSelected = { viewModel.deleteChunks(it, protectMarked = true) },
                        onExportSelected = { ids, uri -> viewModel.exportChunksZip(uri, ids) },
                    )
                    is AppScreen.Conversation -> {
                        if (current.id != null) {
                            val canonicalId = aliases.firstOrNull { it.oldId == current.id }?.canonicalId ?: current.id
                            val conversation by remember(canonicalId) { viewModel.observeConversation(canonicalId) }.collectAsStateWithLifecycle(initialValue = null)
                            RealConversationScreen(
                                conversation = conversation,
                                conversationId = canonicalId,
                                initialTranscriptId = current.transcriptId,
                                searchQuery = current.query,
                                viewModel = viewModel,
                                onBack = goBack,
                            )
                        } else if (current.type == ConversationType.Game) {
                            GameSummaryScreen(onBack = goBack)
                        } else {
                            ConversationScreen(current.type, onBack = goBack)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayScreen(
    viewModel: SonfolioViewModel,
    recordingStatus: RecordingStatus,
    gaps: List<com.gongfpp.sonfolio.data.local.RecordingGapEntity>,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onMark: (Int) -> Unit,
    onRecoverRecording: () -> Unit,
    onEndInterruptedRecording: () -> Unit,
    onOpen: (AppScreen) -> Unit,
) {
    val today = rememberCurrentDay()
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val date = selectedDate?.let(LocalDate::parse) ?: today
    val window = remember(date) { DayWindow.of(date) }
    val conversations by remember(date) { viewModel.observeTimeline(window.start, window.end) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val recordingChunks by remember(date) { viewModel.observeChunks(window.start, window.end) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val calendar by viewModel.calendarSpans.collectAsStateWithLifecycle()
    var gapClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(gaps.any { it.endedAtMillis == null }) {
        while (gaps.any { it.endedAtMillis == null }) { gapClock = System.currentTimeMillis(); delay(1_000) }
    }
    val day = remember(date, conversations, recordingChunks, gaps, gapClock) { DayTimeline.build(date, conversations, recordingChunks, gaps, nowMillis = gapClock) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val unfinished = day.chunks.filter { it.processingState !in setOf("ASR_READY", "AUDIO_DELETED") }
    val recordedDates = remember(calendar) { calendar.filter { !it.organized }.flatMap { datesInRange(it.start, it.end) }.toSet() }
    val organizedDates = remember(calendar) { calendar.filter { it.organized }.flatMap { datesInRange(it.start, it.end) }.toSet() }
    LazyColumn(
        Modifier.fillMaxSize().testTag("timeline-list"), state = listState,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Text("声迹", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                if (recordingStatus.isRecording) "采集状态见下方 · 原音保存在本机" else "本地保存 · 按日期回看",
                color = InkSoft, fontSize = 14.sp,
            )
        }
        item(key = "recording") {
            RecordingCard(
                status = recordingStatus,
                onStart = { selectedDate = null; onStartRecording() },
                onStop = onStopRecording,
                onMark = onMark,
                onRecover = onRecoverRecording,
                onEndInterrupted = onEndInterruptedRecording,
            )
        }
        item(key = "date") {
            DateNavigator(date, today, recordedDates, organizedDates) { next ->
                selectedDate = next.takeUnless { it == today }?.toString()
                scope.launch { listState.scrollToItem(0) }
            }
        }
        item(key = "journal") {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { onOpen(AppScreen.Daily(date.toString())) },
                shape = RoundedCornerShape(15.dp),
                color = PaleGreen,
            ) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Green, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("一日回顾", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("${date.format(DateTimeFormatter.ofPattern("M月d日"))} · 查看这一天的总结", color = InkSoft, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Green)
                }
            }
        }
        item(key = "health") { DayRecordingCard(day) { onOpen(AppScreen.RawRecordings(date.toString())) } }
        if (day.chunks.isNotEmpty()) {
            item(key = "processing") { ProcessingSummaryCard(day.chunks) { onOpen(AppScreen.RawRecordings(date.toString())) } }
        }
        item(key = "timeline-title") { SectionTitle("对话时间线 · ${day.conversations.size}场") }
        if (day.conversations.isEmpty() && day.chunks.none { it.processingState != "ASR_READY" }) {
            item(key = "empty") {
                Text(
                    if (day.chunks.isEmpty()) "这一天还没有录音，可以选择其他日期回看。" else "原音已保存，这一天暂无可显示的对话。未识别或已过滤的内容仍可在原始录音中回听。",
                    color = InkSoft, fontSize = 13.sp,
                )
            }
        }
        items(unfinished.take(5), key = { "chunk:${it.id}" }) { chunk ->
            RawAudioCard(chunk) { onOpen(AppScreen.RawRecordings(date.toString())) }
        }
        if (unfinished.size > 5) item(key = "more-raw") {
            TextButton(onClick = { onOpen(AppScreen.RawRecordings(date.toString())) }) { Text("查看更多原音（另有 ${unfinished.size - 5} 段）") }
        }
        items(day.conversations, key = { "conversation:${it.id}" }) { conversation ->
            Column {
                TimelineCard(conversation) { onOpen(AppScreen.Conversation(type = conversation.type, id = conversation.id)) }
                if (conversation.startedAtMillis < window.start || conversation.endedAtMillis > window.end) {
                    Text("跨日对话 · 打开后可查看及回听完整内容", Modifier.padding(start = 22.dp, top = 3.dp), color = InkSoft, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun rememberCurrentDay(): LocalDate {
    var day by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { day = LocalDate.now(); delay(30_000L) } }
    return day
}

@Composable
private fun DateNavigator(date: LocalDate, today: LocalDate = rememberCurrentDay(), recorded: Set<LocalDate> = emptySet(), organized: Set<LocalDate> = emptySet(), onSelect: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (open) RecordingCalendarDialog(date, today, recorded, organized, { open = false }) { next -> onSelect(next); open = false }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSelect(date.minusDays(1)) }) { Icon(Icons.Default.ChevronLeft, "前一天") }
        TextButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
            Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { onSelect(date.plusDays(1)) }, enabled = date < today) { Icon(Icons.Default.ChevronRight, "后一天") }
        if (date != today) TextButton(onClick = { onSelect(today) }) { Text("本日", fontSize = 12.sp) }
    }
}

@Composable
private fun DayRecordingCard(day: DayTimeline, onOpenRaw: () -> Unit) {
    var gapsOpen by rememberSaveable(day.gaps.map { it.id }) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), color = PaleGreen) {
        Column(Modifier.padding(13.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("已保存 ${formatElapsed(day.savedMillis)}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("${day.chunks.size} 段原音", color = InkSoft, fontSize = 12.sp)
            }
            TextButton(onClick = onOpenRaw) { Text("查看当日原始录音") }
            if (day.gaps.isNotEmpty()) {
                TextButton(onClick = { gapsOpen = !gapsOpen }) {
                    Text("${day.gaps.size} 次中断 · 缺口 ${formatPlaybackTime(day.gapMillis)} · ${if (gapsOpen) "收起" else "查看"}", color = Color(0xFF805900))
                }
                if (gapsOpen) day.gaps.forEach { gap ->
                    Text("${formatDateTime(gap.startedAtMillis)} — ${gap.endedAtMillis?.let(::formatDateTime) ?: "等待恢复"}\n${gap.reason}", Modifier.padding(vertical = 5.dp), color = InkSoft, fontSize = 11.sp)
                }
            } else Text("未记录到异常中断", color = InkSoft, fontSize = 11.sp)
            Text("保存时长按文件计算，不保证每秒都有有效声音；未主动开启的时段不计缺口，异常中断持续计至恢复。", Modifier.padding(top = 5.dp), color = InkSoft, fontSize = 10.sp)
        }
    }
}

@Composable
private fun RecordingCard(
    status: RecordingStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMark: (Int) -> Unit,
    onRecover: () -> Unit,
    onEndInterrupted: () -> Unit,
) {
    val markWindows = (LocalContext.current.applicationContext as SonfolioApplication).preferences.markerWindows
    var sliceHelp by remember { mutableStateOf(false) }
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
        Column {
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
                        if (status.isRecording) {
                            if (status.health.clientSilenced == true) "输入被静音" else if (status.health.lastBufferAtMillis == null) "正在启动" else "正在记录"
                        } else if (status.interruptionPending) "恢复记录" else "开始记录",
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
                InputWaveform(levels = status.health.levels, accent = if (status.health.clientSilenced == true) Amber else Green, modifier = Modifier.width(42.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier
                        .alpha(if (status.isRecording) 1f else .45f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(AmberPale)
                        .clickable(enabled = status.isRecording) { onMark(markWindows.first()) }
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("★", color = Amber, fontSize = 18.sp)
                    Text("标记（${markWindows.first()}分）", color = Color(0xFF694E00), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    markWindows.drop(1).forEach { minutes ->
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (status.isRecording) AmberPale else Color(0xFFE8E8E3))
                                .clickable(enabled = status.isRecording) { onMark(minutes) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${minutes}分", color = Color(0xFF694E00), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        TextButton(onClick = { sliceHelp = true }) { Text("每 5 分钟保存一份原音 · 切片说明 ⓘ", fontSize = 11.sp) }
        if (sliceHelp) AlertDialog(onDismissRequest = { sliceHelp = false },
            title = { Text("文件切片不等于对话切割") },
            text = { Text("5 分钟切片是为了边录边处理，并减少异常退出时未收尾的范围。相邻语音间隔不超过 2 分钟、且没有已知录音缺口时，会合并为同一场对话，能够跨越多个文件。\n\n总结使用整场对话的已识别文字；长内容分段时会携带上一部分的总结。后续转写到达后会更新基础小结，旧 AI 结果会标为需要重新生成。\n\n当前按时间连续性合并，不是语义主题识别：同一主题停顿过久仍可能被分开，总结也需结合原文核对。") },
            confirmButton = { TextButton(onClick = { sliceHelp = false }) { Text("知道了") } })
        if (status.isRecording || status.interruptionPending || status.health.failure != null) {
            val failure = status.health.failure
            Text(
                if (!status.isRecording && status.interruptionPending && failure == null) "录音已中断，缺口将计至重新采集到音频。" else status.health.message(nowMillis),
                modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 4.dp, bottom = if (failure != null) 6.dp else 12.dp),
                color = InkSoft, fontSize = 11.sp,
            )
            if (failure != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(onClick = onRecover) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("尝试恢复", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onEndInterrupted) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("结束本次记录", fontWeight = FontWeight.Bold)
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

/**
 * 把 query 在 text 中的命中片段用强调样式标出，用于搜索结果与定位行的高亮。
 * query 为空或没有命中时原样返回，不分配额外对象。
 */
private fun highlightText(text: String, query: String?): AnnotatedString {
    if (query.isNullOrBlank()) return AnnotatedString(text)
    val lower = text.lowercase()
    val needle = query.lowercase()
    if (needle.isEmpty() || !lower.contains(needle)) return AnnotatedString(text)
    val ranges = mutableListOf<AnnotatedString.Range<SpanStyle>>()
    var cursor = 0
    var guard = 0
    while (guard++ < 100) {
        val at = lower.indexOf(needle, cursor)
        if (at < 0) break
        ranges.add(
            AnnotatedString.Range(
                SpanStyle(background = AmberPale, color = Color(0xFF694E00), fontWeight = FontWeight.Bold),
                at,
                at + needle.length,
            ),
        )
        cursor = at + needle.length
    }
    return AnnotatedString(text, spanStyles = ranges)
}

@Composable
private fun ProcessingSummaryCard(chunks: List<AudioChunkPreview>, onOpen: () -> Unit) {
    val recording = chunks.count { it.processingState == "RECORDING" }
    val processing = chunks.count { it.processingState in setOf("VAD_RUNNING", "ASR_RUNNING") }
    val waiting = chunks.count { it.processingState in setOf("RECORDED", "RECOVERED", "VAD_READY", "ASSEMBLY_PENDING", "ASSEMBLY_FAILED") }
    val failed = chunks.count { it.processingState.endsWith("FAILED") }
    val ready = chunks.count { it.processingState == "ASR_READY" }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFF7DD),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEAD9A4)),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Amber, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("录音处理进度", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("① 保存原音 → ② 找人声 → ③ 转写 → ④ 整理对话", color = InkSoft, fontSize = 10.sp)
                    Text(
                        when {
                            processing > 0 -> "正在本地识别，已完成${ready}段${if (recording > 0) " · 同时继续录音" else ""}"
                            failed > 0 -> "${failed}段处理失败，点击查看原音并重试"
                            recording > 0 -> "原音持续保存，每5分钟或停止时开始整理"
                            waiting > 0 -> chunks.firstOrNull { it.errorMessage != null }?.errorMessage ?: "${waiting}段等待处理；若启用了仅充电处理，请接通电源或关闭该开关"
                            else -> "${ready}段原音已保存并处理 · 未识别或过滤的录音可在这里查看"
                        },
                        color = InkSoft,
                        fontSize = 11.5.sp,
                    )
                }
                Text("${chunks.size}段", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(start = 4.dp)) {
                Text(if (expanded) "收起各段状态" else "展开各段状态（${chunks.size}）", fontSize = 12.sp)
            }
            if (expanded) {
                chunks.sortedByDescending { it.startedAtMillis }.forEach { chunk ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDateTime(chunk.startedAtMillis), color = InkSoft, fontSize = 11.sp, modifier = Modifier.width(90.dp))
                        Text(chunkStageLabel(chunk.processingState), color = Color(0xFF725B18), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(formatBytes(chunk.byteSize), color = InkSoft, fontSize = 11.sp)
                    }
                    chunk.errorMessage?.let { Text(it, Modifier.padding(start = 103.dp, end = 13.dp).padding(bottom = 2.dp), color = InkSoft, fontSize = 10.5.sp) }
                }
            }
        }
    }
}

/** 把切片的内部处理状态翻译成用户能理解的阶段文案。 */
private fun chunkStageLabel(state: String): String = when (state) {
    "RECORDING" -> "正在录音"
    "RECORDED", "RECOVERED" -> "① 原音已保存"
    "VAD_RUNNING" -> "② 正在找人声"
    "VAD_READY" -> "③ 等待转写"
    "ASR_RUNNING" -> "③ 正在转写"
    "ASSEMBLY_PENDING", "ASSEMBLY_FAILED" -> "④ 等待整理对话"
    "ASR_READY" -> "已完成"
    "AUDIO_DELETED" -> "原音已清理 · 文字保留"
    else -> if (state.endsWith("FAILED")) "处理失败" else "处理中"
}

@Composable
private fun RawAudioCard(chunk: AudioChunkPreview, onClick: () -> Unit) {
    val elapsed = chunk.endedAtMillis?.let { formatElapsed(it - chunk.startedAtMillis) } ?: "录音中"
    val processingLabel = when (chunk.processingState) {
        "RECORDING" -> "原始对话 · 正在录音"
        "RECORDED", "RECOVERED" -> "原始对话 · 等待识别"
        "VAD_RUNNING" -> "② 正在找人声"
        "VAD_READY" -> "③ 等待转写 · 点击查看原因"
        "ASSEMBLY_PENDING", "ASSEMBLY_FAILED" -> "④ 文字已保存 · 等待整理"
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
private fun InputWaveform(levels: List<Float>, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier.height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(12) { index ->
            val scale = levels.getOrElse(index) { 0f }.coerceIn(.06f, 1f)
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
    searchQuery: String? = null,
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
    var transcriptOpen by rememberSaveable(conversationId) { mutableStateOf(initialTranscriptId != null) }
    var seekLineId by remember(conversationId) { mutableStateOf(initialTranscriptId) }
    var playLineId by remember(conversationId) { mutableStateOf<String?>(null) }
    var playNonce by rememberSaveable(conversationId) { mutableLongStateOf(0L) }
    var titleDraft by remember(conversationId) { mutableStateOf<String?>(null) }
    var noteDraft by remember(conversationId) { mutableStateOf<String?>(null) }
    var editingLine by remember(conversationId) { mutableStateOf<TranscriptLine?>(null) }
    var editLineText by remember(conversationId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(seekLineId, lines) {
        val idx = lines.indexOfFirst { it.id == seekLineId }
        if (idx >= 0) listState.scrollToItem(idx + 1)
    }
    val chunkStart = lines.minOfOrNull { it.startedAtMillis } ?: 0L
    val chunkEnd = lines.maxOfOrNull { it.endedAtMillis } ?: 0L
    val chunks by remember(chunkStart, chunkEnd) { viewModel.observeChunks(chunkStart, chunkEnd) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val title = conversation?.title ?: "未识别"
    val meta = conversation?.let { formatConversationMeta(it) } ?: "正在整理原始对话"
    val summary = conversation?.summary ?: "正在从本地转写中生成本段小结。"
    val detailFields = listOf(
        "识别片段" to "${lines.size} 段",
        "整理方式" to when {
            structuredSummary?.modelVersion?.startsWith("REMOTE:") == true -> "外部 AI 总结"
            structuredSummary?.modelVersion?.startsWith("LOCAL:") == true -> "手机本地 AI"
            else -> "本地提取式小结"
        },
        "说话人" to "暂不区分",
    )
    val exportContext = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val exportTextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val payload = buildConversationText(title, meta, summary, conversation?.note, structuredSummary, lines)
        exportScope.launch(Dispatchers.IO) {
            val result = runCatching {
                exportContext.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) } ?: error("无法打开导出目标")
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    exportContext,
                    if (result.isSuccess) "对话文本已导出" else "导出失败：${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            RealAudioPlayer(
                lines = lines,
                chunks = chunks,
                requestedLineId = seekLineId,
                playRequest = playLineId?.let { id -> playNonce.takeIf { it > 0 }?.let { id to it } },
                onLocateConsumed = { seekLineId = null },
                onPlayConsumed = { playLineId = null; playNonce = 0 },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        ) {
            item(key = "summary") {
                Column {
                    DetailTopBar(title, meta, onBack)
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { titleDraft = conversation?.title ?: "" }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("改标题", fontSize = 12.sp)
                        }
                        if (conversation?.isMarked == true) {
                            TextButton(onClick = { viewModel.removeConversationMarker(conversationId) }) { Text("取消标记", fontSize = 12.sp, color = Color(0xFF805900)) }
                        }
                        TextButton(onClick = { exportTextLauncher.launch("sonfolio-对话文本.txt") }) { Text("导出文本", fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(9.dp))
                    val marked = lines.filter { it.isMarked }
                    if (marked.isNotEmpty()) {
                        Surface(Modifier.fillMaxWidth().padding(bottom = 10.dp), RoundedCornerShape(10.dp), color = AmberPale) {
                            Text(
                                "★ 已高亮 ${formatReadableDuration(marked.maxOf { it.endedAtMillis } - marked.minOf { it.startedAtMillis })} · 标记覆盖整段连续对话",
                                modifier = Modifier.padding(10.dp), color = Color(0xFF694E00), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    SummaryCard(summary, detailFields, detailFields.first { it.first == "整理方式" }.second)
                    Surface(Modifier.fillMaxWidth().padding(top = 10.dp), RoundedCornerShape(12.dp), color = Color(0xFFF4F1E4)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("备注", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { noteDraft = conversation?.note ?: "" }) {
                                    Text(if (conversation?.note.isNullOrBlank()) "添加" else "编辑", fontSize = 12.sp)
                                }
                            }
                            Text(
                                conversation?.note?.takeIf { it.isNotBlank() } ?: "还没有备注，可写一句提醒自己。",
                                color = if (conversation?.note.isNullOrBlank()) InkSoft else Color(0xFF4A4632),
                                fontSize = 12.5.sp,
                            )
                        }
                    }
                    com.gongfpp.sonfolio.summary.SummaryAction("conversation:$conversationId")
                    structuredSummary?.let { structured ->
                        Spacer(Modifier.height(10.dp))
                        SummaryPointsCard(structured, lines) { id -> transcriptOpen = true; seekLineId = id }
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
                }
            }
            if (transcriptOpen) {
                if (lines.isEmpty()) {
                    item(key = "empty") {
                        Text("本段还没有可显示的文字，后台处理完成后会自动刷新。", color = InkSoft, fontSize = 12.5.sp)
                    }
                } else {
                    items(lines, key = { it.id }) { line ->
                        val located = line.id == seekLineId
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { seekLineId = line.id },
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                located -> Color(0xFFDCEFE8)
                                line.isMarked -> AmberPale
                                else -> Color.Transparent
                            },
                        ) {
                            Column(Modifier.padding(vertical = 6.dp, horizontal = 5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { seekLineId = line.id; playLineId = line.id; playNonce++ },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "播放这一句", tint = Green, modifier = Modifier.size(18.dp))
                                    }
                                    Text(
                                        if (line.isMarked) "★ ${formatClock(line.startedAtMillis)}" else formatClock(line.startedAtMillis),
                                        modifier = Modifier.width(58.dp),
                                        color = if (line.isMarked) Amber else InkSoft,
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        highlightText(line.text, searchQuery),
                                        modifier = Modifier.weight(1f),
                                        color = Color(0xFF3E4A42),
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                    )
                                    IconButton(
                                        onClick = { editingLine = line; editLineText = line.text },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "修正这一句", tint = InkSoft, modifier = Modifier.size(16.dp))
                                    }
                                }
                                if (line.originalText != null) {
                                    Text(
                                        "已修正 · 原始版本：${line.originalText}",
                                        modifier = Modifier.padding(start = 58.dp, top = 2.dp),
                                        color = InkSoft,
                                        fontSize = 10.5.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item(key = "playback-hint") {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Text("点击转写行可定位；行首按钮播放这一句，末尾铅笔可修正文字（保留原始版本）", color = InkSoft, fontSize = 11.sp)
                }
            }
        }
        titleDraft?.let { draft ->
            var value by remember(draft) { mutableStateOf(draft) }
            AlertDialog(
                onDismissRequest = { titleDraft = null },
                title = { Text("修改对话标题") },
                text = { OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("标题（最多 30 字）") }) },
                confirmButton = { TextButton(onClick = { viewModel.updateConversationTitle(conversationId, value); titleDraft = null }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { titleDraft = null }) { Text("取消") } },
            )
        }
        noteDraft?.let { draft ->
            var value by remember(draft) { mutableStateOf(draft) }
            AlertDialog(
                onDismissRequest = { noteDraft = null },
                title = { Text("对话备注") },
                text = { Column {
                    OutlinedTextField(value, { value = it }, label = { Text("简短备注（最多 200 字）") }, minLines = 2)
                } },
                confirmButton = { TextButton(onClick = { viewModel.updateConversationNote(conversationId, value); noteDraft = null }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { noteDraft = null }) { Text("取消") } },
            )
        }
        editingLine?.let { line ->
            AlertDialog(
                onDismissRequest = { editingLine = null },
                title = { Text("修正这一句") },
                text = { Column {
                    OutlinedTextField(editLineText, { editLineText = it }, label = { Text("识别文字") }, minLines = 2)
                    if (line.originalText == null) Text("保存后会保留原始识别版本。", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    else Text("原始版本：${line.originalText}", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                } },
                confirmButton = { TextButton(onClick = { viewModel.updateTranscriptText(line.id, editLineText); editingLine = null }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { editingLine = null }) { Text("取消") } },
            )
        }
    }
}

private fun formatConversationMeta(item: ConversationPreview): String {
    val end = if (localDateAt(item.startedAtMillis) == localDateAt(item.endedAtMillis)) formatClock(item.endedAtMillis)
        else formatDateTime(item.endedAtMillis)
    return "${formatDateTime(item.startedAtMillis)}–$end · ${item.duration}"
}

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
    playRequest: Pair<String, Long>?,
    onLocateConsumed: () -> Unit,
    onPlayConsumed: () -> Unit,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as SonfolioApplication
    val gaps by remember(app) { app.database.recordingDao().observeGaps() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val timeline = remember(lines, chunks, gaps) { PlaybackTimeline.forConversation(lines, chunks, gaps) }
    val locateTime = requestedLineId?.let { id -> lines.firstOrNull { it.id == id }?.startedAtMillis }
    val playTime = playRequest?.let { (id, _) -> lines.firstOrNull { it.id == id }?.startedAtMillis }
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
private fun TimelineAudioPlayer(
    timeline: PlaybackTimeline,
    requestedTime: Long? = null,
    playTime: Long? = null,
    playNonce: Long = 0L,
    onLocateConsumed: () -> Unit = {},
    onPlayConsumed: () -> Unit = {},
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
                    "当前进度位于已知录音缺口，原文件可能只有静音。" else "这段原音含已知缺口，缺失内容无法回听。",
                color = Color(0xFF805900), fontSize = 11.sp,
            )
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
private fun SummaryCard(summary: String, fields: List<Pair<String, String>>, origin: String = "本地基础整理") {
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
                Text("⌁  $origin", modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Green, fontSize = 11.sp)
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

/**
 * 结构化要点：每条要点可点击定位到最接近的原句，方便逐条核对，而不是只给一句「请结合原文核对」。
 */
@Composable
private fun SummaryPointsCard(summary: ConversationSummaryEntity, lines: List<TranscriptLine>, onLocate: (String) -> Unit) {
    val sections = listOf(
        "讨论要点" to summary.keyPointsJson,
        "提到的决定" to summary.decisionsJson,
        "提到的安排" to summary.followUpsJson,
        "提出的问题" to summary.openQuestionsJson,
    ).mapNotNull { (label, json) -> parseJsonArray(json).takeIf { it.isNotEmpty() }?.let { label to it } }
    if (sections.isEmpty()) return
    Surface(shape = RoundedCornerShape(14.dp), color = PaleGreen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
                Text("重点整理", modifier = Modifier.padding(start = 8.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("点条目核对原文", color = InkSoft, fontSize = 10.sp)
            }
            sections.forEach { (label, points) ->
                Text(label, modifier = Modifier.padding(top = 10.dp), fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                points.forEach { point ->
                    val matchId = remember(point, lines) { bestMatchLineId(lines, point) }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 5.dp)
                            .clickable(enabled = matchId != null) { matchId?.let(onLocate) },
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text("•", color = Green, fontSize = 12.5.sp)
                        Text(point, modifier = Modifier.padding(start = 6.dp).weight(1f), color = Color(0xFF3D4B41), fontSize = 12.5.sp, lineHeight = 19.sp)
                        if (matchId != null) Text("定位", color = Green, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
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
private fun DailyScreen(viewModel: SonfolioViewModel, initialDate: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var localDate by rememberSaveable { mutableStateOf(initialDate) }
    val journal by key(localDate) {
        remember(localDate) { viewModel.observeDailyJournal(localDate) }.collectAsStateWithLifecycle(initialValue = null)
    }
    val narrative = journal?.narrative
    val sourceCount = journal?.sourceConversationCount ?: 0
    val calendar by viewModel.calendarSpans.collectAsStateWithLifecycle()
    val recordedDates = remember(calendar) { calendar.filter { !it.organized }.flatMap { datesInRange(it.start, it.end) }.toSet() }
    val organizedDates = remember(calendar) { calendar.filter { it.organized }.flatMap { datesInRange(it.start, it.end) }.toSet() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        DetailTopBar("一日回顾", localDate, onBack)
        DateNavigator(LocalDate.parse(localDate), recorded = recordedDates, organized = organizedDates) { localDate = it.toString() }
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
        com.gongfpp.sonfolio.summary.SummaryAction("day:$localDate")
        TextButton(onClick = viewModel::rebuildConversations) { Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("重新整理") }
    }
}

private fun parseJsonLines(value: String?): String = runCatching {
    val array = JSONArray(value ?: "[]")
    (0 until array.length()).map { array.getString(it) }.joinToString("\n") { "• $it" }
}.getOrDefault("")

private fun parseJsonArray(value: String?): List<String> = runCatching {
    val array = JSONArray(value ?: "[]")
    (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
}.getOrDefault(emptyList())

/**
 * 为一条摘要要点在转写里找最接近的原句，用于「定位原文」核对。
 * 先精确包含，再回退到词元命中最多的一行。
 */
private fun bestMatchLineId(lines: List<TranscriptLine>, point: String): String? {
    val target = point.trim()
    if (target.isEmpty() || lines.isEmpty()) return null
    lines.firstOrNull { it.text.contains(target) || target.contains(it.text) }?.let { return it.id }
    val tokens = target.split(Regex("[\\s\\p{Z}，。、；：！？,.!?;:（）()\\[\\]「」『』]+"))
        .filter { it.length >= 2 }
    if (tokens.isEmpty()) return null
    return lines
        .map { line -> line to tokens.count { line.text.contains(it) } }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first?.id
}

/** 把一段对话（小结、备注、结构化要点、逐句转写）整理成可导出的纯文本。 */
private fun buildConversationText(
    title: String,
    meta: String,
    summary: String,
    note: String?,
    structured: ConversationSummaryEntity?,
    lines: List<TranscriptLine>,
): String = buildString {
    appendLine("$title（$meta）")
    if (!note.isNullOrBlank()) appendLine("备注：$note")
    appendLine()
    appendLine("【小结】")
    appendLine(summary)
    if (structured != null) {
        listOf(
            "讨论要点" to structured.keyPointsJson,
            "提到的决定" to structured.decisionsJson,
            "提到的安排" to structured.followUpsJson,
            "提出的问题" to structured.openQuestionsJson,
        ).forEach { (label, json) ->
            val points = parseJsonArray(json)
            if (points.isNotEmpty()) {
                appendLine()
                appendLine("【$label】")
                points.forEach { appendLine("• $it") }
            }
        }
    }
    appendLine()
    appendLine("【转写】")
    lines.forEach { line ->
        appendLine("${formatClock(line.startedAtMillis)}  ${line.text}")
        line.originalText?.let { appendLine("　（原始版本：$it）") }
    }
}

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
    var dateRange by rememberSaveable { mutableStateOf(SearchDateRange.All) }
    var markedOnly by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun resetScroll() { scope.launch { listState.scrollToItem(0) } }
    val today = rememberCurrentDay()
    var visibleLimit by rememberSaveable(query, dateRange, markedOnly, today.toString()) { mutableIntStateOf(SEARCH_BATCH_SIZE) }
    val results by key(query, dateRange, markedOnly, today) {
        remember(query, dateRange, markedOnly, today, visibleLimit) {
            viewModel.observeSearch(query, dateRange, markedOnly, visibleLimit)
        }.collectAsStateWithLifecycle(initialValue = null)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text("搜索记忆", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; resetScroll() },
            modifier = Modifier.fillMaxWidth().padding(top = 17.dp),
            placeholder = { Text("搜索转写内容或对话主题") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; resetScroll() }) { Icon(Icons.Default.Close, contentDescription = "清空搜索") } },
            shape = RoundedCornerShape(11.dp),
        )
        Text("时间范围", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchDateRange.entries.forEach { range ->
                FilterChip(
                    selected = dateRange == range,
                    onClick = { dateRange = range; resetScroll() },
                    label = { Text(range.label, fontSize = 12.sp) },
                )
            }
        }
        Text("筛选", color = InkSoft, fontSize = 11.sp)
        Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = markedOnly,
                onClick = { markedOnly = !markedOnly; resetScroll() },
                label = { Text("仅标记", fontSize = 12.sp) },
            )
        }
        SearchResultsPanel(
            query, dateRange, markedOnly, results, visibleLimit, listState,
            onLoadMore = { visibleLimit = (visibleLimit.toLong() + SEARCH_BATCH_SIZE).coerceAtMost(Int.MAX_VALUE - 1L).toInt() },
            onOpen = onOpen,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun SearchResultsPanel(
    query: String,
    dateRange: SearchDateRange,
    markedOnly: Boolean,
    results: SearchResults?,
    visibleLimit: Int,
    listState: LazyListState,
    onLoadMore: () -> Unit,
    onOpen: (AppScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        val noCriteria = query.isBlank() && dateRange == SearchDateRange.All && !markedOnly
        Text(
            when {
                results?.errorMessage != null -> results.errorMessage
                noCriteria -> "输入文字后搜索本地转写"
                results == null -> "正在搜索…"
                results.hasMore -> "已显示 ${results.hits.size} 条相关内容 · 还有更多"
                else -> "找到 ${results.hits.size} 条相关内容"
            }, color = InkSoft, fontSize = 13.sp,
        )
        val hits = results?.hits
        if (results?.errorMessage != null) return@Column
        if (hits != null && hits.isEmpty()) {
            Text(
                if (noCriteria) "搜索不会自动列出全部记录；你可以输入主题、关键词或人名。"
                else if (query.isBlank()) "此条件下没有已整理的内容。" else "没有找到包含“$query”的转写。",
                modifier = Modifier.padding(top = 20.dp),
                color = InkSoft,
                fontSize = 13.sp,
            )
        } else if (hits != null) {
            LazyColumn(Modifier.weight(1f).testTag("search-results"), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                items(hits, key = { "hit:${it.transcriptId}" }) { hit ->
                    SearchResult(
                        date = formatDateTime(hit.startedAtMillis).substringBefore(' '),
                        title = if (hit.isMarked) "★ ${hit.title}" else hit.title,
                        excerpt = hit.text,
                        trailing = formatClock(hit.startedAtMillis),
                        query = query,
                    ) {
                        onOpen(AppScreen.Conversation(ConversationType.Unknown, hit.conversationId, hit.transcriptId, query))
                    }
                }
                if (results.hasMore) {
                    item(key = "load-more") {
                        TextButton(
                            onClick = onLoadMore,
                            enabled = results.requestedLimit >= visibleLimit,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            Text(if (results.requestedLimit < visibleLimit) "正在加载…" else "加载更多")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResult(date: String, title: String, excerpt: String, trailing: String, query: String? = null, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onClick), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, color = InkSoft, fontSize = 13.sp)
                Text(title, modifier = Modifier.padding(start = 8.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(trailing, color = InkSoft, fontSize = 11.sp)
            }
            Text(highlightText(excerpt, query), modifier = Modifier.padding(top = 9.dp), color = Color(0xFF3E4A42), fontSize = 12.5.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun SettingsScreen(
    preferences: SonfolioPreferences,
    recordingStatus: RecordingStatus,
    onOpenRawRecordings: () -> Unit,
    onRebuildConversations: () -> Unit,
) {
    var chargeOnly by remember { mutableStateOf(preferences.chargeOnly) }
    val settingsScope = rememberCoroutineScope()
    var policyMessage by remember { mutableStateOf<String?>(null) }
    var retentionDays by remember { mutableStateOf(preferences.retentionDays) }
    val context = LocalContext.current
    val app = context.applicationContext as SonfolioApplication
    val used by remember { app.database.recordingDao().observeStorageBytes() }.collectAsStateWithLifecycle(initialValue = 0L)
    val available = remember(used) { android.os.StatFs(context.filesDir.path).availableBytes }
    val bytesPerDay = 16_000L * 2L * 86_400L
    var language by remember { mutableStateOf(preferences.preferredLanguage) }
    var minimumSpeechSeconds by remember { mutableStateOf(preferences.minimumSpeechSeconds.toFloat()) }
    var minimumTextCharacters by remember { mutableStateOf(preferences.minimumTextCharacters.toFloat()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text("录音与存储", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("声迹 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", color = InkSoft, fontSize = 12.sp)
        com.gongfpp.sonfolio.processing.TranscriptionSettingsCard()
        com.gongfpp.sonfolio.summary.SummarySettingsCard()
        MarkerWindowSettings(preferences)
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
                val expiryTime = remember(retentionDays) { if (retentionDays > 0) System.currentTimeMillis() - retentionDays * 86_400_000L else Long.MIN_VALUE }
                val expired by remember(expiryTime) { app.database.recordingDao().observeExpiredCount(expiryTime) }.collectAsStateWithLifecycle(initialValue = 0)
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
                ToggleRow("仅充电时自动处理录音", chargeOnly) { value ->
                    chargeOnly = value; preferences.setChargeOnly(value)
                    policyMessage = "正在更新等待中的任务…"
                    settingsScope.launch {
                        policyMessage = try {
                            val app = context.applicationContext as SonfolioApplication
                            app.processingScheduler.refreshConstraints()
                            app.summaryCoordinator.refreshConstraints()
                            app.recordingRepository.enqueuePendingVad()
                            app.recordingRepository.enqueuePendingAsr()
                            if (value) "等待中的任务改为充电时执行" else "已解除等待任务的充电限制，系统将继续调度"
                        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                        catch (_: Exception) { "设置已保存，但队列更新失败；重新打开应用会重试" }
                    }
                }
                Text("包括人声检测、转写和自动 AI 总结。关闭后，已等待的任务也可在未充电时继续。开启不主动打断当前一轮；正在运行的旧任务若遇系统限制，下一轮按新设置执行。手动 AI 总结不要求充电。录音不受影响。", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp))
                policyMessage?.let { Text(it, color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 13.dp)) }
                Text("默认本地识别，不上传音频。只有单独启用外部转文字并确认后才上传人声片段；外部总结仅发送转写文字。", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(13.dp))
            }
        }
        com.gongfpp.sonfolio.recording.BackupSettingsCard()
        Row(Modifier.padding(top = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, contentDescription = null, tint = InkSoft, modifier = Modifier.size(19.dp))
            Text("所有核心处理默认在本机完成", modifier = Modifier.padding(start = 8.dp), color = InkSoft, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RawRecordingsScreen(
    viewModel: SonfolioViewModel,
    date: String?,
    onRetry: (String) -> Unit,
    onBack: () -> Unit,
    onDeleteSelected: suspend (Set<String>) -> String,
    onExportSelected: suspend (Set<String>, Uri) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(date == null) }
    val selectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })
    var selection by rememberSaveable(stateSaver = selectionSaver) { mutableStateOf(emptySet<String>()) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var operationBusy by remember { mutableStateOf(false) }
    fun leavePage() {
        if (operationBusy) return
        when {
            selectedId != null -> selectedId = null
            selectionMode -> { selectionMode = false; selection = emptySet() }
            else -> onBack()
        }
    }
    BackHandler(onBack = ::leavePage)
    var cleanupIds by remember { mutableStateOf<Set<String>?>(null) }
    val app = context.applicationContext as SonfolioApplication
    var uploadRequest by remember { mutableStateOf<Pair<String, com.gongfpp.sonfolio.processing.TranscriptionConfig>?>(null) }
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var visibleCount by rememberSaveable(date, showAll) { mutableIntStateOf(30) }
    val window = remember(date, showAll) { if (date == null || showAll) DayWindow(Long.MIN_VALUE, Long.MAX_VALUE) else DayWindow.of(LocalDate.parse(date)) }
    val chunks by remember(window, visibleCount) { viewModel.observeChunks(window.start, window.end, visibleCount + 1, includeDeleted = false) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val dao = (context.applicationContext as SonfolioApplication).database.recordingDao()
    val totalCount by remember(window) { dao.observeChunkCount(window.start, window.end) }.collectAsStateWithLifecycle(initialValue = 0)
    val displayedChunks = chunks
    fun prepareCleanup(silence: Boolean) {
        if (operationBusy || cleanupIds != null) return
        operationBusy = true
        scope.launch {
            try {
                val ids = dao.getCleanupCandidates(window.start, window.end, silence).toSet()
                if (ids.isEmpty()) operationMessage = "没有符合条件的原音，无需清理" else cleanupIds = ids
            }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { operationMessage = "读取清理清单失败，未删除任何文件" }
            finally { operationBusy = false }
        }
    }
    val allIds = displayedChunks.take(visibleCount).filter { it.processingState != "AUDIO_DELETED" }.map { it.id }
    val allSelected = allIds.isNotEmpty() && selection.containsAll(allIds)
    val availableBytes = remember(chunks) { android.os.StatFs(context.filesDir.path).availableBytes }
    val bytesPerDay = 16_000L * 2L * 86_400L
    val hoursLeft = (availableBytes.toDouble() / bytesPerDay) * 24.0
    val remainingLabel = if (hoursLeft >= 48) "预计还能录约 ${(hoursLeft / 24).toInt()} 天" else "预计还能录约 ${hoursLeft.toInt()} 小时"
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri ->
        val sourcePath = exportPath
        if (uri == null || sourcePath == null || operationBusy) return@rememberLauncherForActivityResult
        operationBusy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { com.gongfpp.sonfolio.processing.AudioFileAccess.mutex.withLock {
                val source = File(sourcePath)
                require(source.exists()) { "原始录音文件不存在" }
                FileInputStream(source).use { input ->
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        input.copyTo(output)
                    } ?: error("无法打开导出目标")
                }
            } }; operationMessage = "原始录音已导出" }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { operationMessage = "导出失败，目标可能是不完整文件，请重新导出" }
            finally { operationBusy = false }
        }
    }
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ids = selection
        if (operationBusy) return@rememberLauncherForActivityResult
        operationBusy = true
        scope.launch {
            try {
                onExportSelected(ids, uri)
                operationMessage = "原音与文字已导出；这不是完整的数据恢复备份"
                selection = emptySet()
                selectionMode = false
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { operationMessage = "导出失败，目标可能是不完整文件：${error.message}" }
            finally { operationBusy = false }
        }
    }

    cleanupIds?.let { ids ->
        AlertDialog(onDismissRequest = { if (!operationBusy) cleanupIds = null },
            title = { Text("清理 ${ids.size} 份原音？") },
            text = { Text("此操作不可撤销，请先导出需要保留的文件。仅清理已识别完成的原音，保留转写、总结和标记；被标记的整场对话及未处理文件会跳过。") },
            confirmButton = { TextButton(enabled = !operationBusy, onClick = {
                if (operationBusy) return@TextButton
                operationBusy = true
                selectedId = null
                scope.launch {
                    try { operationMessage = onDeleteSelected(ids); selection = emptySet(); selectionMode = false }
                    catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) { operationMessage = "清理未全部完成，请检查列表后重试：${error.message}" }
                    finally { operationBusy = false; cleanupIds = null }
                }
            }) { Text("确认清理原音") } },
            dismissButton = { TextButton(enabled = !operationBusy, onClick = { cleanupIds = null }) { Text("取消") } })
    }

    uploadRequest?.let { (id, config) ->
        AlertDialog(onDismissRequest = { uploadRequest = null }, title = { Text("上传这份录音的人声片段？") },
            text = { Text("将发送到 ${config.provider.label} 的 ${config.model}，可能产生流量和调用费用。这只授权当前一份原音，不上传其他历史录音；原音在本机保留。") },
            confirmButton = { TextButton(onClick = {
                runCatching { app.transcriptionSettings.authorizeChunk(id, config.revision) }
                    .onSuccess { onRetry(id); operationMessage = "已授权这份原音，等待转写" }
                    .onFailure { operationMessage = it.message ?: "授权失败，请重新确认" }
                uploadRequest = null
            }) { Text("确认上传并处理") } },
            dismissButton = { TextButton(onClick = { uploadRequest = null }) { Text("取消") } })
    }

    val selected = displayedChunks.firstOrNull { it.id == selectedId && it.endedAtMillis != null }
    if (selected != null) {
        Scaffold(containerColor = Paper, bottomBar = {
            TimelineAudioPlayer(PlaybackTimeline(listOf(PlaybackSlice(selected.localPath, selected.startedAtMillis, selected.startedAtMillis, selected.savedEndMillis()))))
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) {
                DetailTopBar("原音回听", formatDateTime(selected.startedAtMillis), ::leavePage)
                Text("${formatBytes(selected.byteSize)} · ${File(selected.localPath).name}", color = InkSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
                Text("拖动底部进度条跳转；暂停后可以从当前位置继续。", color = InkSoft, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
        return
    }
    Scaffold(containerColor = Paper) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
            item(key = "header") {
                Column {
                    DetailTopBar("原始录音", "${if (showAll) "全部日期" else date} · ${totalCount} 段原音", ::leavePage)
                    Text(
                        "点击卡片回听，长按进入多选。清理后原音从此列表移除，已有转写和总结仍保留。",
                        modifier = Modifier.padding(start = 46.dp, top = 4.dp),
                        color = InkSoft,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                    )
                    Text(
                        "$remainingLabel（${formatBytes(availableBytes)} 可用）",
                        modifier = Modifier.padding(start = 46.dp, top = 3.dp),
                        color = InkSoft,
                        fontSize = 11.5.sp,
                    )
                    if (date != null) TextButton(enabled = !operationBusy, onClick = { selection = emptySet(); selectionMode = false; showAll = !showAll }) { Text(if (showAll) "仅看 $date" else "查看全部录音") }
                    Text("本地目录：${File(context.filesDir, "recordings").absolutePath}", color = InkSoft, fontSize = 10.sp)
                    Text("系统文件管理器通常不能直接访问应用私有目录；请使用下方导出功能。", color = InkSoft, fontSize = 11.sp)
                    operationMessage?.let { Text(it, color = InkSoft, fontSize = 12.sp) }
                    if (operationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row {
                        TextButton(enabled = !operationBusy, onClick = {
                            prepareCleanup(false)
                        }) { Text("清理已过滤原音") }
                        TextButton(enabled = !operationBusy, onClick = {
                            prepareCleanup(true)
                        }) { Text("清理无人声原音") }
                    }
                }
            }
            if (selectionMode) {
                item(key = "selection-bar") {
                    val totalBytes = selection.sumOf { id -> displayedChunks.firstOrNull { it.id == id }?.byteSize ?: 0L }
                    Surface(Modifier.fillMaxWidth().padding(top = 10.dp), RoundedCornerShape(13.dp), color = Green) {
                        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("已选 ${selection.size} 段 · ${formatBytes(totalBytes)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(enabled = !operationBusy && selection.isNotEmpty(), onClick = { cleanupIds = selection }) { Text("清理原音", color = Color.White) }
                            TextButton(enabled = !operationBusy && selection.isNotEmpty(), onClick = { zipLauncher.launch("sonfolio-export-${date ?: "all"}.zip") }) { Text("导出文件", color = Color.White) }
                        }
                    }
                    Row {
                        if (allIds.isNotEmpty()) TextButton(enabled = !operationBusy, onClick = { selection = if (allSelected) emptySet() else allIds.toSet() }) { Text(if (allSelected) "取消全选" else "全选本页") }
                        TextButton(enabled = !operationBusy, onClick = { selection = emptySet(); selectionMode = false }) { Text("退出多选") }
                    }
                }
            }
            if (displayedChunks.isEmpty()) {
                item(key = "empty") {
                    Text("${if (showAll) "还没有" else "这一天没有"}原始录音。开始记录后，录音切片会立即出现在这里。", modifier = Modifier.padding(top = 24.dp), color = InkSoft, fontSize = 13.sp)
                }
            } else {
                items(displayedChunks.take(visibleCount), key = { it.id }) { chunk ->
                    RawRecordingRow(
                        chunk = chunk,
                        selectionMode = selectionMode,
                        enabled = !operationBusy,
                        checked = selection.contains(chunk.id),
                        onToggleSelect = {
                            selection = if (selection.contains(chunk.id)) selection - chunk.id else selection + chunk.id
                        },
                        onLongClick = { selectionMode = true; selection = selection + chunk.id },
                        onPlay = {
                            if (selectionMode) selection = if (chunk.id in selection) selection - chunk.id else selection + chunk.id
                            else if (chunk.endedAtMillis != null && File(chunk.localPath).exists()) selectedId = chunk.id
                            else operationMessage = "正在保存录音，切片完成后可回听"
                        },
                        onRetry = {
                            val config = app.transcriptionSettings.read()
                            if (config.mode == com.gongfpp.sonfolio.processing.TranscriptionMode.REMOTE &&
                                chunk.processingState !in listOf("ASSEMBLY_PENDING", "ASSEMBLY_FAILED") &&
                                !app.transcriptionSettings.isAuthorized(config, chunk.id, chunk.startedAtMillis)) uploadRequest = chunk.id to config
                            else onRetry(chunk.id)
                        },
                        onExport = {
                            exportPath = chunk.localPath
                            exportLauncher.launch(File(chunk.localPath).name)
                        },
                    )
                }
                if (displayedChunks.size > visibleCount) item(key = "more") {
                    TextButton(onClick = { visibleCount += 30 }) { Text("查看更多原音（已显示 $visibleCount / $totalCount）") }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RawRecordingRow(
    chunk: AudioChunkPreview,
    selectionMode: Boolean,
    enabled: Boolean,
    checked: Boolean,
    onToggleSelect: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onExport: () -> Unit,
) {
    val file = remember(chunk.localPath) { File(chunk.localPath) }
    val stateLabel = when (chunk.processingState) {
        "RECORDING" -> "正在录音"
        "RECORDED", "RECOVERED" -> "1/4 原音已保存 · 等待人声检测"
        "VAD_RUNNING" -> "2/4 正在检测人声"
        "VAD_READY" -> "2/4 人声检测完成 · 等待转写"
        "ASR_RUNNING" -> "3/4 正在转写文字"
        "ASSEMBLY_PENDING", "ASSEMBLY_FAILED" -> "3/4 文字已保存 · 等待整理对话"
        "AUDIO_DELETED" -> "原音已清理 · 已有文字保留"
        "ASR_READY" -> when {
            chunk.speechCount == 0 -> "处理结束 · 未检测到人声，不生成对话"
            chunk.transcriptCount == 0 -> "处理结束 · 检测到人声但未识别出文字，可回听原音"
            chunk.visibleTranscriptCount == 0 -> "处理结束 · 低于过滤阈值，对话已隐藏，原音保留"
            else -> "4/4 对话与基础小结已完成 · AI 总结可在对话详情生成"
        }
        else -> "原音已保留"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("raw-audio-row").combinedClickable(enabled = enabled, onClick = onPlay, onLongClick = onLongClick, onLongClickLabel = "选择原音"),
        shape = RoundedCornerShape(13.dp),
        color = if (checked) PaleGreen else Color(0xFFFFFEFA),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) Checkbox(checked = checked, onCheckedChange = { onToggleSelect() }, enabled = enabled)
            Icon(Icons.Default.Description, contentDescription = null, tint = Green, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(formatDateTime(chunk.startedAtMillis), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(stateLabel, color = InkSoft, fontSize = 12.sp)
                Text("${formatBytes(chunk.byteSize)} · ${file.name}", color = InkSoft, fontSize = 11.sp)
                chunk.errorMessage?.let { Text(it, color = InkSoft, fontSize = 11.sp) }
                if (!selectionMode) Row {
                    if (chunk.processingState.endsWith("FAILED") || chunk.processingState in setOf("RECORDED", "RECOVERED", "VAD_READY", "ASSEMBLY_PENDING")) TextButton(enabled = enabled, onClick = onRetry) { Text("继续处理") }
                    TextButton(onClick = onExport, enabled = enabled && chunk.endedAtMillis != null && file.exists()) { Text("导出") }
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
