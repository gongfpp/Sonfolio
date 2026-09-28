package com.gongfpp.sonfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreen(viewModel: SonfolioViewModel, onOpen: (AppScreen) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    // 输入防抖：逐字查询会在每个字符都打一次库，长列表/大库时明显卡顿。
    var settledQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query.isBlank()) settledQuery = query else { delay(220); settledQuery = query }
    }
    var dateRange by rememberSaveable { mutableStateOf(SearchDateRange.All) }
    var markedOnly by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun resetScroll() { scope.launch { listState.scrollToItem(0) } }
    val today = rememberCurrentDay()
    var visibleLimit by rememberSaveable(settledQuery, dateRange, markedOnly, today.toString()) { mutableIntStateOf(SEARCH_BATCH_SIZE) }
    val results by key(settledQuery, dateRange, markedOnly, today) {
        remember(settledQuery, dateRange, markedOnly, today, visibleLimit) {
            viewModel.observeSearch(settledQuery, dateRange, markedOnly, visibleLimit)
        }.collectAsStateWithLifecycle(initialValue = null)
    }
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = if (compact) 6.dp else 14.dp)) {
        Text("搜索记忆", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; resetScroll() },
            modifier = Modifier.fillMaxWidth().padding(top = if (compact) 6.dp else 14.dp),
            placeholder = { Text("搜索转写内容或对话主题") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; resetScroll() }) { Icon(Icons.Default.Close, contentDescription = "清空搜索") } },
            shape = RoundedCornerShape(11.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchDateRange.entries.forEach { range ->
                FilterChip(
                    selected = dateRange == range,
                    onClick = { dateRange = range; resetScroll() },
                    label = { Text(range.label, fontSize = 12.sp) },
                )
            }
            FilterChip(
                selected = markedOnly,
                onClick = { markedOnly = !markedOnly; resetScroll() },
                label = { Text("仅标记", fontSize = 12.sp) },
            )
        }
        SearchResultsPanel(
            settledQuery, dateRange, markedOnly, results, visibleLimit, listState,
            onLoadMore = { visibleLimit = (visibleLimit.toLong() + SEARCH_BATCH_SIZE).coerceAtMost(Int.MAX_VALUE - 1L).toInt() },
            onOpen = onOpen,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun SearchResultsPanel(
    query: String,
    dateRange: SearchDateRange,
    markedOnly: Boolean,
    results: SearchResults?,
    visibleLimit: Int,
    listState: LazyListState,
    onLoadMore: () -> Unit,
    onOpen: (AppScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            when {
                results?.errorMessage != null -> results.errorMessage
                results == null -> "正在搜索…"
                query.isBlank() -> "最新 ${results.hits.size} 场对话${if (results.hasMore) " · 还有更多" else ""}"
                results.hasMore -> "已显示 ${results.hits.size} 场相关对话 · 还有更多"
                else -> "找到 ${results.hits.size} 场相关对话"
            }, color = InkSoft, fontSize = 13.sp,
        )
        val hits = results?.hits
        if (results?.errorMessage != null) return@Column
        if (hits != null && hits.isEmpty()) {
            Text(
                if (query.isBlank()) "此条件下没有已整理的内容。" else "没有找到包含“$query”的对话。",
                modifier = Modifier.padding(top = 20.dp),
                color = InkSoft,
                fontSize = 13.sp,
            )
        } else if (hits != null) {
            LazyColumn(Modifier.weight(1f).testTag("search-results"), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
                items(hits, key = { "hit:${it.conversationId}" }) { hit ->
                    SearchResult(
                        date = formatDateTime(hit.snippetStartedAtMillis).substringBefore(' '),
                        title = if (hit.isMarked) "★ ${hit.title}" else hit.title,
                        excerpt = hit.snippetText,
                        trailing = formatClock(hit.snippetStartedAtMillis),
                        meta = when {
                            query.isBlank() -> null
                            hit.titleHit -> "标题命中"
                            else -> "命中 ${hit.hitCount} 句"
                        },
                        query = query,
                    ) {
                        onOpen(AppScreen.Conversation(id = hit.conversationId, transcriptId = hit.snippetTranscriptId, query = query))
                    }
                }
                if (results.hasMore) {
                    item(key = "load-more") {
                        TextButton(
                            onClick = onLoadMore,
                            enabled = results.requestedLimit >= visibleLimit,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            Text(if (results.requestedLimit < visibleLimit) "正在加载…" else "加载更多")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchResult(date: String, title: String, excerpt: String, trailing: String, meta: String? = null, query: String? = null, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onClick), RoundedCornerShape(14.dp), color = CardSurface, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, color = InkSoft, fontSize = 13.sp)
                Text(title, modifier = Modifier.padding(start = 8.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(trailing, color = InkSoft, fontSize = 11.sp)
            }
            if (meta != null) {
                Text(
                    meta,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(999.dp)).background(PaleGreen).padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Green, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                )
            }
            Text(highlightText(excerpt, query), modifier = Modifier.padding(top = 9.dp), color = Ink, fontSize = 12.5.sp, lineHeight = 19.sp)
        }
    }
}
