package com.gongfpp.sonfolio

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.*
import com.gongfpp.sonfolio.recording.RecordingRepository
import com.gongfpp.sonfolio.recording.WavChunkWriter
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RawCleanupIntegrationTest {
    @Test fun cleanupReducesVisibleFilesAndCountsButPreservesTextAndIsRepeatSafe() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val directory = File(context.cacheDir, "qa-cleanup-${System.nanoTime()}").apply { mkdirs() }
        val dao = db.recordingDao()
        try {
            for (id in listOf("silence", "filtered", "protected")) {
                val file = File(directory, "$id.wav")
                WavChunkWriter(file, 16_000, 1).use { it.write(ByteArray(32_000), 32_000) }
                val start = if (id == "protected") 900_000L else 1_000L
                dao.insertChunk(AudioChunkEntity(id, start, start + 1_000, file.path, file.length(), 16_000, 1, "ASR_READY", null))
            }
            dao.insertSpeechSegments(listOf(SpeechSegmentEntity("speech", "filtered", 0, 1_000, 1f, "ASR_READY")))
            dao.insertTranscript(TranscriptEntity("text", "speech", null, 1_000, 2_000, "嗯", "zh", "qa", "qa", "ASR_READY", null))
            dao.insertMarker(MarkerEntity("marker", 900_500, 1_000, 0, null))
            // This fixture has its own files/database; do not contend with personal ASR work.
            val fixtureMutex = kotlinx.coroutines.sync.Mutex()
            val repository = RecordingRepository(dao, audioFileMutex = fixtureMutex)
            assertEquals(listOf("filtered"), dao.getCleanupCandidates(0, Long.MAX_VALUE, false))
            assertEquals(3, dao.observeChunkCount(0, Long.MAX_VALUE).first())
            fixtureMutex.lock()
            try {
                assertTrue(repository.deleteChunks(setOf("silence"), true).contains("未删除任何文件"))
                assertEquals(3, dao.observeChunkCount(0, Long.MAX_VALUE).first())
            } finally { fixtureMutex.unlock() }
            val result = repository.deleteChunks(setOf("silence", "filtered", "protected"), protectMarked = true)
            assertTrue(result, result.contains("2"))
            assertEquals(1, dao.observeChunkCount(0, Long.MAX_VALUE).first())
            assertEquals(listOf("protected"), dao.observeRecentChunks(includeDeleted = false).first().map { it.chunk.id })
            assertEquals(3, dao.observeRecentChunks().first().size)
            assertEquals(listOf("text"), dao.getTranscriptsForSummaryChunk("filtered"))
            assertFalse(File(directory, "silence.wav").exists())
            assertFalse(File(directory, "filtered.wav").exists())
            assertTrue(File(directory, "protected.wav").isFile)
            assertTrue(dao.getCleanupCandidates(0, Long.MAX_VALUE, false).isEmpty())
            repository.deleteChunks(setOf("silence", "filtered"), protectMarked = true)
            assertEquals(1, dao.observeChunkCount(0, Long.MAX_VALUE).first())
        } finally { db.close(); directory.deleteRecursively() }
    }
}
