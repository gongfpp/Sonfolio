package com.gongfpp.sonfolio.processing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gongfpp.sonfolio.HelpHint
import com.gongfpp.sonfolio.SonfolioApplication
import com.gongfpp.sonfolio.Green
import com.gongfpp.sonfolio.formatModelSize
import com.gongfpp.sonfolio.starRating
import com.gongfpp.sonfolio.models.ModelCatalog
import com.gongfpp.sonfolio.models.ModelDownloadControl
import kotlinx.coroutines.*

/** 已保存密钥在输入框中的占位显示，避免把空框误认为没有配置。 */
private const val SAVED_SECRET_MASK = "*****"

@Composable internal fun TranscriptionSettingsCard() {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    val saved by app.transcriptionSettings.config.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(saved.mode) }
    var provider by rememberSaveable { mutableStateOf(saved.provider) }
    var model by rememberSaveable { mutableStateOf(saved.model) }
    var localEngine by rememberSaveable { mutableStateOf(saved.localEngine) }
    var key by remember { mutableStateOf("") }
    var keyTouched by remember { mutableStateOf(false) }
    var appId by rememberSaveable { mutableStateOf(saved.appId) }
    var consent by remember(mode, provider) { mutableStateOf(false) }
    var consentWarning by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var keyHelp by remember { mutableStateOf(false) }
    var engineMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("转文字方式", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("当前：${saved.mode.label}${if (saved.mode == TranscriptionMode.REMOTE) " · ${saved.provider.label}" else " / ${saved.localEngine.displayName}"}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                HelpHint(
                    title = "转文字方式说明",
                    body = "**录音始终先保存在本机**，识别失败不影响录音，也不会删除录音。\n\n" +
                        "**本地识别**：按需下载模型，**离线运行、不上传音频**。默认 SenseVoice 体积小、速度快；Qwen3-ASR 中英混说与方言更强，但约 1 GB、速度更慢。\n\n" +
                        "**在线识别**：先在手机检测人声，再把短片段上传给所选提供商，**可能产生费用**；**历史录音不会自动上传**，需要逐份确认。",
                )
            }
            TranscriptionMode.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { mode = option }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == option, onClick = null)
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(option.label, fontSize = 14.sp)
                        Text(if (option == TranscriptionMode.LOCAL) "默认 · 按需下载本地模型，离线、不上传音频" else "无需本地识别模型，上传人声片段，可能产生费用", fontSize = 11.sp)
                    }
                }
            }
            if (mode == TranscriptionMode.LOCAL) {
                // 引擎选择是「在手机上识别」的下属项：缩进成一个子面板，点一行下钻选择。
                Surface(
                    Modifier.fillMaxWidth().padding(start = 12.dp),
                    RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("手机端识别设置", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { engineMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("识别引擎：${localEngine.displayName} ▾")
                        }
                        ModelDownloadControl(ModelCatalog.byId(localEngine.artifactId)!!)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("尚未下载也能录音与回听；下载完成后自动处理等待中的录音。", fontSize = 11.sp, modifier = Modifier.weight(1f))
                            HelpHint(
                                title = "本地识别引擎怎么选",
                                body = "**SenseVoice**（推荐）：体积最小、速度最快，日常够用。\n" +
                                    "**FireRedASR2-CTC**：中文（含噪声、歌曲）更稳，英文偏弱。\n" +
                                    "**Qwen3-ASR 0.6B**：中英混说与方言更强，支持「个人词汇」热词，但更慢、更占内存。\n\n" +
                                    "三者可随时切换，**只影响之后新转写的录音，已有文字不会重做**；主要语言可在下方设置。",
                            )
                        }
                    }
                }
                // 个人词汇只对本地 Qwen3-ASR 生效，仅在该引擎被选中时展示，避免常驻设置页造成误解。
                if (localEngine == LocalAsrEngine.QWEN3_ASR) com.gongfpp.sonfolio.PersonalVocabularyCard()
            } else {
                Box {
                    OutlinedButton(onClick = { providerMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("识别提供商：${provider.label} ▾") }
                    DropdownMenu(providerMenu, { providerMenu = false }) {
                        SpeechProvider.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            if (option != provider) { provider = option; model = option.models.first(); key = ""; keyTouched = false; appId = "" }
                            providerMenu = false
                        }) }
                    }
                }
                if (provider.needsAppId) {
                    OutlinedTextField(appId, { appId = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                        label = { Text(if (saved.appId.isNotBlank() && provider == saved.provider) "APP ID（已保存，留空保留）" else "APP ID") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                val showSavedKey = !keyTouched && saved.hasKey && provider == saved.provider
                OutlinedTextField(
                    value = if (showSavedKey) SAVED_SECRET_MASK else key,
                    onValueChange = { keyTouched = true; key = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused && showSavedKey) { keyTouched = true; key = "" } },
                    singleLine = true, enabled = !busy,
                    label = { Text("${if (provider.needsAppId) "Access Token" else "识别密钥"}${if (showSavedKey) "（已保存）" else ""}") },
                    trailingIcon = { IconButton(onClick = { keyHelp = true }) { Text("？") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showSavedKey) VisualTransformation.None else PasswordVisualTransformation(),
                )
                TextButton(onClick = { runCatching { uriHandler.openUri(provider.keyPage) }.onFailure { message = "无法打开浏览器，请到提供商官网创建识别密钥" } }) {
                    Text(if (provider.needsAppId) "打开 ${provider.label} 控制台获取 APP ID 与 Access Token ↗" else "获取 ${provider.label} 识别密钥 ↗")
                }
                var advancedModel by remember { mutableStateOf(false) }
                TextButton(onClick = { advancedModel = !advancedModel }) { Text((if (advancedModel) "▴ " else "▾ ") + "高级：选择识别模型", fontSize = 12.sp) }
                if (advancedModel) {
                    Surface(
                        Modifier.fillMaxWidth().padding(start = 12.dp),
                        RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box {
                        OutlinedButton(onClick = { modelMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("语音模型：${provider.modelLabel(model)} ▾") }
                        DropdownMenu(modelMenu, { modelMenu = false }) {
                            provider.models.forEach { option -> DropdownMenuItem(text = { Text(provider.modelLabel(option)) }, onClick = { model = option; modelMenu = false }) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("内置官方语音模型，不是聊天总结模型。", fontSize = 11.sp, modifier = Modifier.weight(1f))
                        HelpHint(
                            title = "在线识别模型与语言",
                            body = when (provider) {
                                SpeechProvider.QWEN -> "使用北京地域的密钥，支持下方「主要语言」设置。内置模型来自官方文档，不是聊天总结模型。"
                                SpeechProvider.DOUBAO -> "使用火山引擎控制台「语音技术」应用的 APP ID 与 Access Token；Secret Key 不需要。识别在云端自动判断中英文与方言，不支持强制指定主要语言，也不使用本地「个人词汇」热词。"
                                else -> "此提供商自动判断语言，不支持强制指定主要语言。内置模型来自官方文档，不是聊天总结模型。"
                            },
                        )
                    }
                    }
                    }
                }
                val consentWarningActive = consentWarning && !consent
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (consentWarningActive) Color(0xFFFDECEA) else Color.Transparent,
                    border = if (consentWarningActive) BorderStroke(1.dp, Color(0xFFB23B2E)) else null,
                ) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(consent, { consent = it; if (it) consentWarning = false }, enabled = !busy)
                        Text(
                            "我同意将保存设置之后开始的录音中的人声音频上传至上述提供商，并承担可能的流量和调用费用。",
                            fontSize = 12.sp,
                            color = if (consentWarningActive) Color(0xFFB23B2E) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HelpHint(
                        title = "在线识别的上传范围",
                        body = "**历史录音不会自动上传**，可在原始录音中逐份确认「继续处理」。**只上传人声短片段**，不上传整段录音；时间戳为人声片段级，不是逐字对齐。\n\n" +
                            "**更改设置会停止后续上传**，已经发出的请求无法撤回；**失败后的手动重试可能再次计费**。",
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text("只上传人声片段，不上传整段录音。", fontSize = 11.sp)
                }
            }
            val canSave = mode != TranscriptionMode.REMOTE || consent
            Button(
                enabled = !busy,
                colors = if (canSave) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                onClick = {
                if (busy) return@Button
                if (!canSave) { consentWarning = true; message = "请先勾选下方的同意项，再保存设置"; return@Button }
                busy = true
                val chosenMode = mode; val chosenProvider = provider; val chosenModel = model; val allowed = consent
                // 未改动已保存的密钥时传空串，表示保留原值而不是把它覆盖成占位符。
                val chosenKey = if (keyTouched) key else ""
                val chosenEngine = localEngine; val chosenAppId = appId
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.transcriptionSettings.save(chosenMode, chosenProvider, chosenModel, chosenKey, allowed, chosenAppId, chosenEngine)
                            app.recordingRepository.enqueuePendingAsr()
                        }
                        key = ""; keyTouched = false; message = "转文字设置已保存。已有文字不重做；在线识别不会自动上传历史录音。"
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { message = error.message ?: "设置保存失败，请重试" }
                    finally { busy = false }
                }
            }) { Text("保存转文字设置") }
            OutlinedButton(enabled = !busy, onClick = {
                if (busy) return@OutlinedButton
                busy = true
                val current = saved
                scope.launch {
                    try {
                        message = withContext(Dispatchers.IO) { runTranscriptionTest(app, current) }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { message = "连通失败：${error.message ?: "未知错误"}" }
                    finally { busy = false }
                }
            }) { Text("测试已保存配置") }
            Text("本地测试只检查模型是否就绪；在线测试发送 1 秒静音样例，不读取真实录音，可能产生极少量调用。", fontSize = 11.sp)
            if (saved.hasKey) TextButton(enabled = !busy, onClick = {
                runCatching { app.transcriptionSettings.clearKey() }.onSuccess {
                    mode = TranscriptionMode.LOCAL; key = ""; appId = ""; message = "识别密钥已删除，已恢复本地识别"
                    scope.launch { app.recordingRepository.enqueuePendingAsr() }
                }.onFailure { message = "删除密钥失败，请重试" }
            }) { Text("删除识别密钥") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, fontSize = 12.sp) }
        }
    }
    if (engineMenu) {
        AlertDialog(
            onDismissRequest = { engineMenu = false },
            title = { Text("选择本地识别引擎") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LocalAsrEngine.entries.forEach { option ->
                        val artifact = ModelCatalog.byId(option.artifactId) ?: return@forEach
                        Row(
                            Modifier.fillMaxWidth().clickable { localEngine = option; engineMenu = false }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(localEngine == option, onClick = null)
                            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                                Text(option.displayName + if (option.recommended) "（推荐）" else "", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(option.blurb, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${formatModelSize(artifact.bytes)} · 速度 ${starRating(option.speedStars)} · 准确率 ${starRating(option.accuracyStars)}",
                                    modifier = Modifier.padding(top = 2.dp),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (option.supportsHotwords) Text("支持个人词汇热词", fontSize = 10.sp, color = Green)
                            }
                        }
                    }
                    Text("切换只影响之后新转写的录音，已有文字不会重做。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { engineMenu = false }) { Text("完成") } },
        )
    }
    if (keyHelp) AlertDialog(onDismissRequest = { keyHelp = false }, title = { Text("如何获取识别密钥") },
        text = { Text("点击卡片中的“获取识别密钥”进入官方控制台，登录并创建密钥，再粘贴到此处。密钥不是聊天 App 的密码，只在本机加密保存；请勿分享或公开截图。各提供商、各地域的密钥不能混用，请在官方设置费用限额。") },
        confirmButton = { TextButton(onClick = { keyHelp = false }) { Text("知道了") } })
}

/** 已保存配置的连通性自检：本地看模型是否就绪，在线发 1 秒静音验证鉴权与端点。 */
private suspend fun runTranscriptionTest(app: SonfolioApplication, config: TranscriptionConfig): String = when (config.mode) {
    TranscriptionMode.LOCAL -> {
        val model = ModelCatalog.byId(config.localEngine.artifactId)
        if (model != null && ModelCatalog.available(app.filesDir, model)) {
            "连通成功：本地识别模型已就绪（${config.localEngine.displayName}）"
        } else {
            "连通失败：本地识别模型尚未下载，请先下载模型"
        }
    }
    TranscriptionMode.REMOTE ->
        if (!config.hasKey) "连通失败：请先保存在线识别密钥"
        else RemoteSpeechTransport(app.transcriptionSettings, app.usageStore).test(config, "connection-test", System.currentTimeMillis())
}
