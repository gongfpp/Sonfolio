package com.gongfpp.sonfolio

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteAsrCheckpointTest {
    @Test fun threeWindowsRetryOnlyFailureAndChangedRevisionRequestsAllAgain() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, SonfolioDatabase::class.java).build()
        try {
            val dao = db.recordingDao()
            dao.insertChunk(AudioChunkEntity("qa", 0, 3000, "", 0, 16000, 1, "ASR_FAILED", null))
            val segments = (0..2).map { SpeechSegmentEntity("w$it", "qa", it * 1000L, (it + 1) * 1000L, 1f, "ASR_READY") }
            dao.insertSpeechSegments(segments)
            val calls = java.util.concurrent.ConcurrentHashMap<String, Int>()
            suspend fun batch(revision: String, failLast: Boolean) = com.gongfpp.sonfolio.processing.transcribeRemoteWindows(
                dao, "qa", "$revision:zh", segments, { true },
            ) { segment ->
                calls.merge(segment.id, 1, Int::plus)
                if (segment.id == "w2" && failLast) error("fixture network failure")
                if (segment.id == "w1") "" else "转写 ${segment.id}"
            }
            assertTrue(batch("one", true).getValue("w2").isFailure)
            val retried = batch("one", false)
            assertEquals("", retried.getValue("w1").getOrThrow())
            assertEquals(mapOf("w0" to 1, "w1" to 1, "w2" to 2), calls.toMap())
            batch("two", false)
            assertEquals(mapOf("w0" to 2, "w1" to 2, "w2" to 3), calls.toMap())
            val cancelled = runCatching {
                com.gongfpp.sonfolio.processing.transcribeRemoteWindows(dao, "qa", "cancel:zh", segments, { true }) {
                    throw kotlinx.coroutines.CancellationException("fixture cancel")
                }
            }
            assertTrue(cancelled.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertTrue(dao.getRemoteAsrWindows("qa", "cancel:zh").isEmpty())
        } finally { db.close() }
    }

    @Test fun emptySuccessSurvivesRetryAndConfigChangeDoesNotReuseIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, SonfolioDatabase::class.java).build()
        try {
            val dao = db.recordingDao()
            dao.insertChunk(AudioChunkEntity("qa", 0, 1000, "", 0, 16000, 1, "ASR_FAILED", null))
            dao.insertSpeechSegments(listOf(SpeechSegmentEntity("window", "qa", 0, 1000, 1f, "ASR_READY")))
            dao.saveRemoteAsrWindow(RemoteAsrWindowEntity("window", "revision:zh", "EMPTY_SUCCESS", ""))
            val saved = dao.getRemoteAsrWindows("qa", "revision:zh").single()
            assertEquals("EMPTY_SUCCESS", saved.state); assertEquals("", saved.text)
            assertTrue(dao.getRemoteAsrWindows("qa", "other:zh").isEmpty())
            assertTrue(dao.getRemoteAsrWindows("qa", "revision:en").isEmpty())
            dao.deleteSpeechSegments("qa")
            assertTrue(dao.getRemoteAsrWindows("qa", "revision:zh").isEmpty())
        } finally { db.close() }
    }
}
