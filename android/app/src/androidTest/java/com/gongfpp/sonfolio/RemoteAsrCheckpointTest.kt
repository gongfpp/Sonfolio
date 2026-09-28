package com.gongfpp.sonfolio

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteAsrCheckpointTest {
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
