package com.gongfpp.sonfolio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 对话整理：自动标题来源与对话合并间隔。两项都会影响已有对话的重新整理，
 * 修改后触发一次重建。
 */
@Composable
internal fun OrganizeSettingsCard(preferences: SonfolioPreferences, onRebuildConversations: () -> Unit) {
    var titleMode by remember { mutableStateOf(preferences.titleMode) }
    var gapMinutes by remember { mutableStateOf(preferences.conversationGapMinutes) }
    var gapMenu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("对话整理", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                HelpHint(
                    title = "对话整理说明",
                    body = "**自动标题**：没有 AI 总结时，标题来自规则或转写的第一句有信息量的话。\n\n" +
                        "**对话合并间隔**：相邻语音停顿不超过这个间隔、且中间没有录音缺失，就算同一场对话，可跨越多个 5 分钟切片。**间隔调大能减少一整段被拆开**，但可能把不相关的内容并到一起。\n\n" +
                        "当前**按时间连续性合并，不是语义主题识别**；同一主题停顿过久仍可能被分开。",
                )
            }
            Text("自动标题来源", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TitleMode.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        titleMode = option
                        preferences.setTitleMode(option)
                        onRebuildConversations()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(titleMode == option, onClick = null)
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(option.label, fontSize = 14.sp)
                        Text(option.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("对话合并间隔", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Box {
                OutlinedButton(onClick = { gapMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("相邻语音间隔：$gapMinutes 分钟 ▾")
                }
                if (gapMenu) SettingsChoices("选择对话合并间隔", SonfolioPreferences.CONVERSATION_GAP_OPTIONS, gapMinutes, { "$it 分钟" }, { gapMenu = false }) { value ->
                            gapMinutes = value
                            preferences.setConversationGapMinutes(value)
                            gapMenu = false
                            onRebuildConversations()
                }
            }
            Text("多段连续谈话（例如持续一两小时）可把间隔调大，减少被切成多场对话。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
