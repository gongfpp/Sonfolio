package com.gongfpp.sonfolio

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only on the TCP-connected physical phone. Does not save settings or delete user audio. */
@RunWith(AndroidJUnit4::class)
class ExperienceAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun settingsExposeModesDownloadsAndProviderKeyLinks() {
        ui.onNodeWithText("设置").performClick()
        ui.onNodeWithText("声迹 0.2.0（10）").assertIsDisplayed()
        ui.onNodeWithText("转文字方式").assertIsDisplayed()
        ui.onNodeWithText("外部 API 识别").performScrollTo().performClick()
        ui.onNodeWithText("识别提供商：通义千问 · 中国内地 ▾").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("获取 通义千问 · 中国内地 API Key ↗").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("手机本地识别").performScrollTo().performClick()
        ui.onNodeWithText("手机本地 AI").performScrollTo().performClick()
        ui.onNodeWithText("下载使用 GGUF 模型").performScrollTo().performClick()
        ui.onNodeWithText("下载 491 MB 模型？").assertIsDisplayed()
        ui.onNodeWithText("取消").performClick()
        ui.onNodeWithText("外部 API").performScrollTo().performClick()
        ui.onNodeWithText("提供商：DeepSeek ▾").performScrollTo().performClick()
        ui.onNodeWithText("通义千问 · 中国内地", useUnmergedTree = true).performClick()
        ui.onNodeWithText("模型：qwen-plus ▾").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("获取 通义千问 · 中国内地 API Key ↗").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("完整 HTTPS 接口地址").assertDoesNotExist()
        ui.onNodeWithText("模型名称").assertDoesNotExist()
    }

    @Test fun minutesAndSliceContextAreVisible() {
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("标记（3分）"))
        ui.onNodeWithText("标记（3分）").assertIsDisplayed()
        ui.onNodeWithText("每 5 分钟保存一份原音 · 切片说明 ⓘ").performClick()
        ui.onNode(hasText("5 分钟", substring = true) and hasText("对话", substring = true)).assertExists()
    }

    @Test fun rawCardsOpenPlayerAndLongPressEntersSelection() {
        ui.onNodeWithText("设置").performClick()
        ui.onNodeWithText("原始录音").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("raw-audio-row").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodes(isToggleable()).assertCountEquals(0)
        ui.onAllNodesWithTag("raw-audio-row")[0].performScrollTo().performClick()
        ui.onNodeWithText("原音回听").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onAllNodesWithTag("raw-audio-row")[0].performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        ui.onNodeWithText("退出多选").assertExists()
        ui.onAllNodes(isToggleable()).fetchSemanticsNodes().also { org.junit.Assert.assertTrue(it.isNotEmpty()) }
        ui.onNodeWithText("退出多选").performScrollTo().performClick()
        ui.onAllNodes(isToggleable()).assertCountEquals(0)
        ui.onAllNodesWithText("回听").assertCountEquals(0)
    }
}
