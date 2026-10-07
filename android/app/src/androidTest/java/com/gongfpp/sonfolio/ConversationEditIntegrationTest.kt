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

/** ⑥ 标记撤销、标题、修正转写（保留原始版本）的数据层验收。 */
@RunWith(AndroidJUnit4::class)
class ConversationEditIntegrationTest {
    @Test fun restoreMergedLinePreservesAllOriginalSentencesAndOtherManualEdits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val prefs = "qa-restore-${System.nanoTime()}"
        val repo = ConversationRepository(db, SonfolioPreferences(context, prefs))
        try {
            val dao = db.recordingDao()
            val base = System.currentTimeMillis() - 60_000
            dao.insertChunk(AudioChunkEntity("c", base, base + 10_000, "/qa/restore.wav", 4_000, 16_000, 1, "ASR_READY", null))
            listOf("嗯", "周五提交预蒜", "另一条需要保留").forEachIndexed { i, text ->
                dao.insertSpeechSegments(listOf(SpeechSegmentEntity("s$i", "c", i * 2_000L, i * 2_000L + 1_000, 1f, "ASR_READY")))
                dao.insertTranscript(TranscriptEntity("t$i", "s$i", null, base + i * 2_000, base + i * 2_000 + 1_000, text, "zh", "qa", "qa", "ASR_READY", null))
            }
            repo.rebuildFromTranscripts()
            val id = repo.observeTimeline().first().single().id
            repo.applyTranscriptCorrections(mapOf("t1" to "周五提交预算"))
            assertEquals("嗯周五提交预算", repo.observeTranscript(id).first().first().text)
            repo.updateTranscriptText("t0", "周六提交预算")
            repo.updateTranscriptText("t2", "另一条手工修改")
            repo.restoreTranscriptLine("t0")
            val lines = repo.observeTranscript(id).first()
            assertEquals("嗯周五提交预蒜", lines.first().text)
            assertEquals("另一条手工修改", lines.last().text)
            assertNull(lines.first().originalText)
            assertEquals(base + 2_000, lines.first().sourceStarts["t1"])
        } finally { db.close(); context.deleteSharedPreferences(prefs) }
    }

    @Test fun editTitleUndoMarkerAndCorrectTranscriptKeepOriginal() = runBlocking {
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

            // 关键回归：rebuild 不得覆盖用户手工输入的标题
            repository.rebuildFromTranscripts()
            val afterRebuild = repository.observeConversation(id).first()!!
            assertEquals("投产准备", afterRebuild.title)
            val afterRebuildEntity = database.conversationDao().getConversation(id)!!
            assertEquals("投产准备", afterRebuildEntity.titleOverride)

            // 标记撤销：标记覆盖整段对话后 isMarked 为真，撤销后为假
            dao.insertMarker(MarkerEntity("mark", base + 500, 1_000, 0, null))
            repository.rebuildFromTranscripts()
            assertTrue(repository.observeTimeline().first().single().isMarked)
            // 再次 rebuild 后用户标题依然完好
            assertEquals("投产准备", repository.observeConversation(id).first()?.titleOverride)
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
