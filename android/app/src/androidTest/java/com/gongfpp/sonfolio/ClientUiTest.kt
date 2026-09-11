package com.gongfpp.sonfolio

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClientUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun searchSettingsAndRawAudioRoutesWork() {
        // 使用应用自身的可访问性动作验证交互，不依赖厂商限制的跨应用注入。
        ui.onNodeWithContentDescription("搜索").performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.onNodeWithText("搜索记忆").assertIsDisplayed()
        ui.onNodeWithText("输入文字后搜索本地转写").assertIsDisplayed()
        ui.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("qa-no-such-text-491708")) }
        ui.waitForIdle()
        ui.onNodeWithText("找到 0 条相关内容").assertIsDisplayed()
        ui.onNodeWithContentDescription("清空搜索").performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.onNodeWithText("输入文字后搜索本地转写").assertIsDisplayed()
        ui.onNodeWithContentDescription("设置").performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.onNodeWithText("录音与存储").assertIsDisplayed()
        ui.onNodeWithText("原始录音").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.onNodeWithText("原始录音").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performSemanticsAction(SemanticsActions.OnClick) { it() }
        ui.onNodeWithText("录音与存储").assertIsDisplayed()
    }
}
