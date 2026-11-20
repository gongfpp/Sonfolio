package com.gongfpp.sonfolio.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.gongfpp.sonfolio.SonfolioApplication

private val StepSaver: Saver<OnboardingStep, String> = Saver(
    save = { it.name },
    restore = { name -> OnboardingStep.entries.firstOrNull { it.name == name } ?: OnboardingStep.DECISION },
)

/**
 * 首次启动引导。位于主 Scaffold 之外，不显示底部导航；所有子步骤是同一状态机里的 stage，
 * 因此返回路径确定：子步骤回到决策页，决策页返回即“稍后”。
 *
 * 方案里刻意把识别与总结拆成两个决策：录音、回听和基础整理都不依赖模型，
 * 引导页不能变成启动门禁，只能是一次性的清晰选择。
 */
@Composable
internal fun OnboardingScreen(app: SonfolioApplication, onExit: (OnboardingExit) -> Unit) {
    var step by rememberSaveable(stateSaver = StepSaver) { mutableStateOf(OnboardingStep.DECISION) }
    var decision by rememberSaveable(stateSaver = StepSaver) { mutableStateOf(OnboardingStep.LOCAL_ENGINE) }

    val complete: (OnboardingExit) -> Unit = { exit ->
        runCatching { app.preferences.completeOnboarding() }
        onExit(exit)
    }
    val defer: () -> Unit = {
        app.preferences.deferOnboarding()
        onExit(OnboardingExit.Deferred)
    }

    BackHandler(enabled = true) {
        when (step) {
            OnboardingStep.DECISION -> defer()
            OnboardingStep.LOCAL_ENGINE, OnboardingStep.REMOTE_SPEECH -> step = OnboardingStep.DECISION
            OnboardingStep.SUMMARY_MODE -> step = decision
            OnboardingStep.SUMMARY_REMOTE -> step = OnboardingStep.SUMMARY_MODE
            // 就绪页返回等于完成配置但先不录音，不重放之前的步骤。
            OnboardingStep.READY -> complete(OnboardingExit.Finish)
        }
    }

    when (step) {
        OnboardingStep.DECISION -> DecisionStep(
            selected = decision,
            onSelect = { decision = it },
            onContinue = { step = decision },
            onDefer = defer,
        )
        OnboardingStep.LOCAL_ENGINE -> LocalEngineStep(
            app = app,
            onDone = { step = OnboardingStep.SUMMARY_MODE },
            onBack = { step = OnboardingStep.DECISION },
        )
        OnboardingStep.REMOTE_SPEECH -> RemoteSpeechStep(
            app = app,
            onDone = { step = OnboardingStep.SUMMARY_MODE },
            onBack = { step = OnboardingStep.DECISION },
        )
        OnboardingStep.SUMMARY_MODE -> SummaryModeStep(
            app = app,
            onDone = { step = OnboardingStep.READY },
            onRemote = { step = OnboardingStep.SUMMARY_REMOTE },
            onBack = { step = decision },
        )
        OnboardingStep.SUMMARY_REMOTE -> SummaryRemoteStep(
            app = app,
            onDone = { step = OnboardingStep.READY },
            onBack = { step = OnboardingStep.SUMMARY_MODE },
        )
        OnboardingStep.READY -> ReadyStep(
            app = app,
            onStartRecording = { complete(OnboardingExit.StartRecording) },
            onOpenSettings = { complete(OnboardingExit.OpenSettings) },
        )
    }
}
