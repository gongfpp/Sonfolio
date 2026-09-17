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
import androidx.compose.foundation.border
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
import androidx.compose.material3.ButtonDefaults
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
import com.gongfpp.sonfolio.processing.ChunkProcessing
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
    val timelineEntries = remember(date, day) { day.timelineEntries() }
    var visibleEntries by rememberSaveable(date.toString()) { mutableIntStateOf(TIMELINE_PAGE_SIZE) }
    val recordedDates = remember(calendar) { calendar.filter { !it.organized }.flatMap { datesInRange(it.start, it.end) }.toSet() }
    val organizedDates = remember(calendar) { calendar.filter { it.organized }.flatMap { datesInRange(it.start, it.end) }.toSet() }
    var calendarOpen by remember { mutableStateOf(false) }
    val selectDate: (LocalDate) -> Unit = { next ->
        selectedDate = next.takeUnless { it == today }?.toString()
        scope.launch { listState.scrollToItem(0) }
    }
    if (calendarOpen) {
        RecordingCalendarDialog(date, today, recordedDates, organizedDates, { calendarOpen = false }) { next ->
            calendarOpen = false
            selectDate(next)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("timeline-list"), state = listState,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Text("声迹", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                if (recordingStatus.isRecording) "正在记录 · 原音保存在本机" else "你的记录保存在本机 · 按日期回看",
                color = InkSoft, fontSize = 14.sp,
            )
        }
        item(key = "journal") {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(AppScreen.Daily(date.toString())) },
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
        item(key = "date") {
            DateNavigator(date, today, recordedDates, organizedDates, onOpenCalendar = { calendarOpen = true }, onSelect = selectDate)
        }
        item(key = "stats") { TodayStats(day) }
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
        item(key = "heat") {
            RecentHeatPanel(
                recorded = recordedDates,
                organized = organizedDates,
                date = date,
                day = day,
                onOpenCalendar = { calendarOpen = true },
                onOpenRaw = { onOpen(AppScreen.RawRecordings(date.toString())) },
            )
        }
        item(key = "timeline-title") { SectionTitle("对话时间线") }
        if (timelineEntries.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (day.chunks.isEmpty()) "这一天还没有录音，可以选择其他日期回看。" else "原音已保存，这一天暂无可显示的对话。未识别或已过滤的内容仍可在原始录音中回听。",
                    color = InkSoft, fontSize = 13.sp,
                )
            }
        }
        // 对话与进行中录音单元混合倒序：新的在上面；跨日提示随卡片展示，不依赖顺序。
        items(timelineEntries.take(visibleEntries), key = { entry ->
            when (entry) {
                is TimelineEntry.Conversation -> "conversation:${entry.preview.id}"
                is TimelineEntry.Pending -> "pending:${entry.unit.id}"
            }
        }) { entry ->
            when (entry) {
                is TimelineEntry.Conversation -> {
                    val conversation = entry.preview
                    Column {
                        TimelineCard(conversation) { onOpen(AppScreen.Conversation(id = conversation.id)) }
                        if (conversation.startedAtMillis < window.start || conversation.endedAtMillis > window.end) {
                            Text("跨日对话 · 打开后可查看及回听完整内容", Modifier.padding(start = 22.dp, top = 3.dp), color = InkSoft, fontSize = 11.sp)
                        }
                    }
                }
                is TimelineEntry.Pending -> PendingUnitCard(entry.unit) { onOpen(AppScreen.RawRecordings(date.toString())) }
            }
        }
        if (timelineEntries.size > visibleEntries) {
            item(key = "more-timeline") {
                TextButton(onClick = { visibleEntries += TIMELINE_PAGE_SIZE }) {
                    Text("展示更多（已显示 ${minOf(visibleEntries, timelineEntries.size)} / ${timelineEntries.size}）")
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
private fun DateNavigator(
    date: LocalDate,
    today: LocalDate = rememberCurrentDay(),
    recorded: Set<LocalDate> = emptySet(),
    organized: Set<LocalDate> = emptySet(),
    onOpenCalendar: () -> Unit = {},
    onSelect: (LocalDate) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSelect(date.minusDays(1)) }) { Icon(Icons.Default.ChevronLeft, "前一天") }
        TextButton(onClick = onOpenCalendar, modifier = Modifier.weight(1f)) {
            Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { onSelect(date.plusDays(1)) }, enabled = date < today) { Icon(Icons.Default.ChevronRight, "后一天") }
        if (date != today) TextButton(onClick = { onSelect(today) }) { Text("本日", fontSize = 12.sp) }
    }
}

