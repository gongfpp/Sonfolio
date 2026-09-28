package com.gongfpp.sonfolio

import androidx.activity.compose.setContent
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
        kotlinx.coroutines.runBlocking {
            app.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM audio_chunks").use {
                check(it.moveToFirst() && it.getLong(0) == 0L) { "QA 库已有录音，禁止生成公开截图" }
            }
        }
        val folder = File(app.cacheDir, "qa-screens-${System.nanoTime()}").apply { mkdirs() }
        fun capture(name: String) {
            ui.waitForIdle()
            File(folder, "$name.png").outputStream().use {
                // An editor dialog owns a second Compose root; capture the foreground root.
                ui.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        capture("home")
        ui.onNodeWithText("设置").performClick()
        capture("settings")
        for ((label, filename) in listOf("录音设置" to "recording-settings", "存储与备份" to "storage", "处理与整理" to "processing")) {
            ui.onNodeWithText(label).performScrollTo().performClick()
            capture(filename)
            ui.onNodeWithContentDescription("返回").performClick()
        }
        ui.onNodeWithText("转文字方式").performClick()
        capture("transcription")
        ui.onNodeWithText("返回设置").performClick()
        ui.onNodeWithText("总结方式").performClick()
        capture("summary")
        // App-local theme override, never changes the phone's system theme or security settings.
        ui.runOnUiThread {
            ui.activity.setContent {
                SonfolioTheme(darkTheme = true) {
                    androidx.compose.material3.Surface(color = Paper) {
                        SettingsScreen(app.preferences, com.gongfpp.sonfolio.recording.RecordingStatus(), {}, {})
                    }
                }
            }
        }
        capture("settings-dark")
        ui.onNodeWithText("录音设置").performScrollTo().performClick()
        capture("recording-settings-dark")
        // QA screenshots remain only in this unique cache directory for explicit retrieval.
    }
}
