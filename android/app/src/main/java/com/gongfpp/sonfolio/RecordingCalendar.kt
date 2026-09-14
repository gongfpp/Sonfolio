package com.gongfpp.sonfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

internal fun datesInRange(start: Long, end: Long): List<LocalDate> {
    val first = localDateAt(start)
    val last = localDateAt((end - 1).coerceAtLeast(start))
    return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList()
}

@Composable internal fun RecordingCalendarDialog(
    selected: LocalDate, today: LocalDate, recorded: Set<LocalDate>, organized: Set<LocalDate>,
    onDismiss: () -> Unit, onSelect: (LocalDate) -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("按日期回看") },
        text = { Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                Text("${month.year} 年 ${month.monthValue} 月", Modifier.weight(1f))
                TextButton(enabled = month < YearMonth.from(today), onClick = { month = month.plusMonths(1) }) { Text("›") }
            }
            Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Box(Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.Center) { Text(it, fontSize = 12.sp) }
            } }
            val offset = month.atDay(1).dayOfWeek.value - 1
            repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val day = week * 7 + column - offset + 1
                        Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                            if (day in 1..month.lengthOfMonth()) {
                                val date = month.atDay(day)
                                val marker = when { date in organized -> Color(0xFF3F694B); date in recorded -> Color(0xFFAD7C16); else -> null }
                                TextButton(enabled = date <= today, onClick = { onSelect(date) },
                                    modifier = Modifier.fillMaxSize().semantics {
                                        contentDescription = "$date，${if (date in organized) "已整理对话" else if (date in recorded) "有原音" else "无记录"}"
                                    }, contentPadding = PaddingValues(0.dp)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(day.toString(), Modifier.background(if (date == selected) Color(0xFFE1EEDC) else Color.Transparent, CircleShape).padding(3.dp), fontSize = 13.sp)
                                        Box(Modifier.size(5.dp).background(marker ?: Color.Transparent, CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text("● 绿色：已有对话　● 黄色：已有原音", fontSize = 11.sp)
            Text("未来日期不可选择。没有圆点的过去日期仍可查看。", fontSize = 11.sp)
        } },
        confirmButton = { TextButton(onClick = { onSelect(today) }) { Text("回到本日") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
