package com.gongfpp.sonfolio

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.gongfpp.sonfolio.data.local.*
import com.gongfpp.sonfolio.recording.WavChunkWriter
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 真实详情页与 MediaPlayer，使用内存库、唯一缓存音频和偏好，不碰个人持久目录。 */
class ConversationJourneyUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun searchSeekPlaybackMarkerAndRestoreUseTheSameOriginalSentence() {
        val base = ui.activity
        val root = File(base.cacheDir, "qa-conversation-ui-${System.nanoTime()}").apply { mkdirs() }
        val prefs = java.util.concurrent.CopyOnWriteArraySet<String>()
        val prefix = "qa-conversation-ui-${System.nanoTime()}-"
        val app = object : SonfolioApplication() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                prefs += prefix + name
                return base.getSharedPreferences(prefix + name, mode)
            }
            override val database = Room.inMemoryDatabaseBuilder(base, SonfolioDatabase::class.java).build()
        }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = app
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = root
        }
        var vm: SonfolioViewModel? = null
        var compositionScope: CoroutineScope? = null
        var recomposer: Recomposer? = null
        try {
            val start = DayWindow.of(java.time.LocalDate.now()).start + 10 * 3_600_000L
            val conversation = runBlocking {
                val file = File(File(root, "recordings").apply { mkdirs() }, "fixture.wav")
                WavChunkWriter(file, 16_000, 1).use { writer ->
                    // 静音测试音轨只验证定位和播放器状态，不把它当人声识别验收。
                    repeat(12) { writer.write(ByteArray(32_000), 32_000) }
                }
                val dao = app.database.recordingDao()
                // 已有播放资源，不触发后台压缩任务；播放器仍读取上面的真实 WAV。
                dao.insertChunk(AudioChunkEntity("qa-ui", start, start + 12_000, file.path, file.length(), 16_000, 1, "ASR_READY", null,
                    compressedPath = file.path, compressedBytes = file.length()))
                listOf("嗯", "周五提交预蒜并和小王确认", "另一件事需要保留手工修改").forEachIndexed { i, text ->
                    val offset = if (i == 2) 8_000L else i * 2_000L
                    dao.insertSpeechSegments(listOf(SpeechSegmentEntity("s$i", "qa-ui", offset, offset + 1_000, 1f, "ASR_READY")))
                    dao.insertTranscript(TranscriptEntity("t$i", "s$i", null, start + offset, start + offset + 1_000, text, "zh", "qa", "qa", "ASR_READY", null))
                }
                app.conversationRepository.rebuildFromTranscripts()
                app.conversationRepository.applyTranscriptCorrections(mapOf("t1" to "周五提交预算并和小王确认"))
                app.conversationRepository.observeTimeline().first().single()
            }
            val hit = runBlocking { app.conversationRepository.observeSearch("预算").first().hits.single() }
            assertEquals("t1", hit.snippetTranscriptId)
            ui.runOnUiThread { vm = SonfolioViewModel(app) }
            ui.runOnUiThread {
                // 使用 Android 的真实帧/主线程调度，避免测试拦截器改变 Room Flow 和 Toast 的线程。
                val scope = CoroutineScope(AndroidUiDispatcher.Main)
                compositionScope = scope
                val hostRecomposer = Recomposer(scope.coroutineContext)
                recomposer = hostRecomposer
                scope.launch { hostRecomposer.runRecomposeAndApplyChanges() }
                base.setContentView(ComposeView(base).apply {
                    setParentCompositionContext(hostRecomposer)
                    setContent {
                        CompositionLocalProvider(LocalContext provides context) {
                            SonfolioTheme(darkTheme = false) {
                                RealConversationScreen(conversation, conversation.id, hit.snippetTranscriptId, "预算", vm!!, onBack = {})
                            }
                        }
                    }
                })
            }
            ui.waitUntil(10_000) { ui.onAllNodesWithTag("transcript-t0").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("transcript-t0").assertIsSelected()
            ui.waitUntil(10_000) {
                ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().any {
                    it.config[SemanticsProperties.ProgressBarRangeInfo].current == 2_000f
                }
            }
            ui.onNode(hasContentDescription("播放这一句") and hasAnyAncestor(hasTestTag("transcript-t0"))).performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithContentDescription("暂停").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("暂停").performClick()
            // 播放器已消费一次性请求，选中仍保留；补标记不能回退到对话结尾。
            ui.onNodeWithTag("transcript-t0").assertIsSelected()
            ui.onNodeWithTag("conversation-transcript-list").performScrollToIndex(0)
            ui.onNodeWithContentDescription("更多操作").performClick()
            ui.onNodeWithText("标记", useUnmergedTree = true).performClick()
            ui.onNode(hasText("选中句末", substring = true)).assertIsDisplayed()
            ui.onNodeWithText("标记 向前 3 分").performClick()
            ui.waitUntil(5_000) { runBlocking { app.database.recordingDao().getMarkers().isNotEmpty() } }
            val marker = runBlocking { app.database.recordingDao().getMarkers().single() }
            assertEquals(start + 3_000, marker.markedAtMillis)
            assertEquals(180_000L, marker.windowBeforeMillis)
            ui.onNodeWithTag("conversation-transcript-list").performScrollToNode(hasTestTag("transcript-t0"))
            ui.onAllNodesWithContentDescription("修正这一句").onFirst().performClick()
            ui.onNodeWithText("恢复这一句原文").performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText("嗯周五提交预蒜并和小王确认").fetchSemanticsNodes().isNotEmpty() }
            val restored = runBlocking { app.conversationRepository.observeTranscript(conversation.id).first() }
            assertNull(restored.first().originalText)
            assertEquals("另一件事需要保留手工修改", restored.last().text)
        } finally {
            ui.runOnUiThread {
                vm?.viewModelScope?.cancel()
                ui.activity.setContentView(android.widget.FrameLayout(base))
                recomposer?.cancel()
                compositionScope?.cancel()
            }
            app.database.close()
            prefs.forEach { base.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }
}
