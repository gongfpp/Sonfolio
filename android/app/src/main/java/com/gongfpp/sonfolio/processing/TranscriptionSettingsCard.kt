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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gongfpp.sonfolio.HelpHint
import com.gongfpp.sonfolio.SonfolioApplication
import com.gongfpp.sonfolio.models.ModelCatalog
import com.gongfpp.sonfolio.models.ModelDownloadControl
import kotlinx.coroutines.*

@Composable internal fun TranscriptionSettingsCard() {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    val saved by app.transcriptionSettings.config.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(saved.mode) }
    var provider by rememberSaveable { mutableStateOf(saved.provider) }
    var model by rememberSaveable { mutableStateOf(saved.model) }
    var localEngine by rememberSaveable { mutableStateOf(saved.localEngine) }
    var key by remember { mutableStateOf("") }
    var consent by remember(mode, provider) { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var keyHelp by remember { mutableStateOf(false) }
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
                    body = "录音始终先保存在本机，识别失败不影响录音，也不会删除原音。\n\n" +
                        "本地识别：按需下载模型，离线运行，不上传音频。默认 SenseVoice 体积小、速度快；Qwen3-ASR 中英混说与方言更强，但约 1 GB、速度更慢。\n\n" +
                        "在线识别：先在手机检测人声，再把短片段上传给所选提供商，可能产生费用；历史录音不会自动上传，需要逐份确认。",
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
                Text("本地识别引擎", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                LocalAsrEngine.entries.forEach { option ->
                    val artifact = ModelCatalog.byId(option.artifactId) ?: return@forEach
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { localEngine = option }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(localEngine == option, onClick = null)
                        Column(Modifier.padding(start = 8.dp).weight(1f)) {
                            Text("${option.displayName}${if (option.supportsHotwords) " · 支持个人词汇热词" else ""}", fontSize = 13.sp)
                            Text(artifact.label, fontSize = 11.sp)
                            if (option == LocalAsrEngine.QWEN3_ASR) {
                                Text("当前版本真机实测未返回文字，请先使用 SenseVoice（正在排查）", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            }
                        }
                    }
                }
                ModelDownloadControl(ModelCatalog.byId(localEngine.artifactId)!!)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("尚未下载也能录音与回听；下载完成后自动处理等待中的录音。", fontSize = 11.sp, modifier = Modifier.weight(1f))
                    HelpHint(
                        title = "本地识别引擎怎么选",
                        body = "SenseVoice：默认，约 239 MB，中英日韩粤，速度快。\n" +
                            "Qwen3-ASR 0.6B：约 987 MB，中英混说和方言更强，但速度更慢、占用内存更多，并支持「个人词汇」热词。\n\n" +
                            "两者是并列备选，可随时切换；只影响之后新转写的录音，已有文字不会重做。主要语言可在下方设置。",
                    )
                }
            } else {
                Box {
                    OutlinedButton(onClick = { providerMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("识别提供商：${provider.label} ▾") }
                    DropdownMenu(providerMenu, { providerMenu = false }) {
                        SpeechProvider.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            if (option != provider) { provider = option; model = option.models.first(); key = "" }
                            providerMenu = false
                        }) }
                    }
                }
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    label = { Text(if (saved.hasKey && provider == saved.provider) "识别密钥（已保存，留空保留）" else "识别密钥") },
                    trailingIcon = { IconButton(onClick = { keyHelp = true }) { Text("？") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
                TextButton(onClick = { runCatching { uriHandler.openUri(provider.keyPage) }.onFailure { message = "无法打开浏览器，请到提供商官网创建识别密钥" } }) { Text("获取 ${provider.label} 识别密钥 ↗") }
                var advancedModel by remember { mutableStateOf(false) }
                TextButton(onClick = { advancedModel = !advancedModel }) { Text("高级：选择识别模型") }
                if (advancedModel) {
                    Box {
                        OutlinedButton(onClick = { modelMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("语音模型：$model ▾") }
                        DropdownMenu(modelMenu, { modelMenu = false }) {
                            provider.models.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { model = option; modelMenu = false }) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("内置官方语音模型，不是聊天总结模型。", fontSize = 11.sp, modifier = Modifier.weight(1f))
                        HelpHint(
                            title = "在线识别模型与语言",
                            body = if (provider == SpeechProvider.QWEN)
                                "使用北京地域的密钥，支持下方「主要语言」设置。内置模型来自官方文档，不是聊天总结模型。"
                            else "此提供商自动判断语言，不支持强制指定主要语言。内置模型来自官方文档，不是聊天总结模型。",
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it }, enabled = !busy)
                    Text("我同意将保存设置之后开始的录音中的人声音频上传至上述提供商，并承担可能的流量和调用费用。", fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HelpHint(
                        title = "在线识别的上传范围",
                        body = "历史录音不会自动上传，可在原始录音中逐份确认「继续处理」。先在本机检测人声，再上传短片段；时间戳为人声片段级，不是逐字对齐。\n\n" +
                            "更改设置会停止后续上传，已经发出的请求无法撤回；失败后的手动重试可能再次计费。",
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text("只上传人声片段，不上传整段原音。", fontSize = 11.sp)
                }
            }
            Button(enabled = !busy, onClick = {
                if (busy) return@Button
                busy = true
                val chosenMode = mode; val chosenProvider = provider; val chosenModel = model; val chosenKey = key; val allowed = consent
                val chosenEngine = localEngine
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.transcriptionSettings.save(chosenMode, chosenProvider, chosenModel, chosenKey, allowed, chosenEngine)
                            app.recordingRepository.enqueuePendingAsr()
                        }
                        key = ""; message = "转文字设置已保存。已有文字不重做；在线识别不会自动上传历史录音。"
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { message = error.message ?: "设置保存失败，请重试" }
                    finally { busy = false }
                }
            }) { Text("保存转文字设置") }
            if (saved.hasKey) TextButton(enabled = !busy, onClick = {
                runCatching { app.transcriptionSettings.clearKey() }.onSuccess {
                    mode = TranscriptionMode.LOCAL; key = ""; message = "识别密钥已删除，已恢复本地识别"
                    scope.launch { app.recordingRepository.enqueuePendingAsr() }
                }.onFailure { message = "删除密钥失败，请重试" }
            }) { Text("删除识别密钥") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, fontSize = 12.sp) }
        }
    }
    if (keyHelp) AlertDialog(onDismissRequest = { keyHelp = false }, title = { Text("如何获取识别密钥") },
        text = { Text("点击卡片中的“获取识别密钥”进入官方控制台，登录并创建密钥，再粘贴到此处。密钥不是聊天 App 的密码，只在本机加密保存；请勿分享或公开截图。各提供商、各地域的密钥不能混用，请在官方设置费用限额。") },
        confirmButton = { TextButton(onClick = { keyHelp = false }) { Text("知道了") } })
}
