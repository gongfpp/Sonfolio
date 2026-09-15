package com.gongfpp.sonfolio

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** ⑥ 标记撤销、标题、备注、修正转写（保留原始版本）的数据层验收。 */
@RunWith(AndroidJUnit4::class)
class ConversationEditIntegrationTest {
    @Test fun editTitleNoteUndoMarkerAndCorrectTranscriptKeepOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val repository = ConversationRepository(database, SonfolioPreferences(context, "sonfolio-edit-qa-${System.nanoTime()}"))
        val dao = database.recordingDao()
        val base = System.currentTimeMillis() - 3_600_000L
        try {
            database.withTransaction {
                dao.insertChunk(AudioChunkEntity("chunk", base, base + 8_000, "/qa/edit.wav", 4_000, 16_000, 1, "ASR_READY", null))
                dao.insertSpeechSegments(listOf(SpeechSegmentEntity("seg", "chunk", 0, 4_000, 1f, "ASR_READY")))
                dao.insertTranscript(TranscriptEntity("t-1", "seg", null, base, base + 4_000, "先完成测试，再检查回滚方案。", "zh", "qa", "qa", "ASR_READY", null))
            }
            repository.rebuildFromTranscripts()
            val id = repository.observeTimeline().first().single().id

            // 标题修改：写入 titleOverride，自动标题字段不受影响
            repository.updateConversationTitle(id, "投产准备")
            assertEquals("投产准备", repository.observeTimeline().first().single().title)
            repository.updateConversationNote(id, "记得核对回滚")
            assertEquals("记得核对回滚", repository.observeConversation(id).first()?.note)

            // 关键回归：rebuild 不得覆盖用户手工输入的标题与备注
            repository.rebuildFromTranscripts()
            val afterRebuild = repository.observeConversation(id).first()!!
            assertEquals("投产准备", afterRebuild.title)
            assertEquals("记得核对回滚", afterRebuild.note)
            val afterRebuildEntity = database.conversationDao().getConversation(id)!!
            assertEquals("投产准备", afterRebuildEntity.titleOverride)
            assertEquals("记得核对回滚", afterRebuildEntity.note)
            repository.updateConversationNote(id, "   ")
            assertNull(repository.observeConversation(id).first()?.note)

            // 标记撤销：标记覆盖整段对话后 isMarked 为真，撤销后为假
            dao.insertMarker(MarkerEntity("mark", base + 500, 1_000, 0, null))
            repository.rebuildFromTranscripts()
            assertTrue(repository.observeTimeline().first().single().isMarked)
            // 再次 rebuild 后用户数据依然完好：标题保留，已清空的备注不得被带回
            assertEquals("投产准备", repository.observeConversation(id).first()?.titleOverride)
            assertNull(repository.observeConversation(id).first()?.note)
            repository.removeMarkerForConversation(id)
            assertFalse(repository.observeTimeline().first().single().isMarked)

            // 恢复自动标题：titleOverride 清空后展示回落到 generatedTitle
            repository.resetConversationTitle(id)
            val resetEntity = database.conversationDao().getConversation(id)!!
            assertNull(resetEntity.titleOverride)
            assertEquals(resetEntity.generatedTitle, resetEntity.displayTitle)

            // 修正转写：保留原始版本，二次修正不覆盖原始版本
            val conversationDao = database.conversationDao()
            val dayKey = "day:${localDateAt(base)}"
            conversationDao.saveSummaryRun(com.gongfpp.sonfolio.data.local.SummaryRunEntity("conversation:$id", "old", "REMOTE", "m@e", "{\"title\":\"旧结论\"}", "READY", null, System.currentTimeMillis()))
            conversationDao.saveSummaryRun(com.gongfpp.sonfolio.data.local.SummaryRunEntity(dayKey, "old", "REMOTE", "m@e", "{\"title\":\"旧结论\"}", "READY", null, System.currentTimeMillis()))
            repository.updateTranscriptText("t-1", "先完成测试，再复核回滚方案。")
            val corrected = repository.observeTranscript(id).first().single()
            assertEquals("先完成测试，再复核回滚方案。", corrected.text)
            assertEquals("先完成测试，再检查回滚方案。", corrected.originalText)
            // 失效传播：基础小结按修正后的文字重建，关联 AI 总结被标记 STALE
            assertTrue(conversationDao.getConversation(id)!!.briefSummary.contains("复核回滚方案"))
            assertEquals("STALE", conversationDao.getSummaryRun("conversation:$id")?.state)
            assertEquals("STALE", conversationDao.getSummaryRun(dayKey)?.state)

            repository.updateTranscriptText("t-1", "先完成测试，再复核回滚方案并确认。")
            val reCorrected = repository.observeTranscript(id).first().single()
            assertEquals("先完成测试，再复核回滚方案并确认。", reCorrected.text)
            assertEquals("先完成测试，再检查回滚方案。", reCorrected.originalText)

            // 空文本不应写回
            repository.updateTranscriptText("t-1", "   ")
            assertEquals("先完成测试，再复核回滚方案并确认。", repository.observeTranscript(id).first().single().text)
        } finally {
            database.close()
        }
    }
}
