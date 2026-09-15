package com.gongfpp.sonfolio

import java.time.LocalDate

internal sealed interface AppScreen {
    data object Today : AppScreen
    data object Search : AppScreen
    data object Settings : AppScreen
    data class Daily(val date: String = LocalDate.now().toString()) : AppScreen
    data class RawRecordings(val date: String? = null) : AppScreen
    /** 详情页只按真实会话 ID 打开；id 由时间线或搜索结果提供。 */
    data class Conversation(
        val id: String,
        val transcriptId: String? = null,
        val query: String? = null,
    ) : AppScreen
}

internal val AppScreen.isMainScreen: Boolean
    get() = this == AppScreen.Today || this == AppScreen.Search || this == AppScreen.Settings

internal fun AppScreen.toSavedRoute(): String = when (this) {
    AppScreen.Today -> "today"
    AppScreen.Search -> "search"
    AppScreen.Settings -> "settings"
    is AppScreen.Daily -> "daily:$date"
    is AppScreen.RawRecordings -> date?.let { "raw-recordings:$it" } ?: "raw-recordings"
    is AppScreen.Conversation -> buildString {
        append("conversation-id:")
        append(id)
        if (transcriptId != null) append("|").append(transcriptId)
        if (query != null) append("^").append(query)
    }
}

private fun validDate(value: String): String? = runCatching { LocalDate.parse(value).toString() }.getOrNull()

internal fun appScreenFromSavedRoute(route: String): AppScreen = when {
    route == "today" -> AppScreen.Today
    route == "search" -> AppScreen.Search
    route == "settings" -> AppScreen.Settings
    route == "daily" -> AppScreen.Daily()
    route.startsWith("daily:") -> AppScreen.Daily(validDate(route.removePrefix("daily:")) ?: LocalDate.now().toString())
    route == "raw-recordings" -> AppScreen.RawRecordings()
    route.startsWith("raw-recordings:") -> AppScreen.RawRecordings(validDate(route.removePrefix("raw-recordings:")))
    route.startsWith("conversation-id:") -> {
        val body = route.removePrefix("conversation-id:")
        val bar = body.indexOf('|')
        val id = if (bar >= 0) body.substring(0, bar) else body.substringBefore('^')
        val afterBar = if (bar >= 0) body.substring(bar + 1) else ""
        val caret = afterBar.indexOf('^')
        val transcriptId = if (caret >= 0) afterBar.substring(0, caret) else afterBar
        val query = if (caret >= 0) afterBar.substring(caret + 1) else ""
        val conversationId = id.takeIf { it.isNotBlank() }
        if (conversationId == null) AppScreen.Today else AppScreen.Conversation(
            conversationId,
            transcriptId.takeIf { it.isNotBlank() },
            query.takeIf { it.isNotBlank() },
        )
    }
    // 旧版本会话路由与未知格式一律回到首页；真实对话仍通过 conversation-id 恢复。
    else -> AppScreen.Today
}

/** 返回只移除当前详情，不把搜索、日期及滚动状态一并重置。 */
internal data class AppNavigation(val stack: List<AppScreen> = listOf(AppScreen.Today)) {
    init { require(stack.isNotEmpty()) }
    val current get() = stack.last()
    val canGoBack get() = stack.size > 1 || current != AppScreen.Today
    fun open(screen: AppScreen) = if (screen == current) this else copy(stack = stack + screen)
    fun selectTab(screen: AppScreen): AppNavigation {
        require(screen.isMainScreen)
        return AppNavigation(listOf(screen))
    }
    fun back() = if (stack.size > 1) copy(stack = stack.dropLast(1)) else AppNavigation()
}
