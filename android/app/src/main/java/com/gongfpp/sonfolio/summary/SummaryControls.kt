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
import com.gongfpp.sonfolio.SonfolioApplication
import kotlinx.coroutines.*

@Composable
internal fun SummarySettingsCard() {
    val app = LocalContext.current.applicationContext as SonfolioApplication
    val saved by app.summarySettings.config.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(saved.mode) }
    var endpoint by rememberSaveable { mutableStateOf(saved.endpoint) }
    var model by rememberSaveable { mutableStateOf(saved.model) }
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
            Text("当前：${saved.mode.label}。录音和转写始终在本机；切换方式不自动重做全部历史。", fontSize = 12.sp)
            SummaryMode.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { mode = option }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == option, onClick = null)
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(option.label, fontSize = 14.sp)
                        Text(when (option) {
                            SummaryMode.BASIC -> "默认 · 离线摘取原句，无需模型，不是生成式 AI"
                            SummaryMode.LOCAL -> "导入 GGUF 模型后离线生成，占用额外存储和内存"
                            SummaryMode.REMOTE -> "仅发送转写文字到你配置的服务，可能产生费用"
                        }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (mode == SummaryMode.LOCAL) {
                Text(if (saved.localFile.isEmpty()) "尚未导入本地模型" else "已导入：${saved.localLabel}", fontSize = 12.sp)
                Text("当前支持 arm64 手机、GGUF 文本指令模型及内置聊天模板；优先使用小模型。不会自动下载。兼容性与速度以本机测试为准。", fontSize = 11.sp)
                Text("替换模型会移除本 App 内的旧模型副本，不改变你选择的源文件或录音。", fontSize = 11.sp)
                OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }, enabled = !busy) { Text(if (saved.localFile.isEmpty()) "导入 GGUF 模型" else "替换 GGUF 模型") }
            }
            if (mode == SummaryMode.REMOTE) {
                OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("完整 HTTPS 接口地址") },
                    placeholder = { Text("https://服务地址/v1/chat/completions") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true, enabled = !busy)
                OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text(if (saved.hasKey) "API Key（已保存，留空保留）" else "API Key") },
                    singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text("支持 Chat Completions 与 JSON 输出协议。密钥使用本机 Keystore 加密，修改接口地址必须重新填写密钥。", fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it }, enabled = !busy)
                    Text("我同意将所选对话或日期的转写文字发送到上述服务；原始音频不上传。", fontSize = 12.sp)
                }
                if (saved.hasKey) TextButton(enabled = !busy, onClick = { action(onSuccess = { apiKey = ""; mode = SummaryMode.BASIC }) {
                    app.summarySettings.clearKey(); app.summaryCoordinator.cancelAll(); "已删除密钥，并切回本地基础整理"
                } }) { Text("删除已保存密钥") }
            }
            if (mode != SummaryMode.BASIC) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(automatic, { automatic = it }, enabled = !busy)
                    Text("自动总结之后完成转写的新录音", Modifier.padding(start = 8.dp), fontSize = 12.sp)
                }
                Text("默认关闭。开启后按新录音涉及的对话和日期逐个整理；低电量暂停，失败需手动重试。长内容会分段综合。", fontSize = 11.sp)
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
                Text("测试只使用固定样例，不读取真实转写；外部 API 测试也可能计费。", fontSize = 11.sp)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, fontSize = 12.sp) }
        }
    }
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
            run?.state == "READY" && run?.outputJson != null -> "AI 总结 · ${if (run?.provider == "LOCAL") "手机本地" else "外部 API"} · 请结合原文核对"
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
