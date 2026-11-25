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

/** 在线调用量：本月与累计的 token／估算费用 + 6 个月趋势 + 当月明细。只统计本机调用，不记录音频或文字。 */
@Composable
internal fun UsageCard() {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    var refreshKey by remember { mutableIntStateOf(0) }
    val snapshot = remember(refreshKey) { app.usageStore.snapshot() }
    val history = remember(refreshKey) { app.usageStore.monthlyHistory(6) }
    val lifetime = remember(refreshKey) { app.usageStore.lifetime() }
    val details = remember(refreshKey) { app.usageStore.details() }
    var grouping by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("功能") }
    var trend by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("费用") }
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
                        "识别按音频时长、总结/纠错按模型返回的 token 数统计，输入与输出分开计价。\n\n" +
                        "费用是按 ${UsagePricing.BASIS} 估算：DeepSeek 按调用时刻区分高峰（北京时间 9–12、14–18 点）与空闲价，通义按单次输入长度分档，豆包录音识别按 0.8 元/小时。**服务商调价后估算会偏低或偏高，实际以控制台账单为准。**\n\n" +
                        "订阅制服务（如 OpenCode Go）不按调用计费、自定义模型没有公开单价，这些都只算 token 不算金额，并标为未计价。升级前的老记录只有 token 总数、没有输入输出拆分，同样不计价。",
                )
                TextButton(onClick = { refreshKey++ }) { Text("刷新", fontSize = 12.sp) }
            }
            TotalsLine("本月", history.last())
            TotalsLine("累计", lifetime)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("近 6 个月", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                listOf("费用", "次数").forEach { option ->
                    FilterChip(selected = trend == option, onClick = { trend = option }, label = { Text("按$option") }, modifier = Modifier.padding(start = 6.dp))
                }
            }
            MonthlyUsageBars(history, byCost = trend == "费用")
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
                    Text(
                        listOfNotNull(
                            "${rows.sumOf { it.calls }} 次调用",
                            formatYuan(rows.sumOf { it.cost }).takeIf { rows.any { row -> row.cost > 0 } },
                            "${rows.sumOf { it.unpricedCalls }} 次未计价".takeIf { rows.any { row -> row.unpricedCalls > 0 } },
                        ).joinToString(" · "),
                        fontSize = 12.sp,
                    )
                    rows.forEach { row ->
                        val dimension = if (grouping == "功能") "${row.model} · ${row.provider}" else row.feature
                        val amount = if (row.kind == "asr") formatUsageDuration(row.seconds) else "${formatTokens(row.tokens)} tokens"
                        val cost = listOfNotNull(
                            formatYuan(row.cost).takeIf { row.cost > 0 },
                            "未计价".takeIf { row.unpricedCalls > 0 },
                        ).joinToString(" · ")
                        Text(
                            "$dimension\n${row.calls} 次 · $amount" + if (cost.isNotEmpty()) " · $cost" else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text("费用为按公开单价估算，不含失败请求与订阅制额度；本地推理不计费，所以不在这里统计。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 一行累计：次数、转写时长、token 与估算费用。 */
@Composable
private fun TotalsLine(label: String, usage: UsageStore.MonthUsage) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${formatTokens(usage.calls)} 次 · 转写 ${formatUsageDuration(usage.seconds)} · ${formatTokens(usage.tokens)} tokens",
                fontSize = 12.sp,
            )
            Text(
                listOfNotNull(
                    when {
                        usage.cost > 0 -> "估算 ${formatYuan(usage.cost)}"
                        // 有调用却没有金额，说明单价未知，不显示成 ¥0.00。
                        usage.unpricedCalls > 0 -> "无可用单价"
                        else -> "估算 ¥0.00"
                    },
                    "${usage.unpricedCalls} 次未计价".takeIf { usage.unpricedCalls > 0 },
                ).joinToString(" · "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 6 个月柱状图：柱高按所选维度相对最大值缩放，0 显示为浅灰底线。 */
@Composable
private fun MonthlyUsageBars(history: List<UsageStore.MonthUsage>, byCost: Boolean) {
    val values = history.map { if (byCost) it.cost else it.calls.toDouble() }
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        history.forEachIndexed { index, month ->
            val value = values[index]
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (value <= 0) "" else if (byCost) formatYuanCompact(value) else formatTokens(month.calls),
                    fontSize = 10.sp,
                    color = InkSoft,
                )
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth()
                            .fillMaxHeight((value / max).toFloat().coerceIn(0.03f, 1f))
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (value > 0) Green else Color(0xFFE0E0DA)),
                    )
                }
                Text("${month.month.substringAfter('-')}月", fontSize = 10.sp, color = InkSoft)
            }
        }
    }
}

private fun formatUsageDuration(seconds: Long): String = when {
    seconds <= 0 -> "0 秒"
    seconds < 60 -> "$seconds 秒"
    seconds < 3600 -> String.format(Locale.US, "%.1f 分钟", seconds / 60.0)
    else -> String.format(Locale.US, "%.1f 小时", seconds / 3600.0)
}

private fun formatTokens(value: Long): String = String.format(Locale.US, "%,d", value)

/** 金额：小额保留三位，避免几分的调用全被显示成 ¥0.00。 */
private fun formatYuan(value: Double): String = when {
    value < 0.001 -> "¥0.00"
    value < 1 -> String.format(Locale.US, "¥%.3f", value)
    else -> String.format(Locale.US, "¥%.2f", value)
}

private fun formatYuanCompact(value: Double): String = when {
    value < 1 -> String.format(Locale.US, "%.2f", value)
    value < 100 -> String.format(Locale.US, "%.1f", value)
    else -> String.format(Locale.US, "%.0f", value)
}
