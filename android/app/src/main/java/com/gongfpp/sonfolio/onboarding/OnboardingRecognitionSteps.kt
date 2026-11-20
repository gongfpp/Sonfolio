package com.gongfpp.sonfolio.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gongfpp.sonfolio.CardSurface
import com.gongfpp.sonfolio.Green
import com.gongfpp.sonfolio.InkSoft
import com.gongfpp.sonfolio.Line
import com.gongfpp.sonfolio.SonfolioApplication
import com.gongfpp.sonfolio.formatModelSize
import com.gongfpp.sonfolio.models.ModelArtifact
import com.gongfpp.sonfolio.models.ModelCatalog
import com.gongfpp.sonfolio.processing.LocalAsrEngine
import com.gongfpp.sonfolio.processing.RemoteSpeechTransport
import com.gongfpp.sonfolio.processing.SpeechProvider
import com.gongfpp.sonfolio.processing.TranscriptionConfig
import com.gongfpp.sonfolio.processing.TranscriptionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 01 · 决策：两张互斥卡 + 一个同级的“先不设置”出口，不做启动门禁。 */
@Composable
internal fun DecisionStep(
    selected: OnboardingStep,
    onSelect: (OnboardingStep) -> Unit,
    onContinue: () -> Unit,
    onDefer: () -> Unit,
) {
    val smallestModel = ModelCatalog.speechModels.minOfOrNull { it.bytes }
    OnboardingFrame(
        step = OnboardingStep.DECISION,
        onBack = null,
        content = {
            BrandRow()
            Spacer(Modifier.height(38.dp))
            Headline("先选一种转写方式")
            Spacer(Modifier.height(12.dp))
            Body("录音现在就能开始，之后随时能在设置里更换。")
            Spacer(Modifier.height(28.dp))
            ChoiceCard(
                selected = selected == OnboardingStep.LOCAL_ENGINE,
                title = "在手机上识别",
                description = "离线运行，录音不出手机",
                meta = (smallestModel?.let { "${formatModelSize(it)} 起" } ?: "需下载模型") + " · 一次下载，之后离线可用",
                onClick = { onSelect(OnboardingStep.LOCAL_ENGINE) },
                badge = "推荐",
            )
            Spacer(Modifier.height(14.dp))
            ChoiceCard(
                selected = selected == OnboardingStep.REMOTE_SPEECH,
                title = "在线识别",
                description = "不下载模型，只上传需要识别的人声片段",
                meta = "按量计费 · 需自备密钥 · 断网不可用",
                onClick = { onSelect(OnboardingStep.REMOTE_SPEECH) },
            )
            Spacer(Modifier.height(22.dp))
            AmberNote("音频默认留在本机；只有在线识别才会上传人声片段。")
        },
        bottom = {
            PrimaryAction(
                label = if (selected == OnboardingStep.REMOTE_SPEECH) "继续 · 在线识别" else "继续 · 在手机上识别",
                onClick = onContinue,
            )
            SecondaryAction("先不设置，直接开始记录", onClick = onDefer)
        },
    )
}

