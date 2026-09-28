package com.gongfpp.sonfolio

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DailyScreen(viewModel: SonfolioViewModel, initialDate: String, onBack: () -> Unit, onOpenConversation: (String) -> Unit) {
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val window = remember(localDate) { DayWindow.of(LocalDate.parse(localDate)) }
    val conversations by remember(window) { viewModel.observeTimeline(window.start, window.end) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var calendarOpen by remember { mutableStateOf(false) }
    val today = rememberCurrentDay()
    if (calendarOpen) RecordingCalendarDialog(LocalDate.parse(localDate), today, recordedDates, organizedDates, { calendarOpen = false }) {
        localDate = it.toString(); calendarOpen = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        DetailTopBar("一日回顾", localDate, onBack)
        DateNavigator(LocalDate.parse(localDate), recorded = recordedDates, organized = organizedDates, onOpenCalendar = { calendarOpen = true }) { localDate = it.toString() }
        Surface(Modifier.fillMaxWidth().padding(top = 15.dp), RoundedCornerShape(15.dp), color = PaleGreen) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = Green, modifier = Modifier.size(19.dp))
                    Text("这一天发生了什么", modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text(
                    narrative ?: "这一天暂时还没有足够的已整理内容。完成录音和本地转写后，这里会生成一日回顾。",
                    modifier = Modifier.padding(top = 13.dp),
                    color = Ink,
                    fontSize = 16.sp,
                    lineHeight = 28.sp,
                )
            }
        }
        AuxiliaryCard("值得记住", parseJsonLines(journal?.memorableJson).ifBlank { "这一天还没有标记重点对话" }, Icons.Default.Star, Amber)
        Text("基于 $sourceCount 场对话整理 · 原始录音仍按你的保留策略保存", modifier = Modifier.padding(top = 20.dp), color = InkSoft, fontSize = 11.sp)
        if (conversations.isNotEmpty()) {
            SectionTitle("当日对话")
            Text("打开对话可核对原文。以下是当日时间线，不代表每场对话都已用于这份回顾。", color = InkSoft, fontSize = 12.sp)
            conversations.forEach { conversation ->
                SettingsModeEntry(conversation.title, "${formatClock(conversation.startedAtMillis)} · ${conversation.duration}") { onOpenConversation(conversation.id) }
            }
        }
        com.gongfpp.sonfolio.summary.SummaryAction("day:$localDate")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = viewModel::rebuildConversations) { Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("重新整理") }
            TextButton(onClick = {
                val app = context.applicationContext as SonfolioApplication
                if (app.summarySettings.read().mode == com.gongfpp.sonfolio.summary.SummaryMode.BASIC) {
                    Toast.makeText(context, "请先在设置里选择「在手机上总结」或「在线总结」作为纠错模型", Toast.LENGTH_LONG).show()
                } else {
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { app.summaryCoordinator.enqueueCorrection("day:$localDate") } }
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "已把本日加入纠错队列" else (result.exceptionOrNull()?.message ?: "无法加入纠错队列"),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }) { Text("重新纠错本日") }
            TextButton(onClick = {
                val app = context.applicationContext as SonfolioApplication
                if (app.summarySettings.read().mode == com.gongfpp.sonfolio.summary.SummaryMode.BASIC) {
                    Toast.makeText(context, "请先在设置里选择「在手机上总结」或「在线总结」", Toast.LENGTH_LONG).show()
                } else {
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { app.summaryCoordinator.enqueue("day:$localDate") } }
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "已把本日加入 AI 总结队列" else (result.exceptionOrNull()?.message ?: "无法加入总结队列"),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }) { Text("重新总结本日") }
        }
    }
}


@Composable
internal fun AuxiliaryCard(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Surface(Modifier.fillMaxWidth().padding(top = 10.dp), RoundedCornerShape(14.dp), color = PaleGreen) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                Text(title, modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(body, modifier = Modifier.padding(start = 28.dp, top = 8.dp), color = InkSoft, fontSize = 12.5.sp)
        }
    }
}
