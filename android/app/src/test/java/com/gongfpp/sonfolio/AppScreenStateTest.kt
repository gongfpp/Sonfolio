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
            AppScreen.Daily,
            AppScreen.Conversation(ConversationType.Release),
            AppScreen.Conversation(ConversationType.Lunch),
            AppScreen.Conversation(ConversationType.Game),
            AppScreen.Conversation(ConversationType.Unknown),
        )

        screens.forEach { screen ->
            assertEquals(screen, appScreenFromSavedRoute(screen.toSavedRoute()))
        }
    }

    @Test
    fun unknownRouteFallsBackToToday() {
        assertEquals(AppScreen.Today, appScreenFromSavedRoute("unknown"))
        assertEquals(AppScreen.Today, appScreenFromSavedRoute("conversation:Missing"))
    }
}
