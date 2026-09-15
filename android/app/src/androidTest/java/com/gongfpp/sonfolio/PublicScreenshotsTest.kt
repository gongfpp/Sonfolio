package com.gongfpp.sonfolio

import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.*
import com.gongfpp.sonfolio.recording.WavChunkWriter
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in, synthetic data only, on an empty emulator. Never runs on a personal phone. */
class PublicScreenshotsTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun createReadmeImages() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicScreenshots") == "true")
        check(Build.PRODUCT.startsWith("sdk_")) { "只允许在模拟器制作公开截图" }
        val app = ui.activity.application as SonfolioApplication
        runBlocking {
            check(app.database.recordingDao().getAllChunks().isEmpty() && app.database.conversationDao().count() == 0) { "仅允许空库，禁止公开真实记录" }
            val day = DayWindow.of(LocalDate.now()).start
            val examples = listOf(
                Triple(9L * 3_600_000 + 32 * 60_000, "周末散步", "我们讨论了周末去公园散步的安排。决定周六上午九点在公园门口集合。如果下雨，就改到周日上午。"),
                Triple(12L * 3_600_000 + 11 * 60_000, "午餐闲聊", "午饭时聊起最近读的一本书，大家分享了喜欢的章节。有人提到下次可以一起去图书馆，目前还没有确定时间。"),
                Triple(14L * 3_600_000 + 40 * 60_000, "花园灵感", "想到一个阳台小花园的点子，可以先种薄荷和罗勒。需要先观察阳光和浇水条件，还没有决定买哪一种花盆。"),
            )
            examples.forEachIndexed { index, (offset, title, text) ->
                val id = "public-demo-$index"; val start = day + offset; val duration = 90_000L
                val file = File(app.filesDir, "recordings/$id.wav").apply { parentFile!!.mkdirs() }
                WavChunkWriter(file, 16_000, 1).use { writer -> repeat(90) { writer.write(ByteArray(32_000), 32_000) } }
                val dao = app.database.recordingDao()
                dao.insertChunk(AudioChunkEntity(id, start, start + duration, file.path, file.length(), 16_000, 1, "ASR_READY", null))
                dao.insertSpeechSegments(listOf(SpeechSegmentEntity("s-$id", id, 0, duration, 1f, "ASR_READY")))
                dao.insertTranscript(TranscriptEntity("t-$id", "s-$id", id, start, start + duration, text, "zh", "演示资料", "fixture", "ASR_READY", null))
                app.database.conversationDao().insertAll(listOf(ConversationEntity(id, "Unknown", start, start + duration, java.time.ZoneId.systemDefault().id, title, null, text, "BRIEF", "READY")))
                if (index == 0) dao.insertMarker(MarkerEntity("public-mark", start + duration, 180_000, 0, null))
            }
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("周末散步").fetchSemanticsNodes().isNotEmpty() }
        fun capture(name: String) {
            ui.waitForIdle()
            val folder = File(app.getExternalFilesDir(null), "public-screens").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        capture("timeline")
        ui.onNodeWithText("周末散步").performClick()
        capture("conversation")
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("设置").performClick()
        capture("models")
        ui.onNodeWithText("外部 API").performScrollTo().performClick()
        ui.onNodeWithText("保存总结设置").performScrollTo()
        capture("api-settings")
    }
}
