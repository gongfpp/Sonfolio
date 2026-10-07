package com.gongfpp.sonfolio

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gongfpp.sonfolio.data.local.ConversationSummaryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Composable
internal fun RealConversationScreen(
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
    // 普通进入先看小结；从搜索命中进入时直接展示并定位对应转写。
    var transcriptOpen by rememberSaveable(conversationId) { mutableStateOf(initialTranscriptId != null) }
    var seekLineId by remember(conversationId) { mutableStateOf<String?>(null) }
    var selectedLineId by rememberSaveable(conversationId, initialTranscriptId) { mutableStateOf(initialTranscriptId) }
    var playLineId by remember(conversationId) { mutableStateOf<String?>(null) }
    var playNonce by rememberSaveable(conversationId) { mutableLongStateOf(0L) }
    var titleDraft by remember(conversationId) { mutableStateOf<String?>(null) }
    var markerDialog by remember(conversationId) { mutableStateOf(false) }
    var editingLine by remember(conversationId) { mutableStateOf<TranscriptLine?>(null) }
    var editLineText by remember(conversationId) { mutableStateOf("") }
    var vocabularyPrompt by remember(conversationId) { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()
    var initialLocated by rememberSaveable(conversationId, initialTranscriptId) { mutableStateOf(false) }
    LaunchedEffect(initialTranscriptId, lines) {
        if (!initialLocated && initialTranscriptId != null) {
            val index = lines.indexOfFirst { initialTranscriptId in it.mergedIds }
            if (index >= 0) { listState.scrollToItem(index + 1); seekLineId = initialTranscriptId; initialLocated = true }
        }
    }
    LaunchedEffect(seekLineId, lines) {
        val idx = lines.indexOfFirst { seekLineId in it.mergedIds }
        if (idx >= 0) listState.scrollToItem(idx + 1)
    }
    val chunkStart = lines.minOfOrNull { it.startedAtMillis } ?: 0L
    val chunkEnd = lines.maxOfOrNull { it.endedAtMillis } ?: 0L
    val chunks by remember(chunkStart, chunkEnd) { viewModel.observeChunks(chunkStart, chunkEnd) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val title = conversation?.title ?: "未识别"
    val meta = conversation?.let { formatConversationMeta(it) } ?: "正在整理原始对话"
    val summary = conversation?.summary ?: "正在从本地转写中生成本段小结。"
    val origin = structuredSummary?.modelVersion?.let { version ->
        when {
            version.startsWith("REMOTE:") -> "在线 AI · ${version.removePrefix("REMOTE:").substringBefore(" @").trim()}"
            version.startsWith("LOCAL:") -> "本地 AI · ${version.removePrefix("LOCAL:").substringBefore(" [").trim()}"
            else -> "本地提取式小结"
        }
    } ?: "本地提取式小结"
    val exportContext = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val exportTextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val payload = buildConversationText(title, meta, summary, structuredSummary, lines)
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
                .testTag("conversation-transcript-list")
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        ) {
            item(key = "summary") {
                Column {
                    DetailTopBar(
                        title = title,
                        meta = meta,
                        onBack = onBack,
                        actions = buildList {
                            add("改标题" to { titleDraft = conversation?.title ?: "" })
                            add("标记" to { markerDialog = true })
                            add("重新纠错" to {
                                val app = exportContext.applicationContext as SonfolioApplication
                                if (app.summarySettings.read().mode == com.gongfpp.sonfolio.summary.SummaryMode.BASIC) {
                                    Toast.makeText(exportContext, "请先在设置里选择「在手机上总结」或「在线总结」作为纠错模型", Toast.LENGTH_LONG).show()
                                } else {
                                    exportScope.launch {
                                        val result = runCatching { withContext(Dispatchers.IO) { app.summaryCoordinator.enqueueCorrection("conversation:$conversationId") } }
                                        Toast.makeText(
                                            exportContext,
                                            if (result.isSuccess) "已整场加入纠错队列，完成后显示在转写里" else (result.exceptionOrNull()?.message ?: "无法加入纠错队列"),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            })
                            if (conversation?.isMarked == true) add("取消标记" to { viewModel.removeConversationMarker(conversationId) })
                            add("导出文本" to { exportTextLauncher.launch("sonfolio-对话文本.txt") })
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    val marked = lines.filter { it.isMarked }
                    if (marked.isNotEmpty()) {
                        Surface(Modifier.fillMaxWidth().padding(bottom = 10.dp), RoundedCornerShape(10.dp), color = AmberPale) {
                            Text(
                                "★ 标记 ${formatReadableDuration(marked.maxOf { it.endedAtMillis } - marked.minOf { it.startedAtMillis })} · 覆盖整段连续对话",
                                modifier = Modifier.padding(10.dp), color = Color(0xFF694E00), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    androidx.compose.material3.TabRow(selectedTabIndex = if (transcriptOpen) 1 else 0, containerColor = Paper) {
                        androidx.compose.material3.Tab(selected = !transcriptOpen, onClick = { transcriptOpen = false }, text = { Text("对话小结") })
                        androidx.compose.material3.Tab(selected = transcriptOpen, onClick = { transcriptOpen = true }, text = { Text("结构化转写") })
                    }
                    Spacer(Modifier.height(16.dp))
                    if (!transcriptOpen) {
                        SummaryCard("conversation:$conversationId", title, summary, origin, structuredSummary?.generatedAtMillis?.takeIf { origin != "本地提取式小结" })
                    } else {
                        Text("${lines.size} 段转写 · 点击文字定位录音", color = InkSoft, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
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
                        val located = selectedLineId in line.mergedIds
                        // Amber is a fixed light highlight; its foreground must not inherit dark-theme ink.
                        val amberBackground = line.isMarked && !located
                        val lineInk = if (amberBackground) Color(0xFF443716) else Ink
                        val lineSecondary = if (amberBackground) Color(0xFF69562C) else InkSoft
                        Surface(
                            modifier = Modifier.fillMaxWidth().testTag("transcript-${line.id}")
                                .semantics { selected = located }
                                .clickable { selectedLineId = line.id; seekLineId = line.id },
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                located -> PaleGreenStrong
                                line.isMarked -> AmberPale
                                else -> Color.Transparent
                            },
                        ) {
                            Column(Modifier.padding(vertical = 6.dp, horizontal = 5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            val target = selectedLineId?.takeIf { it in line.mergedIds } ?: line.id
                                            selectedLineId = target; seekLineId = target; playLineId = target; playNonce++
                                        },
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "播放这一句", tint = if (amberBackground) Color(0xFF226548) else Green, modifier = Modifier.size(18.dp))
                                    }
                                    Text(
                                        if (line.isMarked) "★ ${formatClock(line.startedAtMillis)}" else formatClock(line.startedAtMillis),
                                        modifier = Modifier.width(58.dp),
                                        color = if (amberBackground) Color(0xFF695000) else InkSoft,
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        remember(line.text, searchQuery) { highlightText(line.text, searchQuery) },
                                        modifier = Modifier.weight(1f),
                                        color = lineInk,
                                        fontSize = 15.sp,
                                        lineHeight = 18.sp,
                                    )
                                    IconButton(
                                        onClick = { editingLine = line; editLineText = line.text },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "修正这一句", tint = lineSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                                if (line.originalText != null) {
                                    Text(
                                        "已修正 · 原始版本：${line.originalText}",
                                        modifier = Modifier.padding(start = 58.dp, top = 2.dp),
                                        color = lineSecondary,
                                        fontSize = 10.5.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item(key = "playback-hint") {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("点击转写行可定位；行首按钮播放这一句，末尾铅笔可修正文字（保留原始版本）", color = InkSoft, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    HelpHint(
                        title = "修正转写会怎样",
                        body = "**修正会保留原始识别版本**（可随时对照或撤销）。\n\n" +
                            "修正后的文字会用于**之后生成**的本段小结与 AI 总结；**已经生成的 AI 小结会被标记为「需要重新生成」**，不会自动重做，点「重新生成 AI 总结」即可用修正后的文字重算。",
                    )
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
        if (markerDialog) {
            val point = lines.firstOrNull { selectedLineId in it.mergedIds }?.endedAtMillis
                ?: conversation?.endedAtMillis ?: System.currentTimeMillis()
            AlertDialog(
                onDismissRequest = { markerDialog = false },
                title = { Text("标记") },
                text = { Column {
                    Text("从 ${formatClock(point)}（${if (selectedLineId != null) "选中句末" else "对话结尾"}）向前标记为重要。", fontSize = 12.sp, color = InkSoft, modifier = Modifier.padding(bottom = 6.dp))
                    listOf(30 to "30 秒", 60 to "1 分", 180 to "3 分", 300 to "5 分", 600 to "10 分", 1200 to "20 分", 1800 to "30 分").forEach { (seconds, label) ->
                        TextButton(onClick = {
                            viewModel.addBackwardMarker(point, seconds * 1_000L)
                            markerDialog = false
                        }) { Text("标记 向前 $label") }
                    }
                } },
                confirmButton = { TextButton(onClick = { markerDialog = false }) { Text("取消") } },
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
                    if (line.originalText != null) TextButton(onClick = {
                        exportScope.launch {
                            val app = exportContext.applicationContext as SonfolioApplication
                            val result = runCatching { app.conversationRepository.restoreTranscriptLine(line.id) }
                            if (result.isSuccess) editingLine = null
                            Toast.makeText(exportContext, if (result.isSuccess) "这一句已恢复为原始识别文字" else "恢复失败，请重试", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("恢复这一句原文") }
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


internal fun formatConversationMeta(item: ConversationPreview): String {
    val end = if (localDateAt(item.startedAtMillis) == localDateAt(item.endedAtMillis)) formatClock(item.endedAtMillis)
        else formatDateTime(item.endedAtMillis)
    return "${formatDateTime(item.startedAtMillis)}–$end · ${item.duration}"
}


@Composable
internal fun SummaryCard(
    sourceKey: String,
    theme: String,
    summary: String,
    origin: String = "本地基础整理",
    updatedAtMillis: Long? = null,
) {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    val config by app.summarySettings.config.collectAsStateWithLifecycle()
    val run by remember(sourceKey) { app.summaryCoordinator.observe(sourceKey) }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var error by remember(sourceKey) { mutableStateOf<String?>(null) }
    val pending = run?.state in listOf("QUEUED", "RUNNING")
    Surface(shape = RoundedCornerShape(15.dp), color = PaleGreen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, contentDescription = null, tint = Green, modifier = Modifier.size(19.dp))
                Text("本段小结", modifier = Modifier.padding(start = 9.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (config.mode == com.gongfpp.sonfolio.summary.SummaryMode.BASIC) {
                    Surface(shape = CircleShape, color = CardSurface) {
                        Text("⌁  $origin", modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Green, fontSize = 11.sp)
                    }
                } else {
                    TextButton(
                        enabled = !pending,
                        onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { app.summaryCoordinator.enqueue(sourceKey) }
                                    error = null
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { error = e.message ?: "无法加入总结队列" }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(if (run?.outputJson == null) "生成 AI 总结" else "重新生成 AI 总结", fontSize = 12.sp)
                    }
                }
            }
            if (pending) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "正在整理「$theme」…可离开此页，后台会继续；长时间无变化可取消后重试",
                        modifier = Modifier.weight(1f), color = InkSoft, fontSize = 11.sp,
                    )
                    TextButton(onClick = {
                        scope.launch { runCatching { withContext(Dispatchers.IO) { app.summaryCoordinator.cancel(sourceKey) } } }
                    }) { Text("取消", fontSize = 11.sp) }
                }
            } else {
                val extracted = origin == "本地提取式小结"
                val time = updatedAtMillis?.let { " · ${formatDateTime(it)}" }.orEmpty()
                Text(
                    if (extracted) "来源：$origin" else "来源：$origin$time · 请结合原文核对",
                    modifier = Modifier.padding(top = 8.dp),
                    color = if (extracted) InkSoft else Green,
                    fontSize = 11.sp,
                )
            }
            run?.message?.takeIf { it.isNotBlank() && !pending && run?.state != "READY" }?.let {
                Text(it, modifier = Modifier.padding(top = 6.dp), color = InkSoft, fontSize = 11.sp)
            }
            Text(summary, modifier = Modifier.padding(top = 12.dp), color = Ink, fontSize = 16.sp, lineHeight = 27.sp)
            error?.let { Text(it, modifier = Modifier.padding(top = 6.dp), color = Color(0xFFB23B2E), fontSize = 11.sp) }
        }
    }
}



internal fun parseJsonLines(value: String?): String = runCatching {
    val array = JSONArray(value ?: "[]")
    (0 until array.length()).map { array.getString(it) }.joinToString("\n") { "• $it" }
}.getOrDefault("")

internal fun parseJsonArray(value: String?): List<String> = runCatching {
    val array = JSONArray(value ?: "[]")
    (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
}.getOrDefault(emptyList())

/** 把一段对话（小结、结构化要点、逐句转写）整理成可导出的纯文本。 */
internal fun buildConversationText(
    title: String,
    meta: String,
    summary: String,
    structured: ConversationSummaryEntity?,
    lines: List<TranscriptLine>,
): String = buildString {
    appendLine("$title（$meta）")
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
