package com.gongfpp.sonfolio.summary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gongfpp.sonfolio.HelpHint
import com.gongfpp.sonfolio.SonfolioApplication
import kotlinx.coroutines.*

@Composable
internal fun SummarySettingsCard() {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    val saved by app.summarySettings.config.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(saved.mode) }
    LaunchedEffect(saved.mode) { mode = saved.mode }
    var endpoint by rememberSaveable { mutableStateOf(saved.endpoint) }
    var model by rememberSaveable { mutableStateOf(saved.model) }
    var provider by rememberSaveable { mutableStateOf(SummaryProvider.fromEndpoint(saved.endpoint)) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var listedModels by remember(provider) { mutableStateOf(provider.defaults) }
    var modelListNote by remember(provider) { mutableStateOf("预设模型，可从官方刷新；费用与可用性以提供商为准") }
    var keyHelp by remember { mutableStateOf(false) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    LaunchedEffect(Unit) {
        if (endpoint.isBlank()) { endpoint = provider.endpoint; model = provider.defaults.firstOrNull().orEmpty() }
    }
    var apiKey by remember { mutableStateOf("") } // 不写入页面恢复状态、日志或剪贴板。
    var automatic by rememberSaveable { mutableStateOf(saved.automatic) }
    var consent by remember(endpoint, mode) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun action(onSuccess: () -> Unit = {}, block: suspend () -> String) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { message = withContext(Dispatchers.IO) { block() }; onSuccess() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "操作失败，原有配置保留" }
            finally { busy = false }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action {
            app.summarySettings.importModel(uri)
            app.summaryCoordinator.cancelAll()
            app.summarySettings.removeReplacedModelCopies()
            "模型已导入。选择手机本地 AI 并保存后，可使用固定样例测试。"
        }
    }
    Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("总结方式", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("当前：${saved.mode.label}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                HelpHint(
                    title = "总结方式说明",
                    body = "本卡片只决定文字如何总结，转文字方式在上方单独设置；切换不会自动重做全部历史。\n\n" +
                        "本地基础整理：直接摘取原句，离线可用，不是生成式 AI。\n手机本地 AI：下载约 491 MB 模型，在手机上生成，离线可用，效果与速度受手机性能影响。\n在线总结：把转写文字发送给所选服务，不上传音频，可能产生费用。",
                )
            }
            SummaryMode.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { mode = option }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == option, onClick = null)
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(option.label, fontSize = 14.sp)
                        Text(when (option) {
                            SummaryMode.BASIC -> "默认 · 在手机上直接摘取原句，离线可用，不是生成式 AI"
                            SummaryMode.LOCAL -> "按需下载约 491 MB 模型，在手机上生成，离线可用，无需密钥"
                            SummaryMode.REMOTE -> "发送转写文字到在线服务，不上传音频，可能产生费用"
                        }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (mode == SummaryMode.LOCAL) {
                com.gongfpp.sonfolio.models.ModelDownloadControl(com.gongfpp.sonfolio.models.ModelCatalog.summary)
                var advancedImport by remember { mutableStateOf(false) }
                TextButton(onClick = { advancedImport = !advancedImport }) { Text("高级：使用已有模型文件") }
                if (advancedImport) {
                    Text("替换会清理旧导入副本，不改变源文件或录音。仅用于了解 GGUF 兼容性的用户。", fontSize = 11.sp)
                    OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }, enabled = !busy) { Text("选择已有 GGUF 文件") }
                }
            }
            if (mode == SummaryMode.REMOTE) {
                Box {
                    OutlinedButton(onClick = { providerMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("提供商：${provider.label} ▾") }
                    DropdownMenu(providerMenu, { providerMenu = false }) {
                        SummaryProvider.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            if (option != provider) {
                                provider = option; endpoint = option.endpoint; model = option.defaults.firstOrNull().orEmpty(); apiKey = ""
                            }
                            providerMenu = false
                        }) }
                    }
                }
                if (provider == SummaryProvider.CUSTOM) {
                OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("完整 HTTPS 接口地址") },
                    placeholder = { Text("https://服务地址/v1/chat/completions") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true, enabled = !busy)
                } else {
                    var advancedModel by remember { mutableStateOf(false) }
                    TextButton(onClick = { advancedModel = !advancedModel }) { Text("高级：选择模型") }
                    if (advancedModel) {
                        Box {
                            OutlinedButton(onClick = { modelMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("模型：$model ▾") }
                            DropdownMenu(modelMenu, { modelMenu = false }) {
                                (listOf(model) + listedModels).filter { it.isNotBlank() }.distinct().forEach { id -> DropdownMenuItem(text = { Text(id) }, onClick = { model = id; modelMenu = false }) }
                            }
                        }
                        Text(modelListNote, fontSize = 11.sp)
                        TextButton(enabled = !busy, onClick = {
                            val selected = provider; val keyDraft = apiKey; val selectedEndpoint = endpoint
                            action {
                                val fetched = SummaryModelDirectory.fetch(selected, app.summarySettings.keyForModelList(selectedEndpoint, keyDraft))
                                withContext(Dispatchers.Main) {
                                    listedModels = fetched; modelListNote = "已从官方获取 ${fetched.size} 个文本模型；当前选择不会被自动替换"
                                }
                                "模型列表已刷新；只查询模型名称，未发送转写"
                            }
                        }) { Text("从官方刷新模型列表") }
                        Text("支持 Chat Completions 与 JSON 输出协议。修改接口地址必须重新填写密钥。", fontSize = 11.sp)
                    }
                }
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text(if (saved.hasKey && saved.endpoint == endpoint) "总结服务密钥（已保存，留空保留）" else "总结服务密钥") },
                    trailingIcon = { IconButton(onClick = { keyHelp = true }) { Text("？") } },
                    singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (provider.help.isNotBlank()) TextButton(onClick = {
                    runCatching { uriHandler.openUri(provider.help) }.onFailure { message = "无法打开浏览器，请到提供商官网创建 API Key" }
                }) { Text("获取 ${provider.label} API Key ↗") }
                Text("密钥只保存在本机加密存储；修改服务地址后需重新填写密钥。", fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it }, enabled = !busy)
                    Text("我同意将所选对话或日期的转写文字发送到上述服务；总结请求不上传音频。", fontSize = 12.sp)
                }
                if (saved.hasKey) TextButton(enabled = !busy, onClick = { action(onSuccess = { apiKey = ""; mode = SummaryMode.BASIC }) {
                    app.summarySettings.clearKey(); app.summaryCoordinator.cancelAll(); "已删除密钥，并切回本地基础整理"
                } }) { Text("删除已保存密钥") }
            }
            if (mode != SummaryMode.BASIC) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(automatic, { automatic = it }, enabled = !busy)
                    Text("转写完成后自动生成 AI 总结", Modifier.padding(start = 8.dp).weight(1f), fontSize = 12.sp)
                    HelpHint(
                        title = "自动 AI 总结的范围",
                        body = "关闭时仍会自动转写并生成基础小结，AI 总结需在对话或一日回顾中手动点击生成。\n\n" +
                            "开启后只自动处理新完成转写涉及的对话和日期，不重做全部历史；低电量时等待，失败可重试。",
                    )
                }
            }
            Button(enabled = !busy, onClick = {
                val key = apiKey
                val chosenMode = mode; val chosenEndpoint = endpoint; val chosenModel = model
                val chosenAutomatic = automatic; val chosenConsent = consent
                action(onSuccess = { apiKey = "" }) {
                    app.summarySettings.save(chosenMode, chosenEndpoint, chosenModel, key, chosenAutomatic, chosenConsent)
                    app.summaryCoordinator.cancelAll()
                    "总结设置已保存，旧队列已取消，已有小结保留"
                }
            }) { Text("保存总结设置") }
            if (saved.mode != SummaryMode.BASIC) {
                OutlinedButton(enabled = !busy, onClick = { action { app.summaryCoordinator.test(app.summarySettings.read()) } }) { Text("测试已保存配置") }
                Text("测试只使用固定样例，不读取真实转写；在线总结测试也可能计费。", fontSize = 11.sp)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, fontSize = 12.sp) }
        }
    }
    if (keyHelp) AlertDialog(onDismissRequest = { keyHelp = false }, title = { Text("总结服务密钥从哪里获取？") },
        text = { Text(if (provider == SummaryProvider.QWEN) "在阿里云百炼创建中国内地（北京）地域的密钥，复制后粘贴到这里。其他地域的密钥不能混用。调用可能计费，建议在官方设置用量限制。密钥只保存在本机，不要分享或截图公开。"
            else "在所选提供商的开发者平台登录，创建密钥后粘贴到这里。它不是聊天 App 的密码。调用可能计费，建议设置用量限制；不要分享或截图公开密钥。") },
        confirmButton = { TextButton(onClick = { keyHelp = false; if (provider.help.isNotBlank()) runCatching { uriHandler.openUri(provider.help) }.onFailure { message = "无法打开浏览器，请到提供商官网查看 API Key 帮助" } }) { Text(if (provider.help.isNotBlank()) "打开官方页面" else "知道了") } },
        dismissButton = { TextButton(onClick = { keyHelp = false }) { Text("关闭") } })
}

