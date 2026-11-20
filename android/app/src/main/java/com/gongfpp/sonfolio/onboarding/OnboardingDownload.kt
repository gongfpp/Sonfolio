package com.gongfpp.sonfolio.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.gongfpp.sonfolio.formatModelSize
import com.gongfpp.sonfolio.models.ModelArtifact
import com.gongfpp.sonfolio.models.ModelCatalog
import com.gongfpp.sonfolio.models.ModelDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 模型下载的只读视图：可用性、进度、速度与取消入口。
 * 可用性检查放在 IO 线程，组合期不哈希大文件。
 */
internal data class ModelDownloadState(
    val installed: Boolean?,
    val running: Boolean,
    val progress: Float?,
    val bytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long,
    val message: String?,
    val failed: Boolean,
    val start: (Boolean) -> Unit,
    val cancel: () -> Unit,
)

@Composable
internal fun rememberModelDownload(model: ModelArtifact): ModelDownloadState {
    val context = LocalContext.current
    val manager = remember(model.id) { WorkManager.getInstance(context) }
    val work by remember(model.id) {
        manager.getWorkInfosByTagFlow(ModelDownloadWorker.TAG)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val info: WorkInfo? = work.filter { "model:${model.id}" in it.tags }.let { list ->
        list.firstOrNull { !it.state.isFinished }
            ?: list.maxByOrNull { item -> item.tags.firstOrNull { it.startsWith("created:") }?.substringAfter(':')?.toLongOrNull() ?: 0L }
    }
    val running = info != null && !info.state.isFinished
    val bytes = info?.progress?.getLong("bytes", 0L) ?: 0L

    var installed by remember(model.id) { mutableStateOf<Boolean?>(null) }
    // 只在任务状态变化时复查文件；进度每 700ms 上报一次，不能每次都 stat 全部文件。
    LaunchedEffect(model.id, info?.state) {
        installed = withContext(Dispatchers.IO) { ModelCatalog.available(context.filesDir, model) }
    }

    var sampleAt by remember(model.id) { mutableLongStateOf(0L) }
    var sampleBytes by remember(model.id) { mutableLongStateOf(0L) }
    var speed by remember(model.id) { mutableLongStateOf(0L) }
    LaunchedEffect(bytes, running) {
        if (!running || bytes <= 0L) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (sampleAt > 0L && now > sampleAt && bytes > sampleBytes) {
            speed = (bytes - sampleBytes) * 1000L / (now - sampleAt)
        }
        sampleAt = now
        sampleBytes = bytes
    }

    return ModelDownloadState(
        installed = installed,
        running = running,
        progress = if (model.bytes > 0) (bytes.toFloat() / model.bytes).coerceIn(0f, 1f) else null,
        bytes = bytes,
        totalBytes = model.bytes,
        speedBytesPerSecond = speed,
        message = when {
            info == null -> null
            info.state == WorkInfo.State.RUNNING -> info.progress.getString("message")
            info.state == WorkInfo.State.ENQUEUED ->
                if (info.constraints.requiredNetworkType == NetworkType.UNMETERED) "等待非计费网络；手机热点可能仍被系统视为计费网络" else "等待网络与系统调度"
            info.state == WorkInfo.State.CANCELLED -> "已取消，重新下载会续传已下载部分"
            info.state == WorkInfo.State.FAILED -> info.outputData.getString("message") ?: "下载失败，请检查网络后重试"
            else -> info.outputData.getString("message")
        },
        failed = info?.state == WorkInfo.State.FAILED,
        start = { wifiOnly -> ModelDownloadWorker.enqueue(context, model.id, wifiOnly) },
        cancel = { manager.cancelUniqueWork("download-model:${model.id}") },
    )
}

internal fun downloadSummaryText(state: ModelDownloadState): String {
    val percent = ((state.progress ?: 0f) * 100).toInt()
    val speedText = if (state.speedBytesPerSecond > 0) " · " + formatModelSize(state.speedBytesPerSecond) + "/s" else ""
    val remaining = if (state.speedBytesPerSecond > 0 && state.totalBytes > state.bytes) {
        " · 剩余约 " + formatDuration((state.totalBytes - state.bytes) / state.speedBytesPerSecond)
    } else ""
    return "$percent% · ${formatModelSize(state.bytes)} / ${formatModelSize(state.totalBytes)}$speedText$remaining"
}

private fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds} 秒"
    seconds < 3600 -> "${seconds / 60} 分钟"
    else -> String.format(Locale.US, "%.1f 小时", seconds / 3600.0)
}

/** 下载确认：体积、空间提醒、独立许可与非计费网络开关都在这里一次讲清。 */
@Composable
internal fun ModelDownloadDialog(
    model: ModelArtifact,
    onDismiss: () -> Unit,
    onConfirm: (wifiOnly: Boolean) -> Unit,
) {
    var acceptedLicense by remember(model.id) { mutableStateOf(model.license == null) }
    var wifiOnly by remember(model.id) { mutableStateOf(true) }
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载 ${formatModelSize(model.bytes)} 模型？") },
        text = {
            Column {
                Text("请预留模型空间，并为录音额外保留约 512 MB。", fontSize = 12.sp)
                model.license?.let { license ->
                    Spacer(Modifier.height(8.dp))
                    Text(license.notice, fontSize = 12.sp)
                    TextButton(onClick = { runCatching { uriHandler.openUri(license.url) } }) {
                        Text("查看独立模型许可 ↗")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(acceptedLicense, { acceptedLicense = it })
                        Text("我已阅读并接受独立模型许可", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wifiOnly, { wifiOnly = it })
                    Text("仅在非计费网络下载（建议）", fontSize = 12.sp)
                }
                Text("关闭后可使用移动流量或手机热点。", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = acceptedLicense, onClick = { onConfirm(wifiOnly) }) { Text("开始下载") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
