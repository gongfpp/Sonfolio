package com.gongfpp.sonfolio

import java.time.LocalDate

internal sealed interface AppScreen {
    data object Today : AppScreen
    data object Search : AppScreen
    data object Settings : AppScreen
    data class Daily(val date: String = LocalDate.now().toString()) : AppScreen
    data class RawRecordings(val date: String? = null) : AppScreen
    data class Conversation(val type: ConversationType, val id: String? = null, val transcriptId: String? = null) : AppScreen
}

internal val AppScreen.isMainScreen: Boolean
    get() = this == AppScreen.Today || this == AppScreen.Search || this == AppScreen.Settings

internal fun AppScreen.toSavedRoute(): String = when (this) {
    AppScreen.Today -> "today"
    AppScreen.Search -> "search"
    AppScreen.Settings -> "settings"
    is AppScreen.Daily -> "daily:$date"
    is AppScreen.RawRecordings -> date?.let { "raw-recordings:$it" } ?: "raw-recordings"
    is AppScreen.Conversation -> id?.let { "conversation-id:$it${transcriptId?.let { value -> "|$value" }.orEmpty()}" } ?: "conversation:${type.name}"
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
    route.startsWith("conversation-id:") -> AppScreen.Conversation(
        ConversationType.Unknown,
        route.removePrefix("conversation-id:").substringBefore('|').takeIf { it.isNotBlank() },
        route.substringAfter('|', "").takeIf { it.isNotBlank() },
    )
    else -> ConversationType.entries.firstOrNull { it.name == route.substringAfter("conversation:", "") }
        ?.let { AppScreen.Conversation(it) } ?: AppScreen.Today
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
