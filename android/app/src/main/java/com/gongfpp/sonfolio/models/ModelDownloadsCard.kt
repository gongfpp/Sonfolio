package com.gongfpp.sonfolio.models

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.*

/** The download lives beside the mode that needs it, not in a separate settings section. */
@Composable internal fun ModelDownloadControl(model: ModelArtifact) {
    val context = LocalContext.current
    val app = context.applicationContext as com.gongfpp.sonfolio.SonfolioApplication
    val summary by app.summarySettings.config.collectAsStateWithLifecycle()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val manager = remember { WorkManager.getInstance(context) }
    val work by remember { manager.getWorkInfosByTagFlow(ModelDownloadWorker.TAG) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var confirm by remember(model.id) { mutableStateOf<ModelArtifact?>(null) }
    var wifiOnly by remember { mutableStateOf(true) }
    var acceptedLicense by remember(model.id) { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val info = work.filter { "model:${model.id}" in it.tags }.let { list -> list.firstOrNull { !it.state.isFinished } ?: list.maxByOrNull { info -> info.tags.firstOrNull { it.startsWith("created:") }?.substringAfter(':')?.toLongOrNull() ?: 0L } }
                val installed = ModelCatalog.installed(context.filesDir, model)
                val running = info != null && !info.state.isFinished
                Text("${model.label} · ${model.bytes / 1_000_000} MB", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(when {
                    installed -> if (model.kind == ModelKind.SUMMARY) "总结模型已下载 · ${if (summary.mode == com.gongfpp.sonfolio.summary.SummaryMode.LOCAL) "已启用在手机上总结" else "点击下方按钮启用"}" else "已下载，可离线转写"
                    info?.state == WorkInfo.State.RUNNING -> info.progress.getString("message") ?: "正在准备下载"
                    running -> if (info?.constraints?.requiredNetworkType == NetworkType.UNMETERED) "等待非计费网络；手机热点可能仍被系统视为计费网络" else "等待网络与系统调度"
                    info?.state == WorkInfo.State.CANCELLED -> "已取消，重新下载会尝试续传"
                    else -> info?.outputData?.getString("message") ?: "尚未下载；优先使用大陆可访问镜像，下载后按 SHA-256 校验"
                }, fontSize = 11.sp)
                if (running) {
                    LinearProgressIndicator(progress = { (info!!.progress.getLong("bytes", 0).toFloat() / model.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { manager.cancelUniqueWork("download-model:${model.id}") }) { Text("取消下载") }
                } else if (!installed) {
                    OutlinedButton(onClick = { acceptedLicense = false; confirm = model }) { Text(if (model.kind == ModelKind.SUMMARY) "下载总结模型" else "下载语音识别模型") }
                } else if (model.kind == ModelKind.SUMMARY && summary.mode != com.gongfpp.sonfolio.summary.SummaryMode.LOCAL) {
                    OutlinedButton(onClick = {
                        runCatching { app.summarySettings.useDownloadedModel(summary.revision) }
                            .onSuccess { message = "已启用在手机上总结；自动总结默认关闭，可在下方设置" }
                            .onFailure { message = "启用失败，请重试" }
                    }) { Text("使用已下载的总结模型") }
                }
            message?.let { Text(it, fontSize = 11.sp) }
            Text("统一保存在应用私有目录 files/models/，卸载应用会移除。首选大陆可访问镜像，失败时才回退上游，且每个文件都用 SHA-256 校验通过后才启用；下载不上传录音。", fontSize = 11.sp)
        }
    confirm?.let { model -> AlertDialog(onDismissRequest = { confirm = null },
        title = { Text("下载 ${model.bytes / 1_000_000} MB 模型？") },
        text = { Column {
            Text("请预留模型空间及至少 512 MB 录音空间。总结模型用于文字总结，语音识别模型用于转写，互不替代。")
            Text("下载完成后即可离线生成总结；若期间切换了总结配置，不会覆盖你的新选择。", fontSize = 12.sp)
            model.license?.let { license ->
                Text(license.notice, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                TextButton(onClick = { runCatching { uriHandler.openUri(license.url) } }) { Text("查看独立模型许可 ↗") }
                Row { Checkbox(acceptedLicense, { acceptedLicense = it }); Text("我已阅读并接受独立模型许可", Modifier.padding(top = 13.dp), fontSize = 12.sp) }
            }
            Row { Checkbox(wifiOnly, { wifiOnly = it }); Text("仅在非计费网络下载（建议）", Modifier.padding(top = 13.dp)) }
            Text("关闭后可使用移动流量或手机热点。", fontSize = 12.sp)
        } },
        confirmButton = { TextButton(enabled = model.license == null || acceptedLicense, onClick = { ModelDownloadWorker.enqueue(context, model.id, wifiOnly, if (model.kind == ModelKind.SUMMARY) summary.revision else null); confirm = null }) { Text("开始下载") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