@Composable
private fun TodayStats(day: DayTimeline) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(formatStatDuration(day.savedMillis), null, "当日已录", Modifier.weight(1f))
        StatCard(day.conversations.size.toString(), null, "场对话", Modifier.weight(1f))
        StatCard(day.totalChunks.toString(), null, "当日原音", Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(value: String, unit: String?, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFFEFA),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                if (unit != null) {
                    Text(unit, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp), color = InkSoft, fontSize = 11.sp)
                }
            }
            Text(label, color = InkSoft, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RecentHeatPanel(
    recorded: Set<LocalDate>,
    organized: Set<LocalDate>,
    date: LocalDate,
    day: DayTimeline,
    onOpenCalendar: () -> Unit,
    onOpenRaw: () -> Unit,
) {
    val days = remember(date) { (13 downTo 0).map { date.minusDays(it.toLong()) } }
    var gapsOpen by rememberSaveable(date.toString()) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFFEFA),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最近两周", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenCalendar) { Text("日历 ›", color = Green, fontSize = 12.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                days.forEach { day ->
                    val color = when {
                        day in organized -> Green
                        day in recorded -> PaleGreenStrong
                        else -> Color(0xFFEDEDE7)
                    }
                    Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(color))
                }
            }
            Text(
                "浅绿 = 有原音 · 深绿 = 已整理 · 近两周 ${days.count { it in recorded || it in organized }} 天有记录",
                modifier = Modifier.padding(top = 6.dp), color = InkSoft, fontSize = 10.5.sp,
            )
            if (day.totalChunks > 0) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("整理进度", color = InkSoft, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("${day.processedChunks}/${day.totalChunks}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            if (day.gaps.isNotEmpty()) {
                TextButton(onClick = { gapsOpen = !gapsOpen }, modifier = Modifier.padding(top = 2.dp)) {
                    Text("当日 ${day.gaps.size} 次中断 · 缺口 ${formatPlaybackTime(day.gapMillis)} · ${if (gapsOpen) "收起" else "查看"}", color = Color(0xFF805900), fontSize = 12.sp)
                }
                if (gapsOpen) day.gaps.forEach { gap ->
                    Text("${formatDateTime(gap.startedAtMillis)} — ${gap.endedAtMillis?.let(::formatDateTime) ?: "等待恢复"}\n${gap.reason}", Modifier.padding(vertical = 4.dp), color = InkSoft, fontSize = 11.sp)
                }
            }
            TextButton(onClick = onOpenRaw, modifier = Modifier.padding(top = 2.dp)) { Text("查看当日原始录音 ›", fontSize = 12.sp) }
        }
    }
}

/** 1–4 阶段状态灯：已完成=绿，进行中=琥珀，失败=红，未到=灰。 */
@Composable
private fun StageIndicator(progress: ChunkProcessing.StageProgress, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (stage in 1..ChunkProcessing.TOTAL_STAGES) {
            val color = when {
                progress.failed && stage == progress.active -> Color(0xFFC0392B)
                stage <= progress.completed -> Green
                stage == progress.active -> Amber
                else -> Color(0xFFD8D8D0)
            }
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        }
    }
}

