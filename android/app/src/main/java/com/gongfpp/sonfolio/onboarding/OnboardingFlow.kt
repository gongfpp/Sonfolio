package com.gongfpp.sonfolio.onboarding

import com.gongfpp.sonfolio.SonfolioApplication
import com.gongfpp.sonfolio.models.ModelCatalog
import com.gongfpp.sonfolio.processing.TranscriptionMode
import com.gongfpp.sonfolio.summary.SummaryMode
import com.gongfpp.sonfolio.summary.SummaryProvider
import java.io.File

/**
 * 首次启动引导的步骤。识别与总结是两个独立决策，因此顺序固定为
 * 决策 → 识别方式 → 总结方式 → 就绪；两条识别分支在同一状态机里替换内容，不新开路由。
 */
internal enum class OnboardingStep(val title: String?) {
    DECISION(null),
    LOCAL_ENGINE("在手机上识别"),
    REMOTE_SPEECH("在线识别"),
    SUMMARY_MODE("总结方式"),
    SUMMARY_REMOTE("在线总结"),
    READY(null),
}

/** 引导结束后的去向；由宿主决定是否立即申请麦克风权限或跳到设置。 */
internal sealed interface OnboardingExit {
    data object StartRecording : OnboardingExit
    data object OpenSettings : OnboardingExit
    /** 只完成配置，先停在首页，不主动录音。 */
    data object Finish : OnboardingExit
    data object Deferred : OnboardingExit
}

internal const val ONBOARDING_DEFER_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

/**
 * 是否展示引导的纯判定，单独抽出来固定产品规则：
 * 完成后永不拦截；7 天内选过“稍后”不拦截；安装里已有录音或已配置识别方式时视为老用户。
 * 时钟回拨不重复拦截（时间差为负时按“仍在窗口内”处理）。
 */
internal fun shouldShowOnboarding(
    completed: Boolean,
    deferredAt: Long,
    hasHistory: Boolean,
    now: Long,
    deferWindowMillis: Long = ONBOARDING_DEFER_WINDOW_MILLIS,
): Boolean {
    if (completed) return false
    if (deferredAt > 0 && now - deferredAt < deferWindowMillis) return false
    return !hasHistory
}

/**
 * 是否拦截主界面并展示引导。
 *
 * 判定顺序：已完成 → 不展示；7 天内选过“稍后”→ 不展示；
 * 已有录音或已配置识别方式的安装（多为覆盖升级）→ 视为老用户，自动标记完成。
 */
internal object OnboardingGate {
    fun isPending(app: SonfolioApplication, now: Long = System.currentTimeMillis()): Boolean {
        val preferences = app.preferences
        // 已完成与“稍后”窗口内都不需要触碰文件系统。
        val notBlocked = shouldShowOnboarding(
            completed = preferences.onboardingCompleted,
            deferredAt = preferences.onboardingDeferredAt,
            hasHistory = false,
            now = now,
        )
        if (!notBlocked) return false
        val history = hasHistory(app)
        // 老用户不该被引导页打断；标记失败也不影响本次不展示。
        if (history) runCatching { preferences.completeOnboarding() }
        return !history
    }

    /** 只做文件名级检查，不哈希模型，避免在主线程读取上百 MB 的权重。 */
    private fun hasHistory(app: SonfolioApplication): Boolean {
        val recordings = File(app.filesDir, "recordings")
        if (recordings.list()?.isNotEmpty() == true) return true
        val config = app.transcriptionSettings.read()
        if (config.hasKey) return true
        val engine = ModelCatalog.byId(config.localEngine.artifactId) ?: return false
        return ModelCatalog.available(app.filesDir, engine)
    }
}

/** 就绪页的四行事实，全部从已保存的配置读取，不在界面层重算。 */
internal data class OnboardingResult(
    val recognition: String,
    val model: String,
    val audio: String,
    val scope: String,
    val summary: String,
    /** 选了本地识别但模型尚未落盘；就绪页必须说清“暂不转文字”，不能假装已生效。 */
    val localModelMissing: Boolean = false,
)

internal fun readOnboardingResult(app: SonfolioApplication): OnboardingResult {
    val speech = app.transcriptionSettings.read()
    val summary = app.summarySettings.read()
    val engineArtifact = ModelCatalog.byId(speech.localEngine.artifactId)
    // 只比对文件大小，不哈希；引导页在主线程读一次。
    val localReady = engineArtifact != null && ModelCatalog.available(app.filesDir, engineArtifact)
    val localMissing = speech.mode == TranscriptionMode.LOCAL && !localReady
    return OnboardingResult(
        recognition = when (speech.mode) {
            TranscriptionMode.LOCAL -> "手机本地运行"
            TranscriptionMode.REMOTE -> speech.provider.label
        },
        model = when (speech.mode) {
            TranscriptionMode.LOCAL -> buildString {
                append(speech.localEngine.displayName)
                when {
                    localMissing -> append(" · 尚未下载")
                    // 自定义 ONNX 模型没有内置体积，只显示名称。
                    speech.customAsrId == null && engineArtifact != null ->
                        append(" · ").append(com.gongfpp.sonfolio.formatModelSize(engineArtifact.bytes))
                }
            }
            TranscriptionMode.REMOTE -> speech.provider.modelLabel(speech.model)
        },
        audio = when (speech.mode) {
            TranscriptionMode.LOCAL -> if (localMissing) "只留在这台手机 · 暂不转文字" else "只留在这台手机"
            TranscriptionMode.REMOTE -> "人声片段会发到 ${speech.provider.label}"
        },
        scope = "之后新转写的录音",
        summary = when (summary.mode) {
            SummaryMode.BASIC -> "基础整理 · 只摘原句"
            SummaryMode.LOCAL -> "本地模型 · ${summary.localLabel.ifBlank { "已下载模型" }}"
            SummaryMode.REMOTE -> "在线 · ${summary.model.ifBlank { SummaryProvider.DEEPSEEK.defaults.first() }}"
        },
        localModelMissing = localMissing,
    )
}
