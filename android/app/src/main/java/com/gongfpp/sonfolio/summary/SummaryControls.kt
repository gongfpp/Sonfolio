package com.gongfpp.sonfolio.summary

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gongfpp.sonfolio.HelpHint
import com.gongfpp.sonfolio.SonfolioApplication
import com.gongfpp.sonfolio.Green
import kotlinx.coroutines.*
import java.io.File

/** 已保存密钥在输入框中的占位显示，避免把空框误认为没有配置。 */
private const val SAVED_SECRET_MASK = "*****"

@Composable
internal fun SummarySettingsCard(onDirtyChange: (Boolean) -> Unit = {}, onBusyChange: (Boolean) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    val saved by app.summarySettings.config.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(saved.mode) }
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
    var keyTouched by remember { mutableStateOf(false) }
    var automatic by rememberSaveable { mutableStateOf(saved.automatic) }
    var correctionOnline by remember { mutableStateOf(app.preferences.correctionOnlineEnabled) }
    var consent by remember(endpoint, mode) { mutableStateOf(false) }
    var consentWarning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var selectedSummaryId by rememberSaveable { mutableStateOf(app.summarySettings.selectedCatalogModelId(saved)) }
    val dirty = mode != saved.mode || automatic != saved.automatic || (keyTouched && apiKey.isNotBlank()) ||
        (mode == SummaryMode.REMOTE && (endpoint != saved.endpoint || model != saved.model)) ||
        (mode == SummaryMode.LOCAL && selectedSummaryId != app.summarySettings.selectedCatalogModelId(saved)) ||
        correctionOnline != app.preferences.correctionOnlineEnabled
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    LaunchedEffect(busy) { onBusyChange(busy) }
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
    Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("总结方式", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已生效：${saved.mode.label}${if (saved.mode == SummaryMode.REMOTE) " · ${SummaryProvider.fromEndpoint(saved.endpoint).label} · ${saved.model}" else if (saved.mode == SummaryMode.LOCAL) " · ${saved.localLabel}" else ""}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                HelpHint(
                    title = "总结方式说明",
                    body = "本卡片只决定**文字如何总结**，转文字方式在上方单独设置；**切换不会自动重做全部历史**。\n\n" +
                        "**本地基础整理**：直接摘取原句，离线可用，不是生成式 AI。\n**手机本地 AI**：下载约 491 MB 模型，在手机上生成，离线可用，效果与速度受手机性能影响。\n**在线总结**：把转写文字发送给所选服务，**不上传音频**，**可能产生费用**。",
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
                val summaryModels = com.gongfpp.sonfolio.models.ModelCatalog.summaryModels
                val context = LocalContext.current
                var summaryModelMenu by remember { mutableStateOf(false) }
                val selectedSummary = summaryModels.firstOrNull { it.id == selectedSummaryId } ?: summaryModels.first()
                // localFile 不是内置 id 时说明当前启用的是导入的 GGUF，面板要显示它而不是内置模型。
                val importedLabel = if (selectedSummaryId == null && app.summarySettings.selectedCatalogModelId(saved) == null && saved.localFile.isNotEmpty()) {
                    saved.localLabel.ifBlank { "导入的 GGUF" }
                } else null
                // 与「本地识别引擎」保持同一套层级：二级面板 + 模型行下拉选择。
                Surface(
                    Modifier.fillMaxWidth().padding(start = 12.dp),
                    RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("手机端总结设置", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            com.gongfpp.sonfolio.HelpHint(
                                title = "本地总结怎么用",
                                body = "在手机本地生成对话小结，**离线可用**，效果与速度受手机性能影响。当前内置 **Qwen2.5-0.5B（约 491 MB）**；下载后即可启用，未下载时可用基础整理或在线总结。",
                            )
                        }
                        Box {
                            OutlinedButton(onClick = { summaryModelMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    importedLabel?.let { "总结模型：$it（导入）▾" }
                                        ?: "总结模型：${selectedSummary.label} · ${com.gongfpp.sonfolio.formatModelSize(selectedSummary.bytes)} ▾",
                                )
                            }
                            DropdownMenu(summaryModelMenu, { summaryModelMenu = false }) {
                                summaryModels.forEach { option ->
                                    // 用只比大小的 available（不哈希大文件）；真正启用前仍会完整校验。
                                    val installed = com.gongfpp.sonfolio.models.ModelCatalog.available(context.filesDir, option)
                                    DropdownMenuItem(
                                        text = { Text(option.label + " · " + com.gongfpp.sonfolio.formatModelSize(option.bytes) + if (installed) "" else "（未下载）") },
                                        onClick = {
                                            selectedSummaryId = option.id
                                            summaryModelMenu = false
                                            message = if (installed) "已选择 ${option.label}，保存设置后生效" else "请先下载该模型，再保存设置"
                                        },
                                    )
                                }
                            }
                        }
                        if (importedLabel != null) {
                            Text("当前使用导入的 GGUF：$importedLabel；仅离线使用，不上传。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(enabled = !busy, onClick = {
                                runCatching { app.summarySettings.clearImportedModel() }
                                    .onSuccess { message = "已删除导入的模型，回退到基础整理" }
                                    .onFailure { message = "删除失败：${it.message}" }
                            }) { Text("删除导入的模型") }
                        } else {
                            com.gongfpp.sonfolio.models.ModelDownloadControl(selectedSummary, allowActivation = false)
                        }
                        // 高级：导入自带 GGUF（llama.cpp 格式），复制到应用私有目录后即可离线使用。
                        val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                        ) { uri ->
                            if (uri == null || busy) return@rememberLauncherForActivityResult
                            busy = true
                            scope.launch {
                                try {
                                    val name = "${java.util.UUID.randomUUID()}.gguf"
                                    withContext(Dispatchers.IO) {
                                        val target = File(context.filesDir, "summary-models/$name")
                                        target.parentFile?.mkdirs()
                                        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选文件" }
                                            .use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                                        // llama.cpp 的 GGUF 文件必须以「GGUF」开头；先校验再登记，避免导入坏文件。
                                        val magic = java.io.RandomAccessFile(target, "r").use { raf ->
                                            ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
                                        }
                                        require(magic == "GGUF") { "所选文件不是 GGUF 模型，请用 .gguf 文件（llama.cpp 格式）" }
                                        app.summarySettings.useImportedModel(name, "导入的模型")
                                    }
                                    mode = SummaryMode.LOCAL; selectedSummaryId = null; automatic = app.summarySettings.read().automatic
                                    message = "已导入并启用本地模型；仅离线使用，不上传。"
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) { message = error.message ?: "导入失败，请换一个文件" }
                                finally { busy = false }
                            }
                        }
                        TextButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text("▾ 高级：导入并启用自己的 GGUF 模型", fontSize = 12.sp)
                        }
                    }
                }
            }
            if (mode == SummaryMode.REMOTE) {
                Box {
                    OutlinedButton(onClick = { providerMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("提供商：${provider.label} ▾") }
                    DropdownMenu(providerMenu, { providerMenu = false }) {
                        SummaryProvider.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            if (option != provider) {
                                provider = option; endpoint = option.endpoint; model = option.defaults.firstOrNull().orEmpty(); apiKey = ""; keyTouched = false
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
                    // 提供商与模型都是必选项，不折叠进「高级」。
                    Box {
                        OutlinedButton(onClick = { modelMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("模型：$model ▾") }
                        DropdownMenu(modelMenu, { modelMenu = false }) {
                            (listOf(model) + listedModels).filter { it.isNotBlank() }.distinct().forEach { id -> DropdownMenuItem(text = { Text(id) }, onClick = { model = id; modelMenu = false }) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(modelListNote, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(enabled = !busy, onClick = {
                            val selected = provider; val keyDraft = apiKey; val selectedEndpoint = endpoint
                            action {
                                val fetched = SummaryModelDirectory.fetch(selected, app.summarySettings.keyForModelList(selectedEndpoint, keyDraft))
                                withContext(Dispatchers.Main) {
                                    listedModels = fetched; modelListNote = "已从官方获取 ${fetched.size} 个文本模型；当前选择不会被自动替换"
                                }
                                "模型列表已刷新；只查询模型名称，未发送转写"
                            }
                        }) { Text(if (busy) "刷新中…" else "从官方刷新模型列表") }
                    }
                    Text("支持 Chat Completions 与 JSON 输出协议。修改接口地址必须重新填写密钥。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val showSavedKey = !keyTouched && saved.hasKey && saved.endpoint == endpoint
                OutlinedTextField(
                    value = if (showSavedKey) SAVED_SECRET_MASK else apiKey,
                    onValueChange = { keyTouched = true; apiKey = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused && showSavedKey) { keyTouched = true; apiKey = "" } },
                    label = { Text(if (showSavedKey) "总结服务密钥（已保存）" else "总结服务密钥") },
                    trailingIcon = { IconButton(onClick = { keyHelp = true }) { Text("？") } },
                    singleLine = true, enabled = !busy,
                    visualTransformation = if (showSavedKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                when {
                    showSavedKey -> Text("✓ 已保存密钥（$SAVED_SECRET_MASK）：留空保存保留原值，输入新值会替换", fontSize = 11.sp, color = Green)
                    apiKey.isNotBlank() -> Text("将保存为新的密钥", fontSize = 11.sp, color = Green)
                    else -> Text("尚未填写密钥；点击下方链接到官方控制台创建。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (provider.help.isNotBlank()) TextButton(onClick = {
                    runCatching { uriHandler.openUri(provider.help) }.onFailure { message = "无法打开浏览器，请到提供商官网创建 API Key" }
                }) { Text("获取 ${provider.label} API Key ↗") }
                Text("密钥只保存在本机加密存储；修改服务地址后需重新填写密钥。", fontSize = 11.sp)
                val consentWarningActive = consentWarning && !consent
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (consentWarningActive) Color(0xFFFDECEA) else Color.Transparent,
                    border = if (consentWarningActive) BorderStroke(1.dp, Color(0xFFB23B2E)) else null,
                ) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(consent, { consent = it; if (it) consentWarning = false }, enabled = !busy)
                        Text(
                            "我同意将所选对话或日期的转写文字发送到上述服务；总结请求不上传音频。",
                            fontSize = 12.sp,
                            color = if (consentWarningActive) Color(0xFFB23B2E) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (saved.hasKey) TextButton(enabled = !busy, onClick = { action(onSuccess = { apiKey = ""; keyTouched = false; mode = SummaryMode.BASIC }) {
                    app.summarySettings.clearKey(); app.summaryCoordinator.cancelAll(); "已删除密钥，并切回本地基础整理"
                } }) { Text("删除已保存密钥") }
            }
            if (mode != SummaryMode.BASIC) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(automatic, { automatic = it }, enabled = !busy)
                    Text("转写完成后自动生成 AI 总结", Modifier.padding(start = 8.dp).weight(1f), fontSize = 12.sp)
                    HelpHint(
                        title = "自动 AI 总结的范围",
                        body = "关闭时仍会自动转写并生成基础小结，**AI 总结需在对话或一日回顾中手动点击生成**。\n\n" +
                            "开启后**只自动处理新完成转写涉及的对话和日期，不重做全部历史**；低电量时等待，失败可重试。",
                    )
                }
                if (mode == SummaryMode.REMOTE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(correctionOnline, { value -> correctionOnline = value }, enabled = !busy)
                        Text("转写后自动用该模型纠错（在线会持续计费）", Modifier.padding(start = 8.dp).weight(1f), fontSize = 12.sp)
                        HelpHint(
                            title = "自动纠错（第 5 步）",
                            body = "转写整理完成后，用总结模型纠正错别字与标点；**原文保留在「原始版本」，可随时撤销**。\n\n" +
                                "**本地模型默认自动纠错**，离线且无费用；**在线模型会把整段转写文字发到外部服务并计费**，所以默认关闭，需要在这里单独打开。",
                        )
                    }
                } else {
                    Text("本地模型会自动完成一次错别字与标点纠错（离线、无费用）。", Modifier.padding(top = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val canSave = mode != SummaryMode.REMOTE || consent
            if (dirty) Text("有尚未保存的更改 · 下面的选择尚未生效", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            Button(
                enabled = !busy,
                colors = if (canSave) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                onClick = {
                if (!canSave) { consentWarning = true; message = "请先勾选下方的同意项，再保存设置"; return@Button }
                // 未改动已保存的密钥时传空串，表示保留原值而不是把它覆盖成占位符。
                val key = if (keyTouched) apiKey else ""
                val chosenMode = mode; val chosenEndpoint = endpoint; val chosenModel = model
                val chosenAutomatic = automatic; val chosenConsent = consent
                val chosenLocal = if (chosenMode == SummaryMode.LOCAL && (selectedSummaryId != null || saved.localFile.isEmpty())) selectedSummaryId ?: com.gongfpp.sonfolio.models.ModelCatalog.summary.id else null
                val chosenCorrection = correctionOnline
                action(onSuccess = { apiKey = ""; keyTouched = false; selectedSummaryId = app.summarySettings.selectedCatalogModelId() }) {
                    app.summarySettings.save(chosenMode, chosenEndpoint, chosenModel, key, chosenAutomatic, chosenConsent, chosenLocal)
                    app.preferences.setCorrectionOnlineEnabled(chosenCorrection)
                    app.summaryCoordinator.cancelAll()
                    "总结设置已保存，旧队列已取消，已有小结保留"
                }
            }) { Text("保存总结设置") }
            if (saved.mode != SummaryMode.BASIC) {
                OutlinedButton(enabled = !busy, onClick = { action { app.summaryCoordinator.test(app.summarySettings.read()) } }) { Text("测试已保存配置") }
                Text("只验证连通性：本地确认模型就绪；在线只发一个最小请求，不读取真实转写。", fontSize = 11.sp)
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

/** 对话详情里的「生成 AI 总结」入口：复用已配置的总结模型，产出小结并更新本段小结/标题。 */
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