/** 还没整理完成、按同一场对话临时聚合的录音单元；标题未知，只展示时间范围与当前阶段。 */
@Composable
private fun PendingUnitCard(unit: PendingUnit, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = Color(0xFFFFFCF0),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8DFC2)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Amber))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "录音处理中",
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                        fontSize = 14.sp,
                        color = Color(0xFF725B18),
                        modifier = Modifier.weight(1f),
                    )
                    StageIndicator(unit.progress)
                }
                Text(
                    "${formatDateTime(unit.startedAtMillis)} · ${unit.chunkIds.size} 段原音",
                    color = InkSoft, fontSize = 11.5.sp,
                )
                Text(unit.label, color = Color(0xFF725B18), fontSize = 12.sp)
                unit.errorMessage?.let { Text(it, color = InkSoft, fontSize = 11.sp) }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "查看原始录音", tint = Amber)
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
    var lastMarked by remember(status.startedAtMillis) { mutableStateOf<Pair<Long, Int>?>(null) }
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
    val enabled = status.isRecording
    val onWhite = Color.White

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Green,
    ) {
        Column {
            Row(
                Modifier.padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            status.isRecording -> if (status.health.clientSilenced == true) "输入被静音 · $elapsed"
                                else if (status.health.lastBufferAtMillis == null) "正在启动 · $elapsed" else "正在记录 · $elapsed"
                            status.interruptionPending -> "恢复记录"
                            else -> "开始记录"
                        },
                        color = onWhite, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    )
                    Text(
                        if (status.isRecording) "每 5 分钟保存一段原音 · 全程在本机" else "点击后持续在后台录音",
                        color = onWhite.copy(alpha = .85f), fontSize = 12.sp,
                    )
                }
                if (status.isRecording) {
                    InputWaveform(
                        levels = status.health.levels,
                        accent = if (status.health.clientSilenced == true) Color(0xFFFFE9B8) else onWhite,
                        modifier = Modifier.width(38.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Box(
                    Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(onWhite)
                        .border(6.dp, PaleGreen, CircleShape)
                        .clickable { if (status.isRecording) onStop() else onStart() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (status.isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (status.isRecording) "停止记录" else "开始记录",
                        tint = Green,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp)
                    .background(onWhite.copy(alpha = .28f)),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("标记刚才\n重要的事", color = onWhite.copy(alpha = .85f), fontSize = 12.sp, lineHeight = 16.sp)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .alpha(if (enabled) 1f else .4f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(AmberPale)
                            .clickable(enabled = enabled) {
                                lastMarked = System.currentTimeMillis() to markWindows.first()
                                onMark(markWindows.first())
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("★", color = Amber, fontSize = 15.sp)
                        Text("标记（${markWindows.first()}分）", color = Color(0xFF694E00), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    markWindows.drop(1).forEach { minutes ->
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(if (enabled) AmberPale else Color(0xFFE8E8E3))
                                .clickable(enabled = enabled) {
                                    lastMarked = System.currentTimeMillis() to minutes
                                    onMark(minutes)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${minutes}分", color = Color(0xFF694E00), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            lastMarked?.let { (markedAt, minutes) ->
                Text(
                    "★ 已标记 ${formatClock(markedAt)} 往前 ${minutes} 分钟 · 涉及的对话会高亮保留",
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                    color = Color(0xFFFFE9B8), fontSize = 11.sp,
                )
            }
            TextButton(onClick = { sliceHelp = true }, modifier = Modifier.padding(start = 6.dp)) {
                Text("切片说明 ⓘ", color = onWhite.copy(alpha = .9f), fontSize = 11.sp)
            }
            if (sliceHelp) AlertDialog(onDismissRequest = { sliceHelp = false },
                title = { Text("文件切片不等于对话切割") },
                text = { Text("5 分钟切片是为了边录边处理，并减少异常退出时未收尾的范围。相邻语音间隔不超过 2 分钟、且没有已知录音缺口时，会合并为同一场对话，能够跨越多个文件。\n\n总结使用整场对话的已识别文字；长内容分段时会携带上一部分的总结。后续转写到达后会更新基础小结，旧 AI 结果会标为需要重新生成。\n\n当前按时间连续性合并，不是语义主题识别：同一主题停顿过久仍可能被分开，总结也需结合原文核对。") },
                confirmButton = { TextButton(onClick = { sliceHelp = false }) { Text("知道了") } })
            if (status.isRecording || status.interruptionPending || status.health.failure != null) {
                val failure = status.health.failure
                // 进程重启后 health 会回到默认值（failure 为空），但缺口仍开着；此时也必须能主动结束。
                val canEndInterrupted = !status.isRecording && status.interruptionPending
                Text(
                    if (canEndInterrupted && failure == null) "录音已中断，缺口将计至重新采集到音频。" else status.health.message(nowMillis),
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = if (failure != null || canEndInterrupted) 6.dp else 12.dp),
                    color = onWhite.copy(alpha = .85f), fontSize = 11.sp,
                )
                if (failure != null || canEndInterrupted) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (failure != null) TextButton(
                            onClick = onRecover,
                            colors = ButtonDefaults.textButtonColors(contentColor = onWhite),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("尝试恢复", fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = onEndInterrupted,
                            colors = ButtonDefaults.textButtonColors(contentColor = onWhite),
                        ) {
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

/** 对话时间线默认展示条数，每次「展示更多」再追加同样多。 */
private const val TIMELINE_PAGE_SIZE = 8

/** 统计格用的紧凑时长：超过 1 小时显示 h:mm，否则显示分钟数。 */
private fun formatStatDuration(durationMillis: Long): String {
    val safe = durationMillis.coerceAtLeast(0L)
    val totalMinutes = safe / 60_000L
    val hours = totalMinutes / 60
    return when {
        hours > 0 -> "$hours:${(totalMinutes % 60).toString().padStart(2, '0')}"
        totalMinutes > 0 -> "${totalMinutes}分"
        safe > 0L -> "不足1分"
        else -> "0分"
    }
}

/** 时间线徽标用的紧凑时长。 */
private fun formatCompactDuration(durationMillis: Long): String {
    val minutes = durationMillis.coerceAtLeast(0L) / 60_000L
    return if (minutes == 0L) "不足1分" else "${minutes}分"
}

/**
 * 把 query 里的每个关键词在 text 中的命中片段用强调样式标出。
 * 搜索按空白拆词做 AND 匹配，高亮必须用同一套拆词规则，否则多词查询永远不亮。
 */
private fun highlightText(text: String, query: String?): AnnotatedString {
    val terms = SearchTerms.split(query.orEmpty())
    if (terms.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase()
    val intervals = mutableListOf<IntRange>()
    terms.forEach { term ->
        val needle = term.lowercase()
        if (needle.isEmpty()) return@forEach
        var cursor = 0
        var guard = 0
        while (guard++ < 200) {
            val at = lower.indexOf(needle, cursor)
            if (at < 0) break
            intervals += at until (at + needle.length)
            cursor = at + needle.length
        }
    }
    if (intervals.isEmpty()) return AnnotatedString(text)
    // 相邻/重叠的命中合并，避免同一段文字叠加多层背景。
    val merged = mutableListOf<IntRange>()
    intervals.sortedBy { it.first }.forEach { range ->
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        else merged += range
    }
    val style = SpanStyle(background = AmberPale, color = Color(0xFF694E00), fontWeight = FontWeight.Bold)
    return AnnotatedString(text, spanStyles = merged.map { AnnotatedString.Range(style, it.first, it.last + 1) })
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
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFFEFA),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Column(
                Modifier.width(48.dp).clip(RoundedCornerShape(12.dp)).background(PaleGreen).padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(formatClock(item.startedAtMillis), color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(formatCompactDuration(item.endedAtMillis - item.startedAtMillis), color = InkSoft, fontSize = 9.sp)
            }
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StageIndicator(ChunkProcessing.progressOf(ChunkProcessing.ASR_READY), Modifier.padding(start = 6.dp))
                }
                if (item.summary.isNotBlank()) {
                    Text(item.summary, modifier = Modifier.padding(top = 3.dp), color = InkSoft, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (item.isMarked) {
                    Text(
                        "★ 标记",
                        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(999.dp)).background(AmberPale).padding(horizontal = 9.dp, vertical = 3.dp),
                        color = Color(0xFF694E00), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    )
                }
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
    var vocabularyPrompt by remember(conversationId) { mutableStateOf<List<String>>(emptyList()) }
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
                                        remember(line.text, searchQuery) { highlightText(line.text, searchQuery) },
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
                text = { Column {
                    OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("标题（最多 30 字）") })
                    if (conversation?.titleOverride != null) {
                        Text("已手工命名；后台整理不会覆盖你写的标题。", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                        TextButton(onClick = {
                            viewModel.resetConversationTitle(conversationId)
                            titleDraft = null
                        }) { Text("恢复自动标题", fontSize = 12.sp) }
                    }
                } },
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
                confirmButton = { TextButton(onClick = {
                    viewModel.updateTranscriptText(line.id, editLineText) { candidates -> vocabularyPrompt = candidates }
                    editingLine = null
                }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { editingLine = null }) { Text("取消") } },
            )
        }
        if (vocabularyPrompt.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { vocabularyPrompt = emptyList() },
                title = { Text("加入个人词汇？") },
                text = { Column {
                    Text("这些词在你多次修正后仍反复出现，确认后会作为本地 Qwen3-ASR 的识别提示：", fontSize = 12.sp)
                    Text(vocabularyPrompt.joinToString("、"), modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold)
                    Text("只影响本地识别，不会上传；可在设置 → 个人词汇中管理。", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                } },
                confirmButton = { TextButton(onClick = {
                    vocabularyPrompt.forEach { viewModel.acceptVocabulary(it) }
                    vocabularyPrompt = emptyList()
                }) { Text("加入") } },
                dismissButton = { Row {
                    TextButton(onClick = {
                        vocabularyPrompt.forEach { viewModel.ignoreVocabulary(it) }
                        vocabularyPrompt = emptyList()
                    }) { Text("忽略") }
                    TextButton(onClick = { vocabularyPrompt = emptyList() }) { Text("稍后") }
                } },
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
    val timelineState = remember { mutableStateOf(PlaybackTimeline(emptyList())) }
    LaunchedEffect(lines, chunks, gaps) {
        timelineState.value = withContext(Dispatchers.Default) { PlaybackTimeline.forConversation(lines, chunks, gaps) }
    }
    val timeline = timelineState.value
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
    // 输入防抖：逐字查询会在每个字符都打一次库，长列表/大库时明显卡顿。
    var settledQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query.isBlank()) settledQuery = query else { delay(220); settledQuery = query }
    }
    var dateRange by rememberSaveable { mutableStateOf(SearchDateRange.All) }
    var markedOnly by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun resetScroll() { scope.launch { listState.scrollToItem(0) } }
    val today = rememberCurrentDay()
    var visibleLimit by rememberSaveable(settledQuery, dateRange, markedOnly, today.toString()) { mutableIntStateOf(SEARCH_BATCH_SIZE) }
    val results by key(settledQuery, dateRange, markedOnly, today) {
        remember(settledQuery, dateRange, markedOnly, today, visibleLimit) {
            viewModel.observeSearch(settledQuery, dateRange, markedOnly, visibleLimit)
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
            settledQuery, dateRange, markedOnly, results, visibleLimit, listState,
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
        Text(
            when {
                results?.errorMessage != null -> results.errorMessage
                results == null -> "正在搜索…"
                query.isBlank() -> "最新 ${results.hits.size} 场对话${if (results.hasMore) " · 还有更多" else ""}"
                results.hasMore -> "已显示 ${results.hits.size} 场相关对话 · 还有更多"
                else -> "找到 ${results.hits.size} 场相关对话"
            }, color = InkSoft, fontSize = 13.sp,
        )
        val hits = results?.hits
        if (results?.errorMessage != null) return@Column
        if (hits != null && hits.isEmpty()) {
            Text(
                if (query.isBlank()) "此条件下没有已整理的内容。" else "没有找到包含“$query”的对话。",
                modifier = Modifier.padding(top = 20.dp),
                color = InkSoft,
                fontSize = 13.sp,
            )
        } else if (hits != null) {
            LazyColumn(Modifier.weight(1f).testTag("search-results"), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                items(hits, key = { "hit:${it.conversationId}" }) { hit ->
                    SearchResult(
                        date = formatDateTime(hit.snippetStartedAtMillis).substringBefore(' '),
                        title = if (hit.isMarked) "★ ${hit.title}" else hit.title,
                        excerpt = hit.snippetText,
                        trailing = formatClock(hit.snippetStartedAtMillis),
                        meta = when {
                            query.isBlank() -> null
                            hit.titleHit -> "标题命中"
                            else -> "命中 ${hit.hitCount} 句"
                        },
                        query = query,
                    ) {
                        onOpen(AppScreen.Conversation(id = hit.conversationId, transcriptId = hit.snippetTranscriptId, query = query))
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
private fun SearchResult(date: String, title: String, excerpt: String, trailing: String, meta: String? = null, query: String? = null, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onClick), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, color = InkSoft, fontSize = 13.sp)
                Text(title, modifier = Modifier.padding(start = 8.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(trailing, color = InkSoft, fontSize = 11.sp)
            }
            if (meta != null) {
                Text(
                    meta,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(999.dp)).background(PaleGreen).padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Green, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                )
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
    val availableState = remember { mutableStateOf(0L) }
    LaunchedEffect(used) {
        availableState.value = withContext(Dispatchers.IO) { android.os.StatFs(context.filesDir.path).availableBytes }
    }
    val available = availableState.value
    val bytesPerDay = 16_000L * 2L * 86_400L
    var language by remember { mutableStateOf(preferences.preferredLanguage) }
    var minimumSpeechSeconds by remember { mutableStateOf(preferences.minimumSpeechSeconds.toFloat()) }
    var minimumTextCharacters by remember { mutableStateOf(preferences.minimumTextCharacters.toFloat()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text("录音与存储", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("声迹 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", color = InkSoft, fontSize = 12.sp)
        SectionTitle("识别与总结")
        com.gongfpp.sonfolio.processing.TranscriptionSettingsCard()
        com.gongfpp.sonfolio.PersonalVocabularyCard()
        com.gongfpp.sonfolio.summary.SummarySettingsCard()
        SectionTitle("录音")
        Surface(Modifier.fillMaxWidth().padding(top = 17.dp), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("录音服务", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (recordingStatus.isRecording) "正在记录" else "已停止", color = Green, fontSize = 13.sp)
            }
        }
        MarkerWindowSettings(preferences)
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("短录音过滤", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    HelpHint(
                        title = "短录音过滤怎么算",
                        body = "有效人声和文字同时低于阈值才隐藏；原音不删除，标记片段豁免。文字设为 0 可关闭过滤。\n\n隐藏只影响首页时间线显示，仍可在原始录音中回听。",
                    )
                }
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
                        catch (error: Exception) { "设置已保存，但队列更新失败（${error.message ?: error.javaClass.simpleName}）；重新打开应用会重试" }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("默认本地识别，不上传音频。", color = InkSoft, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    HelpHint(
                        title = "仅充电时自动处理",
                        body = "包括人声检测、转写和自动 AI 总结。关闭后，已等待的任务也可在未充电时继续；开启不主动打断当前一轮，正在运行的旧任务若遇系统限制，下一轮按新设置执行。手动 AI 总结不要求充电，录音不受影响。\n\n只有单独启用外部转文字并确认后才上传人声片段；外部总结仅发送转写文字。",
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                policyMessage?.let { Text(it, color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 13.dp)) }
            }
        }
        SectionTitle("存储与原音")
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("原音保留", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    HelpHint(
                        title = "原音保留策略",
                        body = "转写完成后自动压缩原音，超过保留期自动删除未压缩文件；文字和标记附近的录音不受影响。选择「永久保留」则始终保留原声。\n\n超过保留期的段：整理完成的会自动压缩并清理，未完成的等转写完成后自动处理。",
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 7, 30, 90).forEach { days ->
                        FilterChip(selected = retentionDays == days, onClick = { retentionDays = days; preferences.setRetentionDays(days) },
                            label = { Text(if (days == 0) "永久保留" else "${days}天", fontSize = 11.sp) })
                    }
                }
                val expiryTime = remember(retentionDays) { if (retentionDays > 0) System.currentTimeMillis() - retentionDays * 86_400_000L else Long.MIN_VALUE }
                val expired by remember(expiryTime) { app.database.recordingDao().observeExpiredCount(expiryTime) }.collectAsStateWithLifecycle(initialValue = 0)
                if (expired > 0) {
                    Text("${expired} 段原音超过保留期，将按保留策略自动处理。", color = InkSoft, fontSize = 11.sp)
                }
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
            catch (error: Exception) { operationMessage = "读取清理清单失败（${error.message ?: error.javaClass.simpleName}），未删除任何文件" }
            finally { operationBusy = false }
        }
    }
    val allIds = displayedChunks.take(visibleCount).filter { it.processingState != "AUDIO_DELETED" }.map { it.id }
    val allSelected = allIds.isNotEmpty() && selection.containsAll(allIds)
    val availableBytesState = remember { mutableStateOf(0L) }
    LaunchedEffect(chunks) {
        availableBytesState.value = withContext(Dispatchers.IO) { android.os.StatFs(context.filesDir.path).availableBytes }
    }
    val availableBytes = availableBytesState.value
    val bytesPerDay = 16_000L * 2L * 86_400L
    val hoursLeft = (availableBytes.toDouble() / bytesPerDay) * 24.0
    val remainingLabel = if (hoursLeft >= 48) "预计还能录约 ${(hoursLeft / 24).toInt()} 天" else "预计还能录约 ${hoursLeft.toInt()} 小时"
    fun saveExport(uri: android.net.Uri?) {
        val sourcePath = exportPath
        if (uri == null || sourcePath == null || operationBusy) return
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
            catch (error: Exception) { operationMessage = "导出失败（${error.message ?: error.javaClass.simpleName}），目标可能是不完整文件，请重新导出" }
            finally { operationBusy = false }
        }
    }
    // 压缩后退伍的录音是 m4a；导出按实际文件类型选择 MIME，避免第三方播放器不识别。
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { saveExport(it) }
    val exportM4aLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { saveExport(it) }
    fun launchExport(path: String) {
        exportPath = path
        val name = File(path).name
        if (name.endsWith(".m4a", ignoreCase = true)) exportM4aLauncher.launch(name) else exportLauncher.launch(name)
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
                Text("${formatBytes(selected.displayBytes)} · ${File(selected.localPath).name}", color = InkSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
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
                    val totalBytes = selection.sumOf { id -> displayedChunks.firstOrNull { it.id == id }?.displayBytes ?: 0L }
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
                        onExport = { launchExport(chunk.localPath) },
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
    val progress = ChunkProcessing.progressOf(chunk.processingState)
    val stateLabel = if (chunk.processingState == ChunkProcessing.ASR_READY) when {
        chunk.speechCount == 0 -> "处理结束 · 未检测到人声，不生成对话"
        chunk.transcriptCount == 0 -> "处理结束 · 检测到人声但未识别出文字，可回听原音"
        chunk.visibleTranscriptCount == 0 -> "处理结束 · 低于过滤阈值，对话已隐藏，原音保留"
        else -> "4/4 对话与基础小结已完成 · AI 总结可在对话详情生成"
    } else {
        "${(progress.active ?: progress.completed).coerceIn(1, ChunkProcessing.TOTAL_STAGES)}/4 ${ChunkProcessing.labelOf(chunk.processingState)}"
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
                Text("${formatBytes(chunk.displayBytes)}${if (chunk.audioCompressed) " · 已压缩" else ""} · ${file.name}", color = InkSoft, fontSize = 11.sp)
                chunk.errorMessage?.let { Text(it, color = InkSoft, fontSize = 11.sp) }
                if (!selectionMode) Row {
                    if (ChunkProcessing.isActionable(chunk.processingState)) TextButton(enabled = enabled, onClick = onRetry) { Text("继续处理") }
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
