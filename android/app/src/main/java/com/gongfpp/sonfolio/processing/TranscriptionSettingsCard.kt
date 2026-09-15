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
            Text("当前：${saved.mode.label}${if (saved.mode == TranscriptionMode.REMOTE) " · ${saved.provider.label}" else " · 本机识别"}。录音始终先保存在本机；识别失败不影响录音。", fontSize = 12.sp)
            TranscriptionMode.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { mode = option }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == option, onClick = null)
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(option.label, fontSize = 14.sp)
                        Text(if (option == TranscriptionMode.LOCAL) "默认 · 按需下载约 239 MB 模型，离线、不上传音频" else "无需本地识别模型，上传人声片段，可能产生费用", fontSize = 11.sp)
                    }
                }
            }
            if (mode == TranscriptionMode.LOCAL) {
                ModelDownloadControl(ModelCatalog.speech)
                Text("尚未下载也能录音与回听；模型下载完成后处理等待中的录音。主要语言可在下方设置。", fontSize = 11.sp)
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
                    Text("内置官方文档中的语音模型，不是聊天总结模型。${if (provider == SpeechProvider.QWEN) "使用北京地域密钥，支持下方主要语言设置。" else "此提供商自动判断语言，不支持强制主要语言。"}", fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it }, enabled = !busy)
                    Text("我同意将保存设置之后开始的录音中的人声音频上传至上述提供商，并承担可能的流量和调用费用。", fontSize = 12.sp)
                }
                Text("历史录音不会自动上传，可在原始录音中逐份确认“继续处理”。先在本机检测人声，再上传短片段；时间戳为人声片段级，不是逐字对齐。更改设置会停止后续上传，已经发出的请求无法撤回；失败后的手动重试可能再次计费。", fontSize = 11.sp)
            }
            Button(enabled = !busy, onClick = {
                if (busy) return@Button
                busy = true
                val chosenMode = mode; val chosenProvider = provider; val chosenModel = model; val chosenKey = key; val allowed = consent
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.transcriptionSettings.save(chosenMode, chosenProvider, chosenModel, chosenKey, allowed)
                            app.recordingRepository.enqueuePendingAsr()
                        }
                        key = ""; message = "转文字设置已保存。已有文字不重做；外部识别不会自动上传历史录音。"
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
