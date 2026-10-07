package com.gongfpp.sonfolio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** 只浏览与放弃草稿，不写真实配置、不触发下载或在线调用。 */
class SettingsExperienceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun choosingEarlierDayDoesNotHideLaterDays() {
        val today = java.time.LocalDate.now()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasContentDescription("统计与缺口"))
        ui.onNodeWithContentDescription("统计与缺口").performClick()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasContentDescription("回看 ${today.minusDays(2)}"))
        ui.onNodeWithContentDescription("回看 ${today.minusDays(2)}").performClick()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("最近两周"))
        ui.onNodeWithContentDescription("回看 $today").assertExists()
        ui.onNodeWithContentDescription("回看 ${today.minusDays(2)}").assertIsSelected()
        ui.onNodeWithText("回到今天").performClick()
        ui.onNodeWithContentDescription("回看 $today").assertIsSelected()
    }
    @Test fun settingsPagesAndSelectorsHaveConsistentReturnAndDiscard() {
        ui.onNodeWithText("设置").performClick()
        ui.onNodeWithText("转文字方式").performClick()
        ui.onNode(hasText("识别引擎：", substring = true)).performScrollTo().performClick()
        ui.onNodeWithText("选择本地识别引擎").assertIsDisplayed()
        ui.onNodeWithText("处理速度：5 / 5").assertExists()
        ui.onNodeWithText("识别准确度：3 / 5").assertExists()
        ui.onNodeWithText("关闭").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("后台运行").performScrollTo().performClick()
        ui.onNodeWithText("电池与后台").assertExists()
        ui.onNodeWithText("打开通知设置").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("用量记录").performScrollTo().performClick()
        ui.onNodeWithText("按模型").performScrollTo().performClick().assertIsSelected()
        ui.onNodeWithText("按功能").performClick().assertIsSelected()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("总结方式").performScrollTo().performClick()
        ui.onNodeWithText("在线总结").performScrollTo().performClick()
        ui.onNode(hasText("提供商：", substring = true)).performScrollTo().performClick()
        ui.onNodeWithText("选择总结提供商").assertIsDisplayed()
        ui.onNodeWithText("关闭").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("放弃尚未保存的更改？").assertIsDisplayed()
        ui.onNodeWithText("放弃更改").performClick()
        ui.onNodeWithText("转文字方式").assertExists()
    }
}
