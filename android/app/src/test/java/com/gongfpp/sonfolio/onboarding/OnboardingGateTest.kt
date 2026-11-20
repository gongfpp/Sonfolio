package com.gongfpp.sonfolio.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingGateTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun freshInstallShowsOnboarding() {
        assertTrue(shouldShowOnboarding(completed = false, deferredAt = 0L, hasHistory = false, now = now))
    }

    @Test
    fun completedInstallNeverShowsAgain() {
        assertFalse(shouldShowOnboarding(completed = true, deferredAt = 0L, hasHistory = false, now = now))
        // 完成标记优先于“稍后”标记与历史数据。
        assertFalse(shouldShowOnboarding(completed = true, deferredAt = now - day, hasHistory = true, now = now))
    }

    @Test
    fun deferredInstallStaysQuietWithinAWeek() {
        assertFalse(shouldShowOnboarding(completed = false, deferredAt = now - day, hasHistory = false, now = now))
        assertFalse(shouldShowOnboarding(completed = false, deferredAt = now - 6 * day, hasHistory = false, now = now))
    }

    @Test
    fun deferredInstallAsksAgainAfterAWeekWhenStillUnconfigured() {
        assertTrue(shouldShowOnboarding(completed = false, deferredAt = now - 8 * day, hasHistory = false, now = now))
    }

    @Test
    fun existingInstallWithRecordingsIsTreatedAsReturningUser() {
        assertFalse(shouldShowOnboarding(completed = false, deferredAt = 0L, hasHistory = true, now = now))
    }

    @Test
    fun clockSkewDoesNotProduceASecondInterruption() {
        // 设备时间被回拨时，时间差为负仍算“窗口内”，避免重复整页拦截。
        assertFalse(shouldShowOnboarding(completed = false, deferredAt = now + day, hasHistory = false, now = now))
    }
}
