package com.gongfpp.sonfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 配置使用二级页面；单个选项统一在可滚动的单选弹窗内选择。 */
@Composable internal fun SettingsChoiceDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable internal fun <T> SettingsChoices(title: String, choices: List<T>, selected: T, label: (T) -> String,
    onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    SettingsChoiceDialog(title, onDismiss) {
        choices.forEach { option ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onSelect(option) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == option, onClick = null)
                Text(label(option), Modifier.weight(1f).padding(start = 8.dp))
            }
        }
    }
}
