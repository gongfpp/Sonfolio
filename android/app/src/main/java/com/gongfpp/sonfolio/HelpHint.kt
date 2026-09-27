package com.gongfpp.sonfolio

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置页统一的「?」帮助入口。默认只留一句短说明，长解释点开弹窗再看，
 * 避免设置页被大段文字淹没。正文里用 `**重点**` 包裹关键信息，会渲染成加粗强调色，
 * 方便快速抓住要点。
 */
@Composable
internal fun HelpHint(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(26.dp)) {
        Icon(
            Icons.Outlined.HelpOutline,
            contentDescription = title,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = { Text(emphasizeHelp(body), lineHeight = 21.sp) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("知道了") } },
        )
    }
}

/** 把 `**...**` 渲染为加粗强调色，标题外的小字号正文用固定行高，便于扫读。 */
internal fun emphasizeHelp(text: String): AnnotatedString = buildAnnotatedString {
    val accent = SpanStyle(fontWeight = FontWeight.Bold, color = Green)
    var index = 0
    while (index < text.length) {
        val start = text.indexOf("**", index)
        if (start < 0) { append(text.substring(index)); break }
        append(text.substring(index, start))
        val end = text.indexOf("**", start + 2)
        if (end < 0) { append(text.substring(start)); break }
        withStyle(accent) { append(text.substring(start + 2, end)) }
        index = end + 2
    }
}
