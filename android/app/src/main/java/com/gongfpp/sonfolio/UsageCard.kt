package com.gongfpp.sonfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** 本月在线调用量与本机粗估费用，帮助控制外部服务的支出。 */
@Composable
internal fun UsageCard() {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    var snapshot by remember { mutableStateOf(app.usageStore.snapshot()) }
    Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本月在线用量", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                HelpHint(
                    title = "在线用量说明",
                    body = "只统计本机发出的在线识别、总结与纠错调用，按月归零，不记录任何音频或文字内容。\n\n" +
                        "识别按音频时长、总结/纠错按模型返回的 Token 数统计；单价与账单以各服务商控制台为准，这里不做费用估算。\n\n" +
                        "OpenCode Go 这类订阅制服务按模型分别计算额度，不按次计费。历史录音不会自动上传。",
                )
                TextButton(onClick = { snapshot = app.usageStore.snapshot() }) { Text("刷新", fontSize = 12.sp) }
            }
            Text("统计月份 ${snapshot.month}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot.asr.isEmpty() && snapshot.llm.isEmpty()) {
                Text("本月还没有在线调用。", fontSize = 12.sp)
            } else {
                if (snapshot.asr.isNotEmpty()) {
                    Text("在线识别", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    snapshot.asr.forEach { row ->
                        Text("${row.provider}：${row.calls} 次 · ${formatUsageDuration(row.seconds)}", fontSize = 12.sp)
                    }
                }
                if (snapshot.llm.isNotEmpty()) {
                    Text("总结 / 纠错", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    snapshot.llm.forEach { row ->
                        Text("${row.model}：${row.calls} 次 · ${row.tokens} tokens", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun formatUsageDuration(seconds: Long): String =
    if (seconds < 60) "${seconds} 秒" else String.format(Locale.US, "%.1f 分钟", seconds / 60.0)
