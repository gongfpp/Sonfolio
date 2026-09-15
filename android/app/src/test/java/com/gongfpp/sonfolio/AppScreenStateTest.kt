package com.gongfpp.sonfolio

import org.junit.Assert.assertEquals
import org.junit.Test

class AppScreenStateTest {
    @Test
    fun everyScreenRoundTripsThroughSavedRoute() {
        val screens = listOf(
            AppScreen.Today,
            AppScreen.Search,
            AppScreen.Settings,
            AppScreen.Daily("2026-09-12"),
            AppScreen.RawRecordings(),
            AppScreen.RawRecordings("2026-09-11"),
            AppScreen.Conversation("auto-test-conversation"),
            AppScreen.Conversation("auto-test-conversation", "transcript-search-hit"),
        )

        screens.forEach { screen ->
            assertEquals(screen, appScreenFromSavedRoute(screen.toSavedRoute()))
        }
    }

    @Test
    fun unknownRouteFallsBackToToday() {
        assertEquals(AppScreen.Today, appScreenFromSavedRoute("unknown"))
        assertEquals(AppScreen.Today, appScreenFromSavedRoute("conversation:Missing"))
        assertEquals(AppScreen.Today, appScreenFromSavedRoute("conversation-id:"))
    }

    @Test
    fun oldAndInvalidDateRoutesRemainSafe() {
        assertEquals(AppScreen.Daily(), appScreenFromSavedRoute("daily"))
        assertEquals(AppScreen.Daily(), appScreenFromSavedRoute("daily:bad-date"))
        assertEquals(AppScreen.RawRecordings(), appScreenFromSavedRoute("raw-recordings"))
        assertEquals(AppScreen.RawRecordings(), appScreenFromSavedRoute("raw-recordings:bad-date"))
    }

    @Test
    fun searchDetailReturnsToSearchAndKeepsHitRoute() {
        val search = AppNavigation().selectTab(AppScreen.Search)
        val detail = search.open(AppScreen.Conversation("conversation-1", "hit-2"))
        assertEquals(search, detail.back())
        assertEquals(detail, AppNavigation(detail.stack.map { appScreenFromSavedRoute(it.toSavedRoute()) }))
        assertEquals(AppScreen.Today, detail.back().back().current)
    }

    @Test
    fun rawAudioReturnsToItsActualOriginAndDoesNotDuplicateCurrentScreen() {
        val raw = AppScreen.RawRecordings("2026-09-11")
        val fromHome = AppNavigation().open(raw)
        val fromSettings = AppNavigation().selectTab(AppScreen.Settings).open(raw)
        assertEquals(AppScreen.Today, fromHome.back().current)
        assertEquals(AppScreen.Settings, fromSettings.back().current)
        assertEquals(fromSettings, fromSettings.open(raw))
    }
}
