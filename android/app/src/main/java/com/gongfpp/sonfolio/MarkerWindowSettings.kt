package com.gongfpp.sonfolio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun MarkerWindowSettings(preferences: SonfolioPreferences) {
    var windows by remember { mutableStateOf(preferences.markerWindows) }
    Surface(Modifier.fillMaxWidth().padding(top = 13.dp), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("录音回溯标记", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("时长表示向前寻找重点内容的窗口，不是总结长度；命中后高亮整场连续对话。主按钮同时用于通知栏。", fontSize = 12.sp)
            windows.forEachIndexed { index, minutes ->
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text("${listOf("主按钮", "快捷一", "快捷二")[index]}：前 $minutes 分钟") }
                    DropdownMenu(expanded, { expanded = false }) {
                        SonfolioPreferences.MARKER_OPTIONS.forEach { value ->
                            DropdownMenuItem(text = { Text("前 $value 分钟") }, onClick = {
                                preferences.setMarkerWindow(index, value)
                                windows = preferences.markerWindows; expanded = false
                            })
                        }
                    }
                }
            }
        }
    }
}
