package com.gongfpp.sonfolio

import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gongfpp.sonfolio.processing.ChunkProcessing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import com.gongfpp.sonfolio.recording.RecordingStatus
import kotlinx.coroutines.delay

@Composable
internal fun TodayScreen(
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
    val activeSummaryRuns by viewModel.observeActiveSummaryRunCount().collectAsStateWithLifecycle(initialValue = 0)
    val runningSummaryRuns by viewModel.observeRunningSummaryRunCount().collectAsStateWithLifecycle(initialValue = 0)
    var gapClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(gaps.any { it.endedAtMillis == null }) {
        while (gaps.any { it.endedAtMillis == null }) { gapClock = System.currentTimeMillis(); delay(1_000) }
    }
    val mergeGapMillis = (LocalContext.current.applicationContext as SonfolioApplication).preferences.conversationGapMinutes * 60_000L
    val day = remember(date, conversations, recordingChunks, gaps, gapClock, mergeGapMillis) {
        DayTimeline.build(date, conversations, recordingChunks, gaps, nowMillis = gapClock, mergeGapMillis = mergeGapMillis)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val timelineEntries = remember(date, day) { day.timelineEntries() }
    var visibleEntries by rememberSaveable(date.toString()) { mutableIntStateOf(TIMELINE_PAGE_SIZE) }
    val recordedDates = remember(calendar) { calendar.filter { !it.organized }.flatMap { datesInRange(it.start, it.end) }.toSet() }
    val organizedDates = remember(calendar) { calendar.filter { it.organized }.flatMap { datesInRange(it.start, it.end) }.toSet() }
    var calendarOpen by remember { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
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
        }
        item(key = "date") {
            DateNavigator(date, today, recordedDates, organizedDates, onOpenCalendar = { calendarOpen = true }, onSelect = selectDate)
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
        item(key = "journal") {
            SettingsModeEntry("一日回顾", "${formatStatDuration(day.savedMillis)} 已录 · ${day.conversations.size} 场对话 · 查看这一天的总结") {
                onOpen(AppScreen.Daily(date.toString()))
            }
        }
        if (detailsOpen) item(key = "heat") {
            RecentHeatPanel(
                recorded = recordedDates,
                organized = organizedDates,
                date = date,
                today = today,
                day = day,
                onSelect = selectDate,
                onOpenCalendar = { calendarOpen = true },
                onOpenRaw = { onOpen(AppScreen.RawRecordings(date.toString())) },
            )
        }
        item(key = "timeline-title") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("对话时间线")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onOpen(AppScreen.RawRecordings(date.toString())) }) { Text("原始录音（${day.totalChunks}）", fontSize = 12.sp) }
                IconButton(onClick = { detailsOpen = !detailsOpen }) {
                    Icon(Icons.Default.ExpandMore, contentDescription = if (detailsOpen) "收起统计与缺口" else "统计与缺口")
                }
                if (activeSummaryRuns > 0) {
                    Surface(shape = RoundedCornerShape(999.dp), color = AmberPale) {
                        Text(
                            "AI 整理中 $runningSummaryRuns/$activeSummaryRuns",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = Color(0xFF694E00), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        if (timelineEntries.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (day.chunks.isEmpty()) "这一天还没有录音，可以选择其他日期回看。" else "录音已保存，这一天暂无可显示的对话。未识别或已过滤的内容仍可在原始录音中回听。",
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
internal fun rememberCurrentDay(): LocalDate {
    var day by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { day = LocalDate.now(); delay(30_000L) } }
    return day
}


@Composable
internal fun DateNavigator(
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
internal fun TodayStats(day: DayTimeline) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(formatStatDuration(day.savedMillis), null, "当日已录", Modifier.weight(1f))
        StatCard(day.conversations.size.toString(), null, "场对话", Modifier.weight(1f))
        StatCard(day.totalChunks.toString(), null, "当日录音", Modifier.weight(1f))
    }
}


@Composable
internal fun StatCard(value: String, unit: String?, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = CardSurface,
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
internal fun RecentHeatPanel(
    recorded: Set<LocalDate>,
    organized: Set<LocalDate>,
    date: LocalDate,
    today: LocalDate,
    day: DayTimeline,
    onSelect: (LocalDate) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenRaw: () -> Unit,
) {
    val days = remember(date) { (13 downTo 0).map { date.minusDays(it.toLong()) } }
    var gapsOpen by rememberSaveable(date.toString()) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = CardSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最近两周", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenCalendar) { Text("日历 ›", color = Green, fontSize = 12.sp) }
            }
            // 周一到周日对齐的两周热力网格。首尾都补齐到整行：否则最后一行格子少时会被 weight
            // 拉伸，只有一格时会占满整行宽，看起来就是一个“巨大的格子”。
            val weeks = remember(days) { weekAlignedWeeks(days) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = InkSoft, fontSize = 10.sp)
                }
            }
            weeks.forEach { week ->
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    week.forEach { cell ->
                        Box(Modifier.weight(1f).height(30.dp).padding(horizontal = 1.dp), contentAlignment = Alignment.Center) {
                            if (cell != null) {
                                CalendarDay(
                                    day = cell,
                                    recorded = cell in recorded,
                                    organized = cell in organized,
                                    selected = cell == date,
                                    isToday = cell == today,
                                    onClick = { onSelect(cell) },
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "深绿 = 已整理 · 浅绿 = 有录音 · 琥珀框 = 选中 · 绿框 = 今天 · 近两周 ${days.count { it in recorded || it in organized }} 天有记录",
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
internal fun StageIndicator(progress: ChunkProcessing.StageProgress, modifier: Modifier = Modifier) {
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
internal fun PendingUnitCard(unit: PendingUnit, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = CardSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line),
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
                        color = Ink,
                        modifier = Modifier.weight(1f),
                    )
                    StageIndicator(unit.progress)
                }
                Text(
                    "${formatDateTime(unit.startedAtMillis)} · ${unit.chunkIds.size} 段录音",
                    color = InkSoft, fontSize = 12.sp,
                )
                Text(unit.label, color = Ink, fontSize = 12.sp)
                unit.errorMessage?.let { Text(it, color = InkSoft, fontSize = 11.sp) }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "查看原始录音", tint = Amber)
        }
    }
}


@Composable
internal fun RecordingCard(
    status: RecordingStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMark: (Int) -> Unit,
    onRecover: () -> Unit,
    onEndInterrupted: () -> Unit,
) {
    val markWindows = (LocalContext.current.applicationContext as SonfolioApplication).preferences.markerWindows
    val app = LocalContext.current.applicationContext as SonfolioApplication
    val transcription by app.transcriptionSettings.config.collectAsStateWithLifecycle()
    val summary by app.summarySettings.config.collectAsStateWithLifecycle()
    var sliceHelp by remember { mutableStateOf(false) }
    val markerState by com.gongfpp.sonfolio.recording.RecordingController.markerState.collectAsStateWithLifecycle()
    val markPending = markerState is com.gongfpp.sonfolio.recording.MarkerSaveState.Saving
    val markError = (markerState as? com.gongfpp.sonfolio.recording.MarkerSaveState.Failed)?.message
    val lastMarked = (markerState as? com.gongfpp.sonfolio.recording.MarkerSaveState.Saved)?.let { it.markedAtMillis to it.minutes }
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
    val enabled = status.isRecording && !markPending
    val onWhite = Color.White

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = ActionFill,
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
                    if (!status.isRecording) Text("点击后持续在后台录音", color = onWhite.copy(alpha = .85f), fontSize = 12.sp)
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
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(onWhite)
                        .border(6.dp, PaleGreen, CircleShape)
                        .clickable { if (status.isRecording) onStop() else onStart() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (status.isRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (status.isRecording) "停止记录" else "开始记录",
                        tint = ActionFill,
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .weight(1f).height(48.dp)
                            .alpha(if (enabled) 1f else .4f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(AmberPale)
                            .clickable(enabled = enabled) {
                                onMark(markWindows.first())
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text("★", color = Amber, fontSize = 15.sp)
                        Text("标记（${markWindows.first()}分）", color = Color(0xFF694E00), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    markWindows.drop(1).forEach { minutes ->
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (enabled) AmberPale else Color(0xFFE8E8E3))
                                .clickable(enabled = enabled) {
                                        onMark(minutes)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${minutes}分", color = Color(0xFF694E00), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (markPending || markError != null) Text(
                if (markPending) "正在保存标记…" else markError.orEmpty(),
                Modifier.padding(horizontal = 18.dp, vertical = 4.dp), color = Color(0xFFFFE9B8), fontSize = 11.sp,
            )
            lastMarked?.takeUnless { markPending || markError != null }?.let { (markedAt, minutes) ->
                Text(
                    "★ 已标记 ${formatClock(markedAt)} 往前 ${minutes} 分钟 · 涉及的对话会高亮保留",
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                    color = Color(0xFFFFE9B8), fontSize = 11.sp,
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${if (transcription.mode == com.gongfpp.sonfolio.processing.TranscriptionMode.REMOTE) "在线转文字 · 音频上传" else "本地转文字"} · ${if (summary.mode == com.gongfpp.sonfolio.summary.SummaryMode.REMOTE) "在线总结" else "本地总结"}",
                    Modifier.weight(1f), color = onWhite.copy(alpha = .85f), fontSize = 11.sp,
                )
                TextButton(onClick = { sliceHelp = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("切片说明 ⓘ", color = onWhite.copy(alpha = .9f), fontSize = 11.sp)
                }
                HelpHint(
                    title = "标记和没标记的区别",
                    body = "标记会把这次标记覆盖的**整段连续对话高亮**，并在搜索与一日回顾里作为「值得记住」的重点；**标记附近的录音不会被自动压缩或清理**。\n\n没有标记的对话只按时间和内容正常展示，不进入重点区。标记只影响展示与保留，**不修改转写文字**。",
                    tint = onWhite.copy(alpha = .9f),
                )
            }
            if (sliceHelp) AlertDialog(onDismissRequest = { sliceHelp = false },
                title = { Text("文件切片不等于对话切割") },
                text = { Text("5 分钟切片是为了边录边处理，并减少异常退出时未收尾的范围。相邻语音间隔不超过 ${app.preferences.conversationGapMinutes} 分钟、且没有已知录音缺口时，会合并为同一场对话，能够跨越多个文件。\n\n总结使用整场对话的已识别文字；长内容分段时会携带上一部分的总结。后续转写到达后会更新基础小结，旧 AI 结果会标为需要重新生成。\n\n当前按时间连续性合并，不是语义主题识别：同一主题停顿过久仍可能被分开，总结也需结合原文核对。") },
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


@Composable
internal fun InputWaveform(levels: List<Float>, accent: Color, modifier: Modifier = Modifier) {
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
internal fun TimelineCard(item: ConversationPreview, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = CardSurface,
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
                    Text(
                        (item.summarySource ?: "本地提取式整理") + if (item.segmentCount > 0) " · 共 ${item.segmentCount} 段" else "",
                        modifier = Modifier.padding(top = 4.dp),
                        color = if (item.summarySource != null) Green else InkSoft,
                        fontSize = 10.sp,
                    )
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


/** 迷你日历单日格子：状态用底色区分，选中加琥珀框、今天加绿框；点击可直接切换日期。 */
@Composable
private fun CalendarDay(
    day: LocalDate,
    recorded: Boolean,
    organized: Boolean,
    selected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        organized -> ActionFill
        recorded -> PaleGreenStrong
        else -> CardSurface
    }
    val foreground = when {
        organized -> Color.White
        recorded -> Green
        else -> InkSoft
    }
    val shape = RoundedCornerShape(8.dp)
    val base = Modifier.fillMaxSize().clip(shape).background(background).clickable(onClick = onClick)
    val styled = when {
        selected -> base.border(2.dp, Amber, shape)
        isToday -> base.border(1.5.dp, Green, shape)
        else -> base
    }
    Box(styled, contentAlignment = Alignment.Center) {
        Text(
            day.dayOfMonth.toString(),
            color = foreground,
            fontSize = 11.sp,
            fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 把连续的若干天按「周一到周日」对齐并补齐成整行，返回 7 列网格（空位为 null）。
 * 补齐是为了配合 Row + weight 的等宽布局：最后一行不足 7 格时，格子会被拉伸变宽。 */
internal fun weekAlignedWeeks(days: List<LocalDate>): List<List<LocalDate?>> {
    if (days.isEmpty()) return emptyList()
    val padded = ArrayList<LocalDate?>()
    repeat(days.first().dayOfWeek.value - 1) { padded.add(null) }
    padded.addAll(days)
    while (padded.size % 7 != 0) padded.add(null)
    return padded.chunked(7)
}
