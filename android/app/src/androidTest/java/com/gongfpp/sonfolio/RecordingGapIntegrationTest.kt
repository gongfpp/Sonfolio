package com.gongfpp.sonfolio

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingGapIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun interruptionRemainsOpenForTenMinutesAndDoesNotMultiplyOnRecovery() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        try {
            val dao = db.recordingDao()
            dao.openGap(10_000, "设备中断")
            dao.openGap(11_000, "再次启动失败")
            val open = dao.getGaps().single()
            assertEquals(10_000L, open.startedAtMillis)
            assertNull(open.endedAtMillis)
            dao.openGap(12_000, "系统静音", "SYSTEM_SILENCED")
            dao.closeOpenGaps("SYSTEM_SILENCED", 20_000, true)
            assertNull(dao.getOpenGap("INTERRUPTION")!!.endedAtMillis)
            dao.closeOpenGaps("INTERRUPTION", 610_000, false)
            assertEquals(600_000L, dao.getGaps().first().let { it.endedAtMillis!! - it.startedAtMillis })
            assertEquals(open.id, dao.getGaps().first().id)
        } finally { db.close() }
    }

    @Test fun newGapSplitsPreviouslyMergedConversationWithDistinctStableIds() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val prefsName = "qa-gaps-${System.nanoTime()}"
        val repository = ConversationRepository(db, SonfolioPreferences(context, prefsName))
        val start = DayWindow.of(java.time.LocalDate.of(2026, 9, 13)).start + 3_600_000
        suspend fun add(id: String, at: Long) {
            db.recordingDao().insertChunk(AudioChunkEntity(id, at, at + 10_000, "/qa/$id.wav", 320_044, 16_000, 1, "ASR_READY", null))
            db.recordingDao().insertSpeechSegments(listOf(SpeechSegmentEntity("s-$id", id, 0, 10_000, 1f, "ASR_READY")))
            db.recordingDao().insertTranscript(TranscriptEntity("t-$id", "s-$id", null, at, at + 10_000, "我们讨论了录音功能，需要保留原音。", "zh", "qa", "qa", "ASR_READY", null))
        }
        try {
            add("b", start + 40_000)
            repository.rebuildFromTranscripts()
            val originalId = repository.observeTimeline().first().single().id
            add("a", start)
            repository.rebuildFromTranscripts()
            assertEquals(originalId, repository.observeTimeline().first().single().id)
            db.recordingDao().insertGap(RecordingGapEntity("gap", start + 20_000, start + 30_000, "测试中断", false))
            repeat(2) {
                repository.rebuildFromTranscripts()
                val conversations = repository.observeTimeline().first()
                assertEquals(2, conversations.size)
                assertEquals(originalId, conversations.first().id)
                assertEquals(2, conversations.map { it.id }.distinct().size)
                assertTrue(conversations.all { repository.observeTranscript(it.id).first().size == 1 })
                assertTrue(repository.observeDailyJournal("2026-09-13").first()!!.narrative.contains("不代表完整经历"))
            }
        } finally { db.close(); context.deleteSharedPreferences(prefsName) }
    }
}
