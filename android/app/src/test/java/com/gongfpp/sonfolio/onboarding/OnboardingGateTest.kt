package com.gongfpp.sonfolio.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OnboardingGateTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun emptyRecordingFoldersAreNotHistory() {
        // 真机实测：全新安装已建好当天目录但没有任何录音，此时必须仍展示引导。
        val root = temp.newFolder("recordings")
        assertFalse(containsRecordings(root))
        File(root, "2026-11-19").mkdirs()
        assertFalse(containsRecordings(root))
    }

    @Test
    fun dayFolderWithAFileCountsAsHistory() {
        val root = temp.newFolder("recordings")
        File(root, "2026-11-19").mkdirs()
        File(root, "2026-11-19/seg-1.wav").writeText("x")
        assertTrue(containsRecordings(root))
    }

    @Test
    fun missingRecordingsDirectoryIsNotHistory() {
        assertFalse(containsRecordings(File(temp.root, "absent")))
    }

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
