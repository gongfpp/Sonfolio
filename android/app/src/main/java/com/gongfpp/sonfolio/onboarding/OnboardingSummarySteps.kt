package com.gongfpp.sonfolio.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gongfpp.sonfolio.CardSurface
import com.gongfpp.sonfolio.Green
import com.gongfpp.sonfolio.Ink
import com.gongfpp.sonfolio.InkSoft
import com.gongfpp.sonfolio.Line
import com.gongfpp.sonfolio.PaleGreen
import com.gongfpp.sonfolio.SonfolioApplication
import com.gongfpp.sonfolio.formatModelSize
import com.gongfpp.sonfolio.models.ModelArtifact
import com.gongfpp.sonfolio.models.ModelCatalog
import com.gongfpp.sonfolio.summary.SummaryConfig
import com.gongfpp.sonfolio.summary.SummaryMode
import com.gongfpp.sonfolio.summary.SummaryModelDirectory
import com.gongfpp.sonfolio.summary.SummaryProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 04 · 总结方式：三选一，没有跳过出口。默认全不选中，避免用户以为“已经开了 AI 总结”。
 * 基础整理保留为可选项——它是代码里的兜底默认值，不是 AI。
 */
@Composable
internal fun SummaryModeStep(
    app: SonfolioApplication,
    onDone: () -> Unit,
    onRemote: () -> Unit,
    onBack: () -> Unit,
) {
    var choiceName by rememberSaveable { mutableStateOf("") }
    val choice = runCatching { SummaryMode.valueOf(choiceName) }.getOrNull()
    val localModel = ModelCatalog.summary
    val download = rememberModelDownload(localModel)
    val scope = rememberCoroutineScope()
    var confirmDownload by remember { mutableStateOf<ModelArtifact?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var enableLocalWhenReady by rememberSaveable { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun saveMode(mode: SummaryMode, localModelId: String? = null) {
        if (busy || finished) return
        busy = true
        failed = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { saveSummaryMode(app, mode, localModelId) }
                finished = true
                onDone()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                message = error.message ?: "保存失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(download.installed, enableLocalWhenReady) {
        if (enableLocalWhenReady && download.installed == true && !finished) {
            saveMode(SummaryMode.LOCAL, localModel.id)
        }
    }

    OnboardingFrame(
        step = OnboardingStep.SUMMARY_MODE,
        onBack = onBack,
        content = {
            Headline("如何整理你的录音", size = 19)
            Spacer(Modifier.height(7.dp))
            Body("必须选一项；之后随时能在设置里更换。")
            Spacer(Modifier.height(20.dp))
            ChoiceCard(
                selected = choice == SummaryMode.BASIC,
                title = "基础整理",
                description = "在手机上直接摘取原句，离线可用",
                meta = "不是生成式 AI · 只挑原句 · 0 费用",
                onClick = { choiceName = SummaryMode.BASIC.name; message = null },
                badge = "零成本",
                enabled = !busy && !download.running,
            )
            Spacer(Modifier.height(10.dp))
            ChoiceCard(
                selected = choice == SummaryMode.LOCAL,
                title = "在手机上总结（本地模型）",
                description = "下载 ${formatModelSize(localModel.bytes)} 模型，在手机上生成总结",
                meta = "生成式 · 离线可用 · 需下载 ${formatModelSize(localModel.bytes)}",
                onClick = { choiceName = SummaryMode.LOCAL.name; message = null },
                enabled = !busy && !download.running,
            )
            Spacer(Modifier.height(10.dp))
            ChoiceCard(
                selected = choice == SummaryMode.REMOTE,
                title = "在线总结",
                description = "调用 DeepSeek、通义千问等生成总结",
                meta = "生成式 · 按量计费 · 需自备密钥",
                onClick = { choiceName = SummaryMode.REMOTE.name; message = null },
                enabled = !busy && !download.running,
            )
            Spacer(Modifier.height(16.dp))
            if (download.running) {
                Text(downloadSummaryText(download), fontSize = 11.sp, color = InkSoft)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { download.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { download.cancel(); enableLocalWhenReady = false }) {
                    Text("取消下载（保留已下载部分）")
                }
            }
            AmberNote("基础整理只挑选原句、不做重写；要真正提炼归纳，请选后两项。")
            InlineMessage(message, failed)
        },
        bottom = {
            val label = when {
                choice == null -> "继续"
                choice == SummaryMode.REMOTE -> "继续 · 填写在线配置"
                choice == SummaryMode.LOCAL && download.installed != true -> "下载并启用 · ${formatModelSize(localModel.bytes)}"
                choice == SummaryMode.LOCAL -> "继续"
                else -> "继续"
            }
            PrimaryAction(
                label = label,
                enabled = choice != null && !busy && !download.running,
                loading = busy,
                onClick = {
                    when (choice) {
                        null -> Unit
                        SummaryMode.BASIC -> saveMode(SummaryMode.BASIC)
                        SummaryMode.LOCAL ->
                            if (download.installed == true) saveMode(SummaryMode.LOCAL, localModel.id)
                            else confirmDownload = localModel
                        SummaryMode.REMOTE -> onRemote()
                    }
                },
            )
            if (choice == null) {
                Hint("必须选择一项才能继续", centered = true)
            }
        },
    )

    confirmDownload?.let { target ->
        ModelDownloadDialog(
            model = target,
            onDismiss = { confirmDownload = null },
            onConfirm = { wifiOnly ->
                confirmDownload = null
                enableLocalWhenReady = true
                download.start(wifiOnly)
            },
        )
    }
}

/**
 * 05 · 在线总结：线路 + 密钥 + 模型（可向官方刷新清单）+ 同意 + 自动开关。
 * 刷新只更新候选清单，不改变当前已选模型。
 */
@Composable
internal fun SummaryRemoteStep(
    app: SonfolioApplication,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val saved = app.summarySettings.read()
    var providerName by rememberSaveable {
        mutableStateOf(
            SummaryProvider.fromEndpoint(saved.endpoint).takeIf { saved.endpoint.isNotBlank() }?.name
                ?: SummaryProvider.DEEPSEEK.name,
        )
    }
    val provider = runCatching { SummaryProvider.valueOf(providerName) }.getOrDefault(SummaryProvider.DEEPSEEK)
    var endpoint by rememberSaveable {
        mutableStateOf(
            saved.endpoint.takeIf { saved.endpoint.isNotBlank() && SummaryProvider.fromEndpoint(saved.endpoint) == provider }
                ?: provider.endpoint,
        )
    }
    var model by rememberSaveable {
        mutableStateOf(saved.model.takeIf { it.isNotBlank() } ?: provider.defaults.firstOrNull().orEmpty())
    }
    var key by rememberSaveable { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var automatic by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var savedOk by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var listed by remember { mutableStateOf<List<String>>(emptyList()) }
    var listState by remember { mutableStateOf<ModelListState>(ModelListState.Idle) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val candidates = (provider.defaults + listed + model.takeIf { it.isNotBlank() }.orEmpty()).distinct()
    val canSave = key.isNotBlank() && consent && model.isNotBlank() &&
        (provider != SummaryProvider.CUSTOM || endpoint.isNotBlank()) && !busy

    fun refreshModels() {
        if (busy || listState is ModelListState.Running) return
        listState = ModelListState.Running
        scope.launch {
            try {
                val keyForList = withContext(Dispatchers.IO) {
                    app.summarySettings.keyForModelList(endpoint, key)
                }
                val fetched = SummaryModelDirectory.fetch(provider, keyForList)
                listed = fetched
                listState = ModelListState.Success(fetched.size)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                listState = ModelListState.Failed(error.message ?: "官方模型列表暂不可用")
            }
        }
    }

    fun saveAndTest() {
        if (busy) return
        busy = true
        failed = false
        message = "正在保存并验证…"
        scope.launch {
            try {
                val config = withContext(Dispatchers.IO) { saveSummaryRemote(app, provider, endpoint, model, key, consent, automatic) }
                savedOk = true
                val result = withContext(Dispatchers.IO) { app.summaryCoordinator.test(config) }
                message = result
                key = ""
                onDone()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                message = (error.message ?: "验证失败，请检查网络或密钥") +
                    if (savedOk) "（配置已保存，可稍后重试验证）" else ""
            } finally {
                busy = false
            }
        }
    }

    OnboardingFrame(
        step = OnboardingStep.SUMMARY_REMOTE,
        onBack = onBack,
        content = {
            Headline("选择线路并填写密钥", size = 19)
            Spacer(Modifier.height(7.dp))
            Body("默认用基础整理，只有开启在线总结时才需要密钥。")
            Spacer(Modifier.height(18.dp))
            SummaryProviderChips(selected = provider, onSelect = {
                providerName = it.name
                endpoint = it.endpoint
                model = it.defaults.firstOrNull().orEmpty()
                listed = emptyList()
                listState = ModelListState.Idle
                message = null
                savedOk = false
            })
            Spacer(Modifier.height(18.dp))
            if (provider == SummaryProvider.CUSTOM) {
                FieldLabel("完整接口地址")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    placeholder = { Text("https://服务地址/v1/chat/completions", fontSize = 13.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(12.dp))
            }
            FieldLabel("API 密钥")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
                placeholder = { Text(if (savedOk) "已保存，留空保留原值" else "粘贴 API Key", fontSize = 13.sp) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (provider != SummaryProvider.CUSTOM && provider.help.isNotBlank()) {
                TextButton(onClick = {
                    runCatching { uriHandler.openUri(provider.help) }
                        .onFailure { message = "无法打开浏览器，请到 ${provider.label} 平台创建密钥" }
                }) { Text("去 ${provider.label} 获取密钥 ↗", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(6.dp))
            FieldLabel("模型")
            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedButton(onClick = { modelMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(model.ifBlank { "选择模型" } + " ▾", fontSize = 13.sp, maxLines = 1)
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    candidates.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option, fontSize = 13.sp) },
                            onClick = { model = option; modelMenu = false },
                        )
                    }
                }
            }
            if (provider != SummaryProvider.CUSTOM) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listState.label(provider),
                        fontSize = 11.sp,
                        color = if (listState is ModelListState.Failed) OnboardingError else InkSoft,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(9.dp)).background(PaleGreen)
                            .clickable(enabled = listState !is ModelListState.Running && !busy) { refreshModels() }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(
                            if (listState is ModelListState.Running) "刷新中…" else "刷新",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Green,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("刷新只更新可选清单，不会改掉当前已选模型。", fontSize = 10.sp, color = InkSoft, lineHeight = 15.sp)
            }
            Spacer(Modifier.height(16.dp))
            ConsentRow(
                checked = consent,
                onCheckedChange = { consent = it },
                text = "我了解在线总结会把转写文字发送给提供商，并会产生费用。",
                enabled = !busy,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每天自动生成回顾", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink)
                    Text("当天录音整理完后自动跑一次", fontSize = 11.sp, color = InkSoft)
                }
                Switch(automatic, { automatic = it }, enabled = !busy)
            }
            InlineMessage(message, failed)
        },
        bottom = {
            if (!canSave && !busy) {
                Hint(
                    if (key.isBlank()) "填入密钥并勾选同意后可保存" else "勾选同意后可保存",
                    centered = true,
                )
                Spacer(Modifier.height(8.dp))
            }
            PrimaryAction(
                label = if (busy) "正在验证…" else "保存并验证",
                enabled = canSave,
                loading = busy,
                onClick = { saveAndTest() },
            )
            when {
                failed && savedOk -> SecondaryAction("仍然继续（未验证）", enabled = !busy, onClick = onDone)
                provider != SummaryProvider.CUSTOM -> SecondaryAction("先用基础整理", enabled = !busy, onClick = onBack)
                else -> SecondaryAction("返回上一步", enabled = !busy, onClick = onBack)
            }
        },
    )
}

/** 06 · 就绪：用内容替换表达成功，不弹窗；摘要卡直接读已保存的配置。 */
@Composable
internal fun ReadyStep(
    app: SonfolioApplication,
    onStartRecording: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val speech by app.transcriptionSettings.config.collectAsStateWithLifecycle()
    val summary by app.summarySettings.config.collectAsStateWithLifecycle()
    val result = remember(speech, summary) { readOnboardingResult(app) }
    OnboardingFrame(
        step = OnboardingStep.READY,
        onBack = null,
        content = {
            Spacer(Modifier.height(48.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(PaleGreen), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Green, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(20.dp))
                Text("已启用", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text("识别与总结都已保存并生效", fontSize = 13.sp, color = InkSoft, textAlign = TextAlign.Center)
                Spacer(Modifier.height(26.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardSurface)
                        .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ResultRow("识别方式", result.recognition)
                    ResultRow("模型", result.model)
                    ResultRow("音频去向", result.audio)
                    ResultRow("生效范围", result.scope)
                    ResultRow("总结方式", result.summary)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    if (result.localModelMissing) {
                        "模型尚未下载，录音与回听不受影响；下载完成后会自动转写。随时可在设置中更改。"
                    } else {
                        "只影响之后新转写的录音；已有文字不会重做。随时可在设置中更改。"
                    },
                    fontSize = 11.sp,
                    color = if (result.localModelMissing) AmberInk else InkSoft,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp,
                )
            }
        },
        bottom = {
            PrimaryAction(label = "开始记录", onClick = onStartRecording)
            SecondaryAction("去设置微调", onClick = onOpenSettings)
        },
    )
}

@Composable
private fun ResultRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(key, fontSize = 11.sp, color = InkSoft, lineHeight = 17.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Ink,
            lineHeight = 17.sp,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 线路 chip：两行两列，避免长标签在 390dp 宽度下被截断。 */
@Composable
private fun SummaryProviderChips(selected: SummaryProvider, onSelect: (SummaryProvider) -> Unit) {
    val entries = SummaryProvider.entries
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    ChipCell(
                        label = option.label.substringBefore(" · "),
                        active = option == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(option) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChipCell(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(38.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) Green else CardSurface)
            .border(1.dp, if (active) Green else Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) CardSurface else InkSoft,
            maxLines = 1,
        )
    }
}

private sealed interface ModelListState {
    data object Idle : ModelListState
    data object Running : ModelListState
    data class Success(val count: Int) : ModelListState
    data class Failed(val message: String) : ModelListState

    fun label(provider: SummaryProvider): String = when (this) {
        Idle -> "预设清单 · ${provider.defaults.size} 个内置模型"
        Running -> "正在向 ${provider.label} 获取模型列表…"
        is Success -> "已更新 · 官方返回 $count 个模型"
        is Failed -> "获取失败：$message · 已保留预设清单"
    }
}

private suspend fun saveSummaryMode(
    app: SonfolioApplication,
    mode: SummaryMode,
    localModelId: String?,
) = withContext(Dispatchers.IO) {
    app.summarySettings.save(
        mode = mode,
        endpoint = "",
        model = "",
        newKey = "",
        automatic = false,
        consent = false,
        localModelId = localModelId,
    )
    runCatching { app.summaryCoordinator.cancelAll() }
}

private suspend fun saveSummaryRemote(
    app: SonfolioApplication,
    provider: SummaryProvider,
    endpoint: String,
    model: String,
    key: String,
    consent: Boolean,
    automatic: Boolean,
): SummaryConfig = withContext(Dispatchers.IO) {
    app.summarySettings.save(
        mode = SummaryMode.REMOTE,
        endpoint = endpoint,
        model = model,
        newKey = key,
        automatic = automatic,
        consent = consent,
        localModelId = null,
    )
    runCatching { app.summaryCoordinator.cancelAll() }
    app.summarySettings.read()
}
