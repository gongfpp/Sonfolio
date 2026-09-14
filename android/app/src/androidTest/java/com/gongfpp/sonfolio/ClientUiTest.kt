package com.gongfpp.sonfolio

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class ClientUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun searchSettingsAndRawAudioRoutesWork() {
        // 使用应用自身的可访问性动作验证交互，不依赖厂商限制的跨应用注入。
        ui.onNodeWithText("搜索").performClick()
        ui.onNodeWithText("搜索记忆").assertIsDisplayed()
        ui.onNodeWithText("输入文字后搜索本地转写").assertIsDisplayed()
        ui.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("qa-no-such-text-491708")) }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("找到 0 条相关内容").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("找到 0 条相关内容").assertIsDisplayed()
        ui.onNodeWithContentDescription("清空搜索").performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.onNodeWithText("输入文字后搜索本地转写").assertIsDisplayed()
        ui.onNodeWithText("设置").performClick()
        ui.onNodeWithText("录音与存储").assertIsDisplayed()
        ui.onNodeWithText("原始录音").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.onNodeWithText("原始录音").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performSemanticsAction(SemanticsActions.OnClick) { it() }
        // Returning preserves the settings scroll position, so the top heading may be offscreen.
        ui.onNodeWithText("录音与存储").assertExists()
    }

    @Test fun searchQueryAndFilterSurviveTabSwitchAndActivityRecreation() {
        val query = "qa-return-state-491708"
        ui.onNodeWithText("搜索").performClick()
        ui.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(query)) }
        ui.onNodeWithText("仅标记").performClick()
        ui.onNodeWithText("设置").performClick()
        ui.onNodeWithText("搜索").performClick()
        ui.onNode(hasSetTextAction()).assertTextEquals(query)
        ui.onNodeWithText("仅标记").assertIsSelected()
        ui.activityRule.scenario.recreate()
        ui.onNode(hasSetTextAction()).assertTextEquals(query)
        ui.onNodeWithText("仅标记").assertIsSelected()
    }

    @Test fun selectedDayFlowsToRawAudioAndJournalAndBack() {
        val date = LocalDate.now().minusDays(1)
        val label = date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasContentDescription("前一天"))
        ui.onNodeWithContentDescription("前一天").performClick()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("查看当日原始录音"))
        ui.onNodeWithText("查看当日原始录音").performClick()
        ui.onNodeWithText("查看全部录音").assertIsDisplayed()
        ui.onNodeWithText("查看全部录音").performClick()
        ui.onNodeWithText("仅看 $date").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText(label))
        ui.onNodeWithText(label).assertIsDisplayed()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("一日回顾"))
        ui.onNodeWithText("一日回顾").performClick()
        ui.onNodeWithText(date.toString()).assertIsDisplayed()
        ui.onNodeWithText(label).assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("timeline-list").assertExists()
        ui.onNodeWithText("一日回顾").assertIsDisplayed()
    }
}