/** 02 · 本地识别：选引擎 → 下载并校验 → 自动写入配置。 */
@Composable
internal fun LocalEngineStep(
    app: SonfolioApplication,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    var engineName by rememberSaveable { mutableStateOf(app.transcriptionSettings.read().localEngine.name) }
    val engine = LocalAsrEngine.fromName(engineName)
    val model = ModelCatalog.byId(engine.artifactId) ?: ModelCatalog.senseVoice
    val download = rememberModelDownload(model)
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<ModelArtifact?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    // 下载由引导页发起时，完成后直接启用；手动重进该页不自动改动已保存的配置。
    var enableWhenReady by rememberSaveable { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun enable() {
        if (busy || finished) return
        busy = true
        failed = false
        scope.launch {
            try {
                val label = withContext(Dispatchers.IO) { enableLocalAsr(app, engine) }
                finished = true
                message = label
                onDone()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                message = error.message ?: "启用失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(download.installed, enableWhenReady) {
        if (enableWhenReady && download.installed == true && !finished) enable()
    }

    OnboardingFrame(
        step = OnboardingStep.LOCAL_ENGINE,
        onBack = onBack,
        content = {
            StageBars(active = 1)
            Spacer(Modifier.height(22.dp))
            Headline("选择模型", size = 19)
            Spacer(Modifier.height(7.dp))
            Body("下载后可离线使用；切换只影响之后新转写的录音。")
            Spacer(Modifier.height(18.dp))
            LocalAsrEngine.entries.forEach { option ->
                val artifact = ModelCatalog.byId(option.artifactId)
                ChoiceCard(
                    selected = engine == option,
                    title = option.displayName,
                    description = option.blurb,
                    meta = listOfNotNull(
                        artifact?.let { formatModelSize(it.bytes) },
                        "速度 ${option.speedStars}/5",
                        "准确度 ${option.accuracyStars}/5",
                        if (option.supportsHotwords) "支持热词" else null,
                    ).joinToString(" · "),
                    onClick = { engineName = option.name; message = null },
                    badge = if (option.recommended) "推荐" else null,
                    enabled = !busy && !download.running,
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(6.dp))
            if (download.running) {
                Text(downloadSummaryText(download), fontSize = 11.sp, color = InkSoft)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { download.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { download.cancel(); enableWhenReady = false }) {
                    Text("取消下载（保留已下载部分）")
                }
            }
            download.message?.takeIf { !download.running && download.installed != true }?.let {
                Text(it, fontSize = 11.sp, color = if (download.failed) OnboardingError else InkSoft)
                Spacer(Modifier.height(6.dp))
            }
            AmberNote("还没下载也能先录音，下载完成后回来启用即可。")
            InlineMessage(message, failed)
        },
        bottom = {
            when {
                download.running -> {
                    PrimaryAction(label = "正在下载…", enabled = false, loading = true, onClick = {})
                    Hint("可以留在这一页等待，也可以稍后回到设置继续。", centered = true)
                }
                download.installed == true -> PrimaryAction(
                    label = "启用 ${engine.displayName}",
                    enabled = !busy,
                    loading = busy,
                    onClick = { enable() },
                )
                download.installed == null -> PrimaryAction(label = "正在检查模型…", enabled = false, onClick = {})
                else -> PrimaryAction(
                    label = "下载并启用 · ${formatModelSize(model.bytes)}",
                    enabled = !busy,
                    onClick = { confirm = model },
                )
            }
            if (download.installed != true) {
                SecondaryAction("稍后再说", enabled = !busy && !download.running, onClick = onDone)
            }
        },
    )

    confirm?.let { target ->
        ModelDownloadDialog(
            model = target,
            onDismiss = { confirm = null },
            onConfirm = { wifiOnly ->
                confirm = null
                enableWhenReady = true
                message = null
                download.start(wifiOnly)
            },
        )
    }
}

/** 03 · 在线识别：厂商 + 密钥 + 上传同意，保存后立刻用 1 秒静音验证鉴权。 */
@Composable
internal fun RemoteSpeechStep(
    app: SonfolioApplication,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    var providerName by rememberSaveable { mutableStateOf(app.transcriptionSettings.read().provider.name) }
    val provider = runCatching { SpeechProvider.valueOf(providerName) }.getOrDefault(SpeechProvider.QWEN)
    var key by rememberSaveable { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    // 保存成功但验证失败时，允许用户带着“未验证”继续。
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val canSave = key.isNotBlank() && consent && !busy

    fun saveAndTest() {
        if (busy) return
        busy = true
        failed = false
        message = "正在保存并验证…"
        scope.launch {
            try {
                val config = withContext(Dispatchers.IO) { saveRemoteAsr(app, provider, key, consent) }
                saved = true
                val result = withContext(Dispatchers.IO) {
                    RemoteSpeechTransport(app.transcriptionSettings, app.usageStore)
                        .test(config, "onboarding-speech", System.currentTimeMillis())
                }
                message = result
                key = ""
                onDone()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                message = (error.message ?: "验证失败，请检查网络或密钥") +
                    if (saved) "（配置已保存，可稍后重试验证）" else ""
            } finally {
                busy = false
            }
        }
    }

    OnboardingFrame(
        step = OnboardingStep.REMOTE_SPEECH,
        onBack = onBack,
        content = {
            Headline("选择提供商并填写密钥", size = 19)
            Spacer(Modifier.height(7.dp))
            Body("密钥只保存在本机，不会随音频上传。")
            Spacer(Modifier.height(20.dp))
            SpeechProviderChips(selected = provider, onSelect = { providerName = it.name; message = null; saved = false })
            Spacer(Modifier.height(20.dp))
            FieldLabel("API 密钥")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
                placeholder = { Text("粘贴 API Key", fontSize = 13.sp) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = {
                runCatching { uriHandler.openUri(provider.keyPage) }
                    .onFailure { message = "无法打开浏览器，请到 ${provider.label} 控制台创建密钥" }
            }) { Text("去控制台获取密钥 ↗", fontSize = 12.sp) }
            Spacer(Modifier.height(14.dp))
            ConsentRow(
                checked = consent,
                onCheckedChange = { consent = it },
                text = "我了解在线识别只上传人声片段、历史录音不会自动上传，并会产生费用。",
                enabled = !busy,
            )
            InlineMessage(message, failed)
        },
        bottom = {
            if (!canSave && !busy) {
                Hint("填入密钥并勾选同意后可保存", centered = true)
                Spacer(Modifier.height(8.dp))
            }
            PrimaryAction(
                label = if (busy) "正在验证…" else "保存并验证",
                enabled = canSave,
                loading = busy,
                onClick = { saveAndTest() },
            )
            if (failed && saved) {
                SecondaryAction("仍然继续（未验证）", enabled = !busy, onClick = onDone)
            } else {
                SecondaryAction("改用本地识别", enabled = !busy, onClick = onBack)
            }
        },
    )
}

/** 厂商 chip 行；三个提供商在 390dp 宽度下可一行排下，标签取 “·” 前的短名。 */
@Composable
internal fun SpeechProviderChips(selected: SpeechProvider, onSelect: (SpeechProvider) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpeechProvider.entries.forEach { option ->
            val active = option == selected
            Box(
                Modifier.weight(1f).height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Green else CardSurface)
                    .border(1.dp, if (active) Green else Line, RoundedCornerShape(10.dp))
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label.substringBefore(" · "),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (active) CardSurface else InkSoft,
                    maxLines = 1,
                )
            }
        }
    }
}

private suspend fun enableLocalAsr(app: SonfolioApplication, engine: LocalAsrEngine): String = withContext(Dispatchers.IO) {
    val current = app.transcriptionSettings.read()
    app.transcriptionSettings.save(
        mode = TranscriptionMode.LOCAL,
        provider = current.provider,
        model = current.model.takeIf { it in current.provider.models } ?: current.provider.models.first(),
        key = "",
        consent = false,
        localEngine = engine,
    )
    runCatching { app.recordingRepository.enqueuePendingAsr() }
    "已启用 ${engine.displayName}；之后新转写的录音会用它识别"
}

private suspend fun saveRemoteAsr(
    app: SonfolioApplication,
    provider: SpeechProvider,
    key: String,
    consent: Boolean,
): TranscriptionConfig = withContext(Dispatchers.IO) {
    app.transcriptionSettings.save(
        mode = TranscriptionMode.REMOTE,
        provider = provider,
        model = provider.models.first(),
        key = key,
        consent = consent,
    )
    runCatching { app.recordingRepository.enqueuePendingAsr() }
    app.transcriptionSettings.read()
}
