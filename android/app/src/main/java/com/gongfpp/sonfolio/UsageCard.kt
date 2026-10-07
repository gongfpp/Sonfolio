package com.gongfpp.sonfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** 在线调用量：6 个月柱状趋势 + 当月明细。只统计本机调用，不记录音频或文字。 */
@Composable
internal fun UsageCard() {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    var refreshKey by remember { mutableIntStateOf(0) }
    val snapshot = remember(refreshKey) { app.usageStore.snapshot() }
    val history = remember(refreshKey) { app.usageStore.monthlyHistory(6) }
    val details = remember(refreshKey) { app.usageStore.details() }
    var grouping by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("功能") }
    DisposableEffect(app.usageStore) {
        val remove = app.usageStore.observeChanges { refreshKey++ }
        onDispose { remove() }
    }
    Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("在线用量", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                HelpHint(
                    title = "在线用量说明",
                    body = "只统计本机收到成功响应的在线识别、总结与纠错调用，**按月累计，不记录任何音频或文字内容**。失败或超时请求也可能被服务商计费，因此这里不是账单。\n\n" +
                        "识别按音频时长、总结/纠错按模型返回的 Token 数统计；**单价与账单以各服务商控制台为准，这里不做费用估算**。\n\n" +
                        "OpenCode Go 这类订阅制服务按模型分别计算额度，不按次计费。**历史录音不会自动上传**。",
                )
                TextButton(onClick = { refreshKey++ }) { Text("刷新", fontSize = 12.sp) }
            }
            Text("近 6 个月调用次数", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MonthlyUsageBars(history)
            Text("本月明细 · ${snapshot.month}", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("功能", "模型").forEach { option ->
                    FilterChip(selected = grouping == option, onClick = { grouping = option }, label = { Text("按$option") })
                }
            }
            if (snapshot.asr.isEmpty() && snapshot.llm.isEmpty()) {
                Text("本月还没有在线调用。", fontSize = 12.sp)
            } else {
                details.groupBy { if (grouping == "功能") it.feature else "${it.model} · ${it.provider}" }.forEach { (label, rows) ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("${rows.sumOf { it.calls }} 次调用", fontSize = 12.sp)
                    rows.forEach { row ->
                        val dimension = if (grouping == "功能") "${row.model} · ${row.provider}" else row.feature
                        val amount = if (row.kind == "asr") formatUsageDuration(row.seconds) else "${row.tokens} tokens"
                        Text("$dimension\n${row.calls} 次 · $amount", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("旧记录未保存的模型或功能无法补分。这里只统计在线用量，不包含本地推理。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 6 个月柱状图：柱高按当月调用次数相对最大值缩放，0 次显示为浅灰底线。 */
@Composable
private fun MonthlyUsageBars(history: List<UsageStore.MonthUsage>) {
    val max = history.maxOfOrNull { it.calls }?.coerceAtLeast(1L) ?: 1L
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        history.forEach { month ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (month.calls > 0) month.calls.toString() else "", fontSize = 10.sp, color = InkSoft)
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth()
                            .fillMaxHeight((month.calls.toFloat() / max).coerceIn(0.03f, 1f))
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (month.calls > 0) Green else Color(0xFFE0E0DA)),
                    )
                }
                Text("${month.month.substringAfter('-')}月", fontSize = 10.sp, color = InkSoft)
            }
        }
    }
}

private fun formatUsageDuration(seconds: Long): String =
    if (seconds < 60) "${seconds} 秒" else String.format(Locale.US, "%.1f 分钟", seconds / 60.0)
