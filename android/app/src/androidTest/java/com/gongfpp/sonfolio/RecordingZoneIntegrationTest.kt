package com.gongfpp.sonfolio

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** P1 时区归属：日期按录音发生时的时区计算，不随设备当前时区漂移。 */
@RunWith(AndroidJUnit4::class)
class RecordingZoneIntegrationTest {
    @Test fun rebuildAttributesDayByRecordedZone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val repository = ConversationRepository(database, SonfolioPreferences(context, "sonfolio-zone-qa-${System.nanoTime()}"))
        val dao = database.recordingDao()
        val tokyo = ZoneId.of("Asia/Tokyo")
        // 东京 2026-09-20 00:30（此时中国标准时间仍是 09-19 23:30）。
        val start = java.time.LocalDate.of(2026, 9, 20).atTime(0, 30).atZone(tokyo).toInstant().toEpochMilli()
        val localStart = Instant.ofEpochMilli(start).atZone(tokyo).toLocalDate().toString()
        try {
            database.withTransaction {
                dao.insertChunk(AudioChunkEntity("zone-chunk", start, start + 8_000, "/qa/zone.wav", 4_000, 16_000, 1, "ASR_READY", null, tokyo.id, 9 * 3_600, localStart))
                dao.insertSpeechSegments(listOf(SpeechSegmentEntity("zone-seg", "zone-chunk", 0, 4_000, 1f, "ASR_READY")))
                dao.insertTranscript(TranscriptEntity("zone-text", "zone-seg", null, start, start + 4_000, "我们在东京谈了明天的部署计划。", "zh", "qa", "qa", "ASR_READY", null))
            }
            repository.rebuildFromTranscripts(start, start + 4_000)
            val conversation = database.conversationDao().getConversation(repository.observeTimeline().first().single().id)!!
            assertEquals("Asia/Tokyo", conversation.zoneId)
            assertEquals("2026-09-20", conversation.localStartDate)
            val journal = database.conversationDao().getDailyJournal("2026-09-20")
            assertNotNull(journal)
            assertEquals("Asia/Tokyo", journal?.zoneId)
            // 设备时区解释出的错误日期上不应该有整理结果。
            assertNull(database.conversationDao().getDailyJournal("2026-09-19"))
        } finally {
            database.close()
        }
    }
}
