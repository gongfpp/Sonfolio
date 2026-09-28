package com.gongfpp.sonfolio

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in TCP physical QA screenshots. No database fixtures or personal content. */
class PublicScreenshotsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun captureSettingsWithoutPrivateContent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicScreenshots") == "true")
        val app = ui.activity.application as SonfolioApplication
        check(app.packageName == "com.gongfpp.sonfolio.qa")
        check(!app.transcriptionSettings.read().hasKey && !app.summarySettings.read().hasKey) { "有真实密钥时禁止公开截图" }
        val folder = File(app.cacheDir, "qa-screens-${System.nanoTime()}").apply { mkdirs() }
        ui.onNodeWithText("设置").performClick()
        ui.waitForIdle()
        File(folder, "settings.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        // QA screenshots remain only in this unique cache directory for explicit retrieval.
    }
}
