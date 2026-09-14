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
    var confirm by remember { mutableStateOf<ModelArtifact?>(null) }
    var wifiOnly by remember { mutableStateOf(true) }
    var acceptedLicense by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val info = work.filter { "model:${model.id}" in it.tags }.let { list -> list.firstOrNull { !it.state.isFinished } ?: list.maxByOrNull { info -> info.tags.firstOrNull { it.startsWith("created:") }?.substringAfter(':')?.toLongOrNull() ?: 0L } }
                val target = ModelCatalog.file(context.filesDir, model)
                val installed = target.isFile && target.length() == model.bytes
                val running = info != null && !info.state.isFinished
                Text("${model.label} · ${model.bytes / 1_000_000} MB", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(when {
                    installed -> if (model == ModelCatalog.summary) "GGUF 已下载 · ${if (summary.mode == com.gongfpp.sonfolio.summary.SummaryMode.LOCAL) "已启用手机本地 AI" else "点击下方按钮启用"}" else "已下载，可离线转写"
                    info?.state == WorkInfo.State.RUNNING -> info.progress.getString("message") ?: "正在准备下载"
                    running -> if (info?.constraints?.requiredNetworkType == NetworkType.UNMETERED) "等待非计费网络；手机热点可能仍被系统视为计费网络" else "等待网络与系统调度"
                    info?.state == WorkInfo.State.CANCELLED -> "已取消，重新下载会尝试续传"
                    else -> info?.outputData?.getString("message") ?: "尚未下载；来源为上游 Hugging Face 仓库"
                }, fontSize = 11.sp)
                if (running) {
                    LinearProgressIndicator(progress = { (info!!.progress.getLong("bytes", 0).toFloat() / model.bytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { manager.cancelUniqueWork("download-model:${model.id}") }) { Text("取消下载") }
                } else if (!installed) {
                    OutlinedButton(onClick = { acceptedLicense = false; confirm = model }) { Text(if (model == ModelCatalog.summary) "下载使用 GGUF 模型" else "下载语音识别模型") }
                } else if (model == ModelCatalog.summary && summary.mode != com.gongfpp.sonfolio.summary.SummaryMode.LOCAL) {
                    OutlinedButton(onClick = {
                        runCatching { app.summarySettings.useDownloadedModel(summary.revision) }
                            .onSuccess { message = "已启用手机本地 AI；自动总结默认关闭，可在下方设置" }
                            .onFailure { message = "启用失败，请重试" }
                    }) { Text("使用已下载的 GGUF 模型") }
                }
            message?.let { Text(it, fontSize = 11.sp) }
            Text("统一保存在应用私有目录 files/models/，卸载应用会移除。模型首次下载需联网，不上传录音；下载源不可达时会报错，不会自动改用不明来源。", fontSize = 11.sp)
        }
    confirm?.let { model -> AlertDialog(onDismissRequest = { confirm = null },
        title = { Text("下载 ${model.bytes / 1_000_000} MB 模型？") },
        text = { Column {
            Text("请预留模型空间及至少 512 MB 录音空间。GGUF 用于文字总结，SenseVoice 用于语音转写，互不替代。")
            if (model == ModelCatalog.summary) Text("下载完成后启用手机本地 AI；若期间切换了总结配置，不会覆盖你的新选择。", fontSize = 12.sp)
            if (model == ModelCatalog.speech) {
                Text("SenseVoice 权重受独立的 FunASR 模型许可约束，含使用限制，不属于客户端的 GPL-3.0 许可。", fontSize = 12.sp)
                TextButton(onClick = { runCatching { uriHandler.openUri("https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE") } }) { Text("查看独立模型许可 ↗") }
                Row { Checkbox(acceptedLicense, { acceptedLicense = it }); Text("我已阅读并接受独立模型许可", Modifier.padding(top = 13.dp), fontSize = 12.sp) }
            }
            Row { Checkbox(wifiOnly, { wifiOnly = it }); Text("仅在非计费网络下载（建议）", Modifier.padding(top = 13.dp)) }
            Text("关闭后可使用移动流量或手机热点。", fontSize = 12.sp)
        } },
        confirmButton = { TextButton(enabled = model != ModelCatalog.speech || acceptedLicense, onClick = { ModelDownloadWorker.enqueue(context, model.id, wifiOnly, if (model == ModelCatalog.summary) summary.revision else null); confirm = null }) { Text("开始下载") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
