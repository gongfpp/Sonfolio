package com.gongfpp.sonfolio

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.*
import com.gongfpp.sonfolio.processing.DetectedSpeechWindow
import com.gongfpp.sonfolio.processing.InferenceClient
import com.gongfpp.sonfolio.recording.WavChunkWriter
import com.gongfpp.sonfolio.recording.RecordingRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PipelineIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realPlayerPausesResumesAndSeeksAcrossFiles() = runBlocking {
        val first = File(context.filesDir, "qa-playback-a-${System.nanoTime()}.wav")
        val second = File(context.filesDir, "qa-playback-b-${System.nanoTime()}.wav")
        val controller = AudioPlaybackController()
        try {
            listOf(first, second).forEach { file -> WavChunkWriter(file, 16_000, 1).use { it.write(ByteArray(64_000), 64_000) } }
            withContext(Dispatchers.Main) {
                controller.timeline = PlaybackTimeline(listOf(PlaybackSlice(first.path, 0, 0, 2_000), PlaybackSlice(second.path, 2_000, 2_000, 4_000)))
                controller.seek(0, autoPlay = true)
                withTimeout(8_000) { while (!controller.playing) { assertNull(controller.error); delay(30) } }
                delay(450)
                controller.tick()
                controller.pause()
                val paused = controller.position
                assertTrue(paused > 0)
                delay(250)
                controller.tick()
                assertEquals(paused, controller.position)
                controller.toggle()
                delay(250)
                controller.tick()
                assertTrue(controller.position > paused)
                controller.seek(2_500, autoPlay = false)
                withTimeout(8_000) { while (controller.preparing) delay(30) }
                assertEquals(2_500L, controller.position)
                assertFalse(controller.playing)
                controller.seek(1_700, autoPlay = true)
                withTimeout(8_000) {
                    while (controller.position <= 2_300) { assertNull(controller.error); controller.tick(); delay(50) }
                }
                assertTrue(controller.playing)
            }
        } finally {
            withContext(Dispatchers.Main) { controller.release() }
            first.delete()
            second.delete()
        }
    }

    @Test fun recoveryUsesPcmDurationInsteadOfRepairModificationTime() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val file = File(context.filesDir, "qa-recovery-${System.nanoTime()}.wav")
        val started = System.currentTimeMillis() - 60_000L
        val recovered = started + 60_000L
        try {
            WavChunkWriter(file, 16_000, 1).use { it.write(ByteArray(64_000), 64_000) }
            database.recordingDao().insertChunk(AudioChunkEntity("qa", started, null, file.path, 0, 16_000, 1, "RECORDING", null))
            RecordingRepository(database.recordingDao()).recoverDanglingChunks(recovered)
            val chunk = database.recordingDao().getChunk("qa")!!
            assertEquals(started + 2_000L, chunk.endedAtMillis)
            val gap = database.recordingDao().observeGaps().first().single()
            assertEquals(started + 2_000L, gap.startedAtMillis)
            assertNull(gap.endedAtMillis)
            val actualResume = recovered + 600_000
            database.recordingDao().closeOpenGaps("INTERRUPTION", actualResume, false)
            assertEquals(actualResume, database.recordingDao().getGaps().single().endedAtMillis)
            assertTrue(file.exists())
        } finally { database.close(); file.delete() }
    }

    @Test fun stableConversationsMarkersFilteringAndSearch() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val preferenceName = "sonfolio-qa-${System.nanoTime()}"
        val preferences = SonfolioPreferences(context, preferenceName)
        val repository = ConversationRepository(database, preferences)
        val dao = database.recordingDao()
        val base = System.currentTimeMillis() - 3_600_000L
        suspend fun insert(id: String, start: Long, duration: Long, text: String) {
            dao.insertChunk(AudioChunkEntity(id, start, start + duration, "/qa/$id.wav", 44 + duration * 32, 16_000, 1, "ASR_READY", null))
            dao.insertSpeechSegments(listOf(SpeechSegmentEntity("s-$id", id, 0, duration, 1f, "ASR_READY")))
            dao.insertTranscript(TranscriptEntity("t-$id", "s-$id", null, start, start + duration, text, "zh", "qa", "qa", "ASR_READY", null))
        }
        try {
            insert("first", base, 280_000, "今晚安排系统投产。")
            repository.rebuildFromTranscripts()
            val id = repository.observeTimeline().first().single().id
            insert("second", base + 300_000, 60_000, "我们决定先完成测试。明天需要检查回滚方案。")
            repository.rebuildFromTranscripts()
            assertEquals(id, repository.observeTimeline().first().single().id)
            assertEquals(2, repository.observeTranscript(id).first().size)
            dao.insertMarker(MarkerEntity("mark", base + 330_000, 180_000, 0, null))
            repository.rebuildFromTranscripts()
            assertTrue(repository.observeTranscript(id).first().all { it.isMarked })
            assertEquals(2, repository.observeSearch("", markedOnly = true).first().hits.size)
            assertTrue(repository.observeSearch("%").first().hits.isEmpty())
            assertTrue(repository.observeSearch("_").first().hits.isEmpty())
            assertTrue(repository.observeSearch("").first().hits.isEmpty())
            assertEquals(1, repository.observeSearch("回滚").first().hits.size)
            insert("short", base + 900_000, 1_000, "嗯")
            repository.rebuildFromTranscripts()
            assertEquals(1, repository.observeTimeline().first().size)
            dao.insertMarker(MarkerEntity("mark-short", base + 900_500, 180_000, 0, null))
            repository.rebuildFromTranscripts()
            assertEquals(2, repository.observeTimeline().first().size)
            assertTrue(repository.observeTimeline().first().any { it.title == "未识别" })
            assertEquals(3, dao.observeRecentChunks().first().size)
        } finally {
            database.close()
            context.deleteSharedPreferences(preferenceName)
        }
    }

    @Test fun remoteModelsProcessPrivateAudioWithoutChangingIt() = runBlocking {
        val file = File(context.filesDir, "qa-inference-${System.nanoTime()}.wav")
        try {
            WavChunkWriter(file, 16_000, 1).use { it.write(ByteArray(64_000), 64_000) }
            val before = file.readBytes()
            val client = InferenceClient(context)
            assertTrue(client.detect(file).isEmpty())
            val result = client.transcribe(file, listOf(DetectedSpeechWindow(0, 2_000)), "zh")
            assertEquals(1, result.size)
            assertArrayEquals(before, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun modelTimeoutIsFailureButExternalCancellationIsPreserved() = runBlocking {
        val file = File(context.filesDir, "qa-timeout-${System.nanoTime()}.wav")
        try {
            WavChunkWriter(file, 16_000, 1).use { it.write(ByteArray(64_000), 64_000) }
            val windows = listOf(DetectedSpeechWindow(0, 2_000))
            val timeout = runCatching {
                InferenceClient(context, processingTimeoutMillis = 1L).transcribe(file, windows, "zh")
            }.exceptionOrNull()
            assertTrue(timeout is IllegalStateException)
            assertTrue(timeout!!.message!!.contains("处理超时"))
            val cancellation = runCatching {
                withTimeout(1L) { InferenceClient(context).transcribe(file, windows, "zh") }
            }.exceptionOrNull()
            assertTrue(cancellation is TimeoutCancellationException)
        } finally { file.delete() }
    }
}
