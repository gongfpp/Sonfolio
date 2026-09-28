package com.gongfpp.sonfolio

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gongfpp.sonfolio.processing.ChunkProcessing
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gongfpp.sonfolio.recording.RecordingStatus

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(
    preferences: SonfolioPreferences,
    recordingStatus: RecordingStatus,
    onOpenRawRecordings: () -> Unit,
    onRebuildConversations: () -> Unit,
) {
    var chargeOnly by remember { mutableStateOf(preferences.chargeOnly) }
    val settingsScope = rememberCoroutineScope()
    var policyMessage by remember { mutableStateOf<String?>(null) }
    var retentionDays by remember { mutableStateOf(preferences.retentionDays) }
    var retentionCustom by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val app = context.applicationContext as SonfolioApplication
    val activeTranscription by app.transcriptionSettings.config.collectAsStateWithLifecycle()
    val activeSummary by app.summarySettings.config.collectAsStateWithLifecycle()
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var editorDirty by remember { mutableStateOf(false) }
    var editorBusy by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = section != null && editor == null) { section = null }
    fun closeEditor() {
        if (!editorBusy) { if (editorDirty) confirmDiscard = true else editor = null }
    }
    editor?.let { page ->
        androidx.compose.ui.window.Dialog(onDismissRequest = ::closeEditor,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !editorBusy, onClick = ::closeEditor) { Text("返回设置") }
                        Text(if (editorDirty) "未保存更改" else "当前配置已保存", color = InkSoft, fontSize = 12.sp)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp)) {
                        if (page == "transcription") com.gongfpp.sonfolio.processing.TranscriptionSettingsCard({ editorDirty = it }, { editorBusy = it })
                        else com.gongfpp.sonfolio.summary.SummarySettingsCard({ editorDirty = it }, { editorBusy = it })
                    }
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃尚未保存的更改？") },
        text = { Text("已经生效的配置不会改变。下载完成的模型文件也会保留。") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; editorDirty = false; editor = null }) { Text("放弃更改") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
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
    key(section) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp)) {
        if (section != null) DetailTopBar(section.orEmpty(), "", { section = null })
        if (section == null) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("声迹 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", color = InkSoft, fontSize = 12.sp)
        SectionTitle("识别与总结")
        SettingsModeEntry("转文字方式", "已生效：${activeTranscription.mode.label} · ${if (activeTranscription.mode == com.gongfpp.sonfolio.processing.TranscriptionMode.REMOTE) activeTranscription.provider.label else activeTranscription.localEngine.displayName}") {
            editorDirty = false; editorBusy = false; editor = "transcription"
        }
        SettingsModeEntry("总结方式", "已生效：${activeSummary.mode.label}${if (activeSummary.mode == com.gongfpp.sonfolio.summary.SummaryMode.REMOTE) " · ${com.gongfpp.sonfolio.summary.SummaryProvider.fromEndpoint(activeSummary.endpoint).label}" else ""}") {
            editorDirty = false; editorBusy = false; editor = "summary"
        }
        SettingsModeEntry("处理与整理", if (chargeOnly) "自动处理等待充电 · 可手动重试" else "自动处理不限制充电状态") { section = "处理与整理" }
        SectionTitle("录音与数据")
        SettingsModeEntry("原始录音", "点击回听，长按管理") { onOpenRawRecordings() }
        SettingsModeEntry("录音设置", "标记时长、识别语言、短录音过滤") { section = "录音设置" }
        SettingsModeEntry("存储与备份", "空间占用、保留策略、完整备份") { section = "存储与备份" }
        SettingsModeEntry("用量记录", "查看在线调用与用量") { section = "用量记录" }
        Text("外观跟随系统 · 原音保存在本机", color = InkSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
        }
        if (section == "用量记录") UsageCard()
        if (section == "处理与整理") {
        OrganizeSettingsCard(preferences, onRebuildConversations)
        SettingsModeEntry("查看处理进度", "查看每段录音的处理阶段，或重试未完成的任务") { onOpenRawRecordings() }
        }
        if (section == "录音设置") {
        Surface(Modifier.fillMaxWidth().padding(top = 17.dp), RoundedCornerShape(14.dp), color = CardSurface, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("录音服务", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (recordingStatus.isRecording) "正在记录" else "已停止", color = Green, fontSize = 13.sp)
            }
        }
        MarkerWindowSettings(preferences)
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = CardSurface, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
                Text("识别语言", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("新录音将按所选语言识别，已有转写保持原样", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(top = 10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2,
                ) {
                    listOf("zh" to "中文优先", "en" to "英文优先", "zh_en" to "中英文优先", "auto" to "自动识别").forEach { (value, label) ->
                        FilterChip(
                            selected = language == value,
                            onClick = {
                                language = value
                                preferences.setPreferredLanguage(value)
                            },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }
                Text("只影响 SenseVoice 的语言提示；Qwen3-ASR 与 FireRedASR2 会自动判断语言。", color = InkSoft, fontSize = 10.5.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = CardSurface, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("短录音过滤", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    HelpHint(
                        title = "短录音过滤怎么算",
                        body = "**有效人声和文字同时低于阈值才隐藏**；**录音不删除**，标记片段豁免。文字设为 0 可关闭过滤。\n\n**隐藏只影响首页时间线显示**，仍可在原始录音中回听。",
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
        }
        if (section == "处理与整理") {
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = CardSurface, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
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
                    Text("仅影响后台处理，不影响录音。", color = InkSoft, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    HelpHint(
                        title = "仅充电时自动处理",
                        body = "包括人声检测、转写和自动 AI 总结。**关闭后，已等待的任务也可在未充电时继续**；开启不主动打断当前一轮，正在运行的旧任务若遇系统限制，下一轮按新设置执行。**手动 AI 总结不要求充电，录音不受影响**。\n\n**只有单独启用外部转文字并确认后才上传人声片段**；外部总结仅发送转写文字。",
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                policyMessage?.let { Text(it, color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 13.dp)) }
            }
        }
        }
        if (section == "存储与备份") {
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = CardSurface, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(13.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("存储占用", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    HelpHint(
                        title = "存储估算怎么来的",
                        body = "「预计还可记录」按 **16 kHz 单声道 WAV** 的固定码率估算：**约 ${formatModelSize(bytesPerDay)}/天**（16000 × 2 字节 × 86400 秒）。\n\n" +
                            "它**不是写死的**：可用空间取自你当前这台手机，所以每台不同。录音在整理完成后会**自动压缩成更小的 AAC**，实际占用通常低于原始 WAV。\n\n" +
                            "模型和系统也占用存储，所以实际可录时间可能更短。",
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StorageValue("录音已使用", String.format(Locale.US, "%.2f", used / 1_073_741_824.0), "GB", Modifier.weight(1f))
                    StorageValue("预计还可记录", (available / bytesPerDay).toString(), "天", Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(10.dp).clip(CircleShape).background(Color(0xFFE3E3DF))) {
                    Box(Modifier.fillMaxWidth((used.toFloat() / (used + available).coerceAtLeast(1)).coerceIn(0f, 1f)).fillMaxSize().clip(CircleShape).background(ActionFill))
                }
                Text("连续录音约${formatModelSize(bytesPerDay)}/天，剩余${formatModelSize(available)}；模型和系统也占用存储。", color = InkSoft, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = CardSurface, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(13.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("录音保留", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    HelpHint(
                        title = "录音保留策略",
                        body = "转写完成后自动压缩录音，**超过保留期自动删除未压缩文件**；**文字和标记附近的录音不受影响**。选择「永久保留」则始终保留录音。\n\n超过保留期的段：整理完成的会自动压缩并清理，未完成的等转写完成后自动处理。",
                    )
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SonfolioPreferences.RETENTION_OPTIONS.forEach { days ->
                        FilterChip(selected = retentionDays == days, onClick = { retentionDays = days; preferences.setRetentionDays(days) },
                            label = { Text(if (days == 0) "永久保留" else "${days}天", fontSize = 11.sp) })
                    }
                    FilterChip(
                        selected = retentionDays !in SonfolioPreferences.RETENTION_OPTIONS,
                        onClick = { retentionCustom = true },
                        label = { Text(if (retentionDays in SonfolioPreferences.RETENTION_OPTIONS) "自定义" else "${retentionDays}天", fontSize = 11.sp) },
                    )
                }
                if (retentionCustom) {
                    var draft by remember { mutableStateOf(if (retentionDays in SonfolioPreferences.RETENTION_OPTIONS) "" else retentionDays.toString()) }
                    AlertDialog(
                        onDismissRequest = { retentionCustom = false },
                        title = { Text("自定义保留时间") },
                        text = { Column {
                            OutlinedTextField(draft, { draft = it.filter(Char::isDigit).take(3) },
                                label = { Text("保留天数（1–${SonfolioPreferences.MAX_RETENTION_DAYS}）") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Text("「永久保留」表示不自动删除录音。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        } },
                        confirmButton = { TextButton(onClick = {
                            val days = draft.toIntOrNull()
                            if (days != null && days in 1..SonfolioPreferences.MAX_RETENTION_DAYS) {
                                retentionDays = days; preferences.setRetentionDays(days); retentionCustom = false
                            }
                        }) { Text("保存") } },
                        dismissButton = { TextButton(onClick = { retentionCustom = false }) { Text("取消") } },
                    )
                }
                val expiryTime = remember(retentionDays) { if (retentionDays > 0) System.currentTimeMillis() - retentionDays * 86_400_000L else Long.MIN_VALUE }
                val expired by remember(expiryTime) { app.database.recordingDao().observeExpiredCount(expiryTime) }.collectAsStateWithLifecycle(initialValue = 0)
                if (expired > 0) {
                    Text("${expired} 段录音超过保留期，将按保留策略自动处理。", color = InkSoft, fontSize = 11.sp)
                }
            }
        }
        Surface(
            Modifier.fillMaxWidth().padding(top = 13.dp).clickable(onClick = onOpenRawRecordings),
            RoundedCornerShape(14.dp),
            color = CardSurface,
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
        }
    }
    }
}

@Composable
internal fun RawRecordingsScreen(
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
    // 提前算出可清理数量：按钮上直接显示，避免「点了没反应」的困惑。
    var speechCandidates by remember(window) { mutableIntStateOf(-1) }
    var filteredCandidates by remember(window) { mutableIntStateOf(-1) }
    LaunchedEffect(window.start, window.end, chunks) {
        speechCandidates = withContext(Dispatchers.IO) { dao.getCleanupCandidates(window.start, window.end, true).size }
        filteredCandidates = withContext(Dispatchers.IO) { dao.getCleanupCandidates(window.start, window.end, false).size }
    }
    fun prepareCleanup(silence: Boolean) {
        if (operationBusy || cleanupIds != null) return
        operationBusy = true
        scope.launch {
            try {
                val ids = dao.getCleanupCandidates(window.start, window.end, silence).toSet()
                if (ids.isEmpty()) {
                    operationMessage = "没有可清理的录音：只能清理已整理完成、未被标记保护、且无人声或已过滤的录音。"
                } else cleanupIds = ids
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
                val source = requireNotNull(com.gongfpp.sonfolio.recording.AudioResource.managedFile(File(context.filesDir, "recordings"), sourcePath)) { "录音文件不存在" }
                require(source.isFile) { "原始录音文件不存在" }
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
                operationMessage = "录音与文字已导出；这不是完整的数据恢复备份"
                selection = emptySet()
                selectionMode = false
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { operationMessage = "导出失败，目标可能是不完整文件：${error.message}" }
            finally { operationBusy = false }
        }
    }

    cleanupIds?.let { ids ->
        AlertDialog(onDismissRequest = { if (!operationBusy) cleanupIds = null },
            title = { Text("清理 ${ids.size} 份录音？") },
            text = { Text("此操作不可撤销，请先导出需要保留的文件。仅清理已识别完成的录音，保留转写、总结和标记；被标记的整场对话及未处理文件会跳过。") },
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
            }) { Text("确认清理录音") } },
            dismissButton = { TextButton(enabled = !operationBusy, onClick = { cleanupIds = null }) { Text("取消") } })
    }

    uploadRequest?.let { (id, config) ->
        AlertDialog(onDismissRequest = { uploadRequest = null }, title = { Text("上传这份录音的人声片段？") },
            text = { Text("将发送到 ${config.provider.label} 的 ${config.model}，可能产生流量和调用费用。这只授权当前一份录音，不上传其他历史录音；录音在本机保留。") },
            confirmButton = { TextButton(onClick = {
                runCatching { app.transcriptionSettings.authorizeChunk(id, config.revision) }
                    .onSuccess { onRetry(id); operationMessage = "已授权这份录音，等待转写" }
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
                DetailTopBar("录音回听", formatDateTime(selected.startedAtMillis), ::leavePage)
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
                    DetailTopBar("原始录音", "${if (showAll) "全部日期" else date} · ${totalCount} 段录音", ::leavePage)
                    Text(
                        "点击卡片回听，长按进入多选。清理后录音从此列表移除，已有转写和总结仍保留。",
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
                        TextButton(enabled = !operationBusy && speechCandidates != 0, onClick = {
                            prepareCleanup(true)
                        }) { Text(if (speechCandidates > 0) "清理无人声录音（$speechCandidates）" else "清理无人声录音") }
                        TextButton(enabled = !operationBusy && filteredCandidates != 0, onClick = {
                            prepareCleanup(false)
                        }) { Text(if (filteredCandidates > 0) "清理已过滤录音（$filteredCandidates）" else "清理已过滤录音") }
                    }
                    if (speechCandidates == 0 && filteredCandidates == 0) {
                        Text("没有可自动清理的录音：只能清理已整理完成、未被标记保护、且无人声或已过滤的录音。", color = InkSoft, fontSize = 11.sp)
                    }
                }
            }
            if (!selectionMode && displayedChunks.isNotEmpty()) {
                item(key = "select-entry") {
                    TextButton(enabled = !operationBusy, onClick = { selectionMode = true }) { Text("选择录音") }
                }
            }
            if (selectionMode) {
                item(key = "selection-bar") {
                    val totalBytes = selection.sumOf { id -> displayedChunks.firstOrNull { it.id == id }?.displayBytes ?: 0L }
                    Surface(Modifier.fillMaxWidth().padding(top = 10.dp), RoundedCornerShape(13.dp), color = ActionFill) {
                        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("已选 ${selection.size} 段 · ${formatBytes(totalBytes)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(enabled = !operationBusy && selection.isNotEmpty(), onClick = { cleanupIds = selection }) { Text("清理录音", color = Color.White) }
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
                    TextButton(onClick = { visibleCount += 30 }) { Text("查看更多录音（已显示 $visibleCount / $totalCount）") }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun RawRecordingRow(
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
        chunk.transcriptCount == 0 -> "处理结束 · 检测到人声但未识别出文字，可回听录音"
        chunk.visibleTranscriptCount == 0 -> "处理结束 · 低于过滤阈值，对话已隐藏，录音保留"
        else -> "${ChunkProcessing.TOTAL_STAGES}/${ChunkProcessing.TOTAL_STAGES} 对话、纠错与基础小结已完成 · AI 总结可在对话详情生成"
    } else {
        "${(progress.active ?: progress.completed).coerceIn(1, ChunkProcessing.TOTAL_STAGES)}/${ChunkProcessing.TOTAL_STAGES} ${ChunkProcessing.labelOf(chunk.processingState)}"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("raw-audio-row").combinedClickable(enabled = enabled, onClick = onPlay, onLongClick = onLongClick, onLongClickLabel = "选择录音"),
        shape = RoundedCornerShape(13.dp),
        color = if (checked) PaleGreen else CardSurface,
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
internal fun StorageValue(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = InkSoft, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, modifier = Modifier.padding(top = 5.dp), fontSize = 23.sp)
            Text(unit, modifier = Modifier.padding(start = 3.dp, bottom = 3.dp), fontSize = 11.sp)
        }
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
