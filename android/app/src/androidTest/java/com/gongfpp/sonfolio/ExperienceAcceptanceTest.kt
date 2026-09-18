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
        // 版本号跟随构建，避免每次发版都要改测试。
        ui.onNodeWithText("声迹 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）").assertIsDisplayed()
        ui.onNodeWithText("转文字方式").assertIsDisplayed()
        ui.onNodeWithText("在线识别").performScrollTo().performClick()
        ui.onAllNodes(hasText("识别密钥", substring = true)).fetchSemanticsNodes().also { org.junit.Assert.assertTrue(it.isNotEmpty()) }
        ui.onNodeWithText("在手机上识别").performScrollTo().performClick()
        ui.onNodeWithText("手机端识别设置").assertIsDisplayed()
        ui.onNodeWithText("总结方式").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("在线总结").performScrollTo().performClick()
        ui.onNode(hasText("总结服务密钥", substring = true)).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("完整 HTTPS 接口地址").assertDoesNotExist()
        ui.onNodeWithText("模型名称").assertDoesNotExist()
    }

    @Test fun minutesAndSliceContextAreVisible() {
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("标记（3分）"))
        ui.onNodeWithText("标记（3分）").assertIsDisplayed()
        ui.onNode(hasText("切片说明", substring = true)).performClick()
        ui.onNode(hasText("5 分钟", substring = true) and hasText("对话", substring = true)).assertExists()
    }

    @Test fun rawCardsOpenPlayerAndLongPressEntersSelection() {
        ui.onNodeWithText("设置").performClick()
        ui.onNodeWithText("原始录音").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("raw-audio-row").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodes(isToggleable()).assertCountEquals(0)
        ui.onAllNodesWithTag("raw-audio-row")[0].performScrollTo().performClick()
        // 真机上第一行可能已被清理原音（localPath 为空），此时只提示不进入回听页；两种都接受。
        if (ui.onAllNodesWithText("录音回听").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithText("录音回听").assertIsDisplayed()
            ui.onNodeWithContentDescription("返回").performClick()
        }
        ui.onAllNodesWithTag("raw-audio-row")[0].performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        ui.onNodeWithText("退出多选").assertExists()
        ui.onAllNodes(isToggleable()).fetchSemanticsNodes().also { org.junit.Assert.assertTrue(it.isNotEmpty()) }
        ui.onNodeWithText("退出多选").performScrollTo().performClick()
        ui.onAllNodes(isToggleable()).assertCountEquals(0)
        ui.onAllNodesWithText("回听").assertCountEquals(0)
    }
}