@Composable
internal fun SummaryAction(sourceKey: String) {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    val config by app.summarySettings.config.collectAsStateWithLifecycle()
    val run by remember(sourceKey) { app.summaryCoordinator.observe(sourceKey) }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var error by remember(sourceKey) { mutableStateOf<String?>(null) }
    val pending = run?.state in listOf("QUEUED", "RUNNING")
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(when {
            run?.state == "READY" && run?.outputJson != null -> "AI 总结 · ${if (run?.provider == "LOCAL") "在手机上" else "在线"} · 请结合原文核对"
            run?.message != null -> run!!.message!!
            config.mode == SummaryMode.BASIC -> "本地基础整理 · 如需 AI 总结，可在设置中选择方式"
            else -> "已选择${config.mode.label} · 尚未生成本份 AI 总结"
        }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            if (config.mode != SummaryMode.BASIC && !pending) TextButton(onClick = { scope.launch {
                try { withContext(Dispatchers.IO) { app.summaryCoordinator.enqueue(sourceKey) }; error = null }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "无法加入总结队列" }
            } }) { Text(if (run?.outputJson == null) "生成 AI 总结" else "重新生成 AI 总结") }
            if (pending) TextButton(onClick = { scope.launch {
                try { withContext(Dispatchers.IO) { app.summaryCoordinator.cancel(sourceKey) }; error = null }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "取消失败，请重试" }
            } }) { Text("取消总结") }
        }
        error?.let { Text(it, fontSize = 11.sp) }
    }
}
