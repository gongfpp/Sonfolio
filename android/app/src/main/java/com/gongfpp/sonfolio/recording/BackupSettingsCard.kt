package com.gongfpp.sonfolio.recording

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gongfpp.sonfolio.SonfolioApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun BackupSettingsCard() {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    fun run(action: suspend () -> String) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { message = action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = (error.message ?: "操作失败") + "。失败的导出文件不是完整备份，请勿用于恢复。" }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) run { MemoryBackup(app, app.database).export(uri) }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val result = MemoryBackup(app, app.database).restore(uri)
            app.recordingRepository.enqueuePendingVad(); app.recordingRepository.enqueuePendingAsr()
            result
        }
    }
    Surface(Modifier.fillMaxWidth().padding(top = 13.dp), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("完整备份与恢复", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                com.gongfpp.sonfolio.HelpHint(
                    title = "备份包含什么",
                    body = "包含原音、转写、标记、缺口、对话和总结；不包含模型、API Key 与个性化设置。\n\n备份未加密，请保存到你信任的位置，勿公开分享；请先停止录音，并在完成前留在此页面。\n\n恢复仅支持空数据库，不覆盖或合并已有记录。原音逐个校验 SHA-256，通过后统一写入数据库；单份备份文字清单上限 64 MB。恢复后需重新下载模型，在线识别不会自动启用。",
                )
            }
            Text("包含原音、转写、对话与总结；不包含模型与 API Key。", fontSize = 12.sp)
            Row {
                OutlinedButton(enabled = !busy, onClick = { export.launch("Sonfolio-backup-${java.time.LocalDate.now()}.zip") }) { Text("创建完整备份") }
                TextButton(enabled = !busy, onClick = { restoring = true }) { Text("恢复备份") }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在处理文件，请勿离开页面；大录音需要较长时间。", fontSize = 11.sp) }
            message?.let { Text(it, fontSize = 12.sp) }
        }
    }
    if (restoring) AlertDialog(onDismissRequest = { restoring = false }, title = { Text("恢复到空数据库") },
        text = { Text("仅选择你信任的声迹完整备份。已有记录时会拒绝恢复，不删除现有数据。恢复后需重新下载模型，在线识别不会自动启用。") },
        confirmButton = { TextButton(onClick = { restoring = false; restore.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("选择完整备份") } },
        dismissButton = { TextButton(onClick = { restoring = false }) { Text("取消") } })
}
