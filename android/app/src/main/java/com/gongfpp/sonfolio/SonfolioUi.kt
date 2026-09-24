package com.gongfpp.sonfolio

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatElapsed(durationMillis: Long): String {
    val totalSeconds = maxOf(0, durationMillis / 1_000)
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return listOf(hours, minutes, seconds)
        .joinToString(":") { value -> value.toString().padStart(2, '0') }
}

internal fun formatReadableDuration(durationMillis: Long): String {
    val minutes = durationMillis.coerceAtLeast(0L) / 60_000L
    return if (minutes == 0L) "不足1分钟" else "${minutes}分钟"
}

/** 对话时间线默认展示条数，每次「展示更多」再追加同样多。 */
internal const val TIMELINE_PAGE_SIZE = 8

/** 统计格用的紧凑时长：超过 1 小时显示 h:mm，否则显示分钟数。 */
internal fun formatStatDuration(durationMillis: Long): String {
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
internal fun formatCompactDuration(durationMillis: Long): String {
    val minutes = durationMillis.coerceAtLeast(0L) / 60_000L
    return if (minutes == 0L) "不足1分" else "${minutes}分"
}

/**
 * 把 query 里的每个关键词在 text 中的命中片段用强调样式标出。
 * 搜索按空白拆词做 AND 匹配，高亮必须用同一套拆词规则，否则多词查询永远不亮。
 */
internal fun highlightText(text: String, query: String?): AnnotatedString {
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

internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "待写入"
    bytes < 1_024L * 1_024L -> "${bytes / 1_024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}


@Composable
internal fun SectionTitle(title: String) {
    Text(title, modifier = Modifier.padding(top = 20.dp, bottom = 9.dp), fontWeight = FontWeight.Bold, fontSize = 17.sp)
}


@Composable
internal fun DetailTopBar(title: String, meta: String, onBack: () -> Unit, actions: List<Pair<String, () -> Unit>> = emptyList()) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (actions.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多操作") }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    actions.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })
                    }
                }
            }
        }
    }
    Text(meta, modifier = Modifier.padding(start = 46.dp), color = InkSoft, fontSize = 14.sp)
}


internal fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

internal fun formatClock(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

