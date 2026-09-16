package com.gongfpp.sonfolio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchResultsUiTest {
    @get:Rule val ui = createComposeRule()

    private fun hit(index: Int) = SearchHit(
        conversationId = "c-$index",
        title = "测试标题$index",
        snippetTranscriptId = "t-$index",
        snippetStartedAtMillis = index * 1_000L,
        snippetText = "测试内容$index",
        hitCount = 1,
        titleHit = false,
        latestHitMillis = index * 1_000L,
    )

    @Test fun loadsPastFirstHundredWithoutJumpingToTopAndOpensExactHit() {
        val hits = (1..105).map(::hit)
        val limit = mutableStateOf(100)
        val results = mutableStateOf(SearchResults(hits.take(100), hasMore = true))
        var opened: AppScreen? = null
        ui.setContent {
            MaterialTheme {
                SearchResultsPanel("测试", SearchDateRange.All, false, results.value, limit.value, rememberLazyListState(),
                    onLoadMore = { limit.value = 200 }, onOpen = { opened = it }, modifier = Modifier.fillMaxSize())
            }
        }
        ui.onNodeWithText("已显示 100 场相关对话 · 还有更多").assertIsDisplayed()
        ui.onNodeWithTag("search-results").performScrollToNode(hasText("加载更多"))
        ui.onNodeWithText("加载更多").performClick()
        ui.onNodeWithText("正在加载…").assertIsNotEnabled()
        ui.runOnIdle { results.value = SearchResults(hits, requestedLimit = 200) }
        ui.onNodeWithText("找到 105 场相关对话").assertIsDisplayed()
        ui.onNodeWithText("测试标题1").assertDoesNotExist()
        ui.onNodeWithTag("search-results").performScrollToNode(hasText("测试标题105"))
        ui.onNodeWithText("测试标题105").performClick()
        assertEquals(AppScreen.Conversation("c-105", "t-105", "测试"), opened)
        ui.onNodeWithText("加载更多").assertDoesNotExist()
    }

    @Test fun blankQueryListsLatestConversations() {
        val hits = (1..3).map(::hit)
        ui.setContent {
            MaterialTheme {
                SearchResultsPanel("", SearchDateRange.All, false, SearchResults(hits), 100, rememberLazyListState(),
                    onLoadMore = {}, onOpen = {}, modifier = Modifier.fillMaxSize())
            }
        }
        ui.onNodeWithText("最新 3 场对话").assertIsDisplayed()
    }
}
