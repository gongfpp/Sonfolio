package com.gongfpp.sonfolio.data.local

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class TranscriptAudioRow(
    @ColumnInfo(name = "transcriptId") val transcriptId: String,
    @ColumnInfo(name = "conversationId") val conversationId: String?,
    @ColumnInfo(name = "startedAtMillis") val startedAtMillis: Long,
    @ColumnInfo(name = "endedAtMillis") val endedAtMillis: Long,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "localPath") val localPath: String,
    @ColumnInfo(name = "chunkStartedAtMillis") val chunkStartedAtMillis: Long,
    @ColumnInfo(name = "isMarked") val isMarked: Boolean,
    @ColumnInfo(name = "originalText") val originalText: String? = null,
    /** 所属切片录音发生时的时区；空值按设备时区回退。 */
    @ColumnInfo(name = "recordedZoneId") val recordedZoneId: String = "",
)

data class TranscriptSearchRow(
    @ColumnInfo(name = "conversationId") val conversationId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "hitCount") val hitCount: Int,
    @ColumnInfo(name = "titleHit") val titleHit: Int,
    @ColumnInfo(name = "latestHitMillis") val latestHitMillis: Long,
    @ColumnInfo(name = "snippetTranscriptId") val snippetTranscriptId: String,
    @ColumnInfo(name = "snippetText") val snippetText: String,
    @ColumnInfo(name = "snippetStartedAtMillis") val snippetStartedAtMillis: Long,
    @ColumnInfo(name = "isMarked") val isMarked: Boolean,
)

data class CalendarSpan(val start: Long, val end: Long, val organized: Boolean)

/** 修正转写时定位受影响的时间窗与所属对话。 */
data class TranscriptRef(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "conversationId") val conversationId: String?,
    @ColumnInfo(name = "startedAtMillis") val startedAtMillis: Long,
    @ColumnInfo(name = "endedAtMillis") val endedAtMillis: Long,
)

/** 修正前读取文本基线：originalText 非空表示此前已修正过，用它作为个人词汇的原始识别基线。 */
data class TranscriptCorrectionRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "originalText") val originalText: String?,
)

@Dao
interface ConversationDao {
    @Query("""SELECT MIN(startedAtMillis) AS `start`, MAX(COALESCE(endedAtMillis, startedAtMillis + 1)) AS `end`, 0 AS organized
        FROM audio_chunks WHERE processingState <> 'AUDIO_DELETED' GROUP BY COALESCE(NULLIF(localStartDate, ''), date(startedAtMillis / 1000, 'unixepoch', 'localtime'))
        UNION ALL SELECT MIN(startedAtMillis) AS `start`, MAX(endedAtMillis) AS `end`, 1 AS organized
        FROM conversations GROUP BY COALESCE(NULLIF(localStartDate, ''), date(startedAtMillis / 1000, 'unixepoch', 'localtime'))""")
    fun observeCalendarSpans(): Flow<List<CalendarSpan>>

    @Query("SELECT * FROM conversations WHERE id = COALESCE((SELECT canonicalId FROM conversation_aliases WHERE oldId = :id), :id)")
    fun observeConversation(id: String): Flow<ConversationEntity?>
    @Query("SELECT * FROM conversations WHERE startedAtMillis <= :end AND endedAtMillis >= :start AND id LIKE 'auto-%'")
    suspend fun getConversationsInWindow(start: Long, end: Long): List<ConversationEntity>

    @Query("SELECT * FROM conversation_aliases")
    fun observeAliases(): Flow<List<ConversationAliasEntity>>

    @Query("SELECT canonicalId FROM conversation_aliases WHERE oldId = :id")
    suspend fun resolveAlias(id: String): String?

    @Query("UPDATE conversation_aliases SET canonicalId = :canonical WHERE canonicalId = :old")
    suspend fun redirectAliases(old: String, canonical: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAliases(aliases: List<ConversationAliasEntity>)

    @Query("DELETE FROM conversation_aliases WHERE oldId IN (:ids)")
    suspend fun removeAliasesForCanonicalIds(ids: List<String>)

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteConversations(ids: List<String>)

    @Query("DELETE FROM conversation_summaries WHERE conversationId IN (:ids)")
    suspend fun deleteSummaries(ids: List<String>)

    @Query("UPDATE transcripts SET conversationId = NULL WHERE id IN (:ids)")
    suspend fun detachTranscripts(ids: List<String>)

    @Query("DELETE FROM daily_journals WHERE localDate = :date")
    suspend fun deleteJournal(date: String)

    @Query("""SELECT t.id AS transcriptId, t.conversationId, t.startedAtMillis, t.endedAtMillis,
        t.text, t.originalText, a.localPath, a.startedAtMillis AS chunkStartedAtMillis, 0 AS isMarked, a.recordedZoneId
        FROM transcripts t JOIN speech_segments s ON s.id = t.speechSegmentId JOIN audio_chunks a ON a.id = s.audioChunkId
        WHERE t.processingState = 'ASR_READY' AND t.text <> '' AND t.startedAtMillis <= :end AND t.endedAtMillis >= :start
        ORDER BY t.startedAtMillis, t.id""")
    suspend fun getReadyRowsInWindow(start: Long, end: Long): List<TranscriptAudioRow>
    @Query("SELECT * FROM summary_runs")
    suspend fun getSummaryRuns(): List<SummaryRunEntity>

    @Query("SELECT * FROM summary_runs WHERE sourceKey IN (:keys)")
    suspend fun getSummaryRunsForKeys(keys: List<String>): List<SummaryRunEntity>

    @Query("""SELECT t.id AS transcriptId, t.conversationId, t.startedAtMillis, t.endedAtMillis,
        t.text, t.originalText, a.localPath, a.startedAtMillis AS chunkStartedAtMillis, 0 AS isMarked, a.recordedZoneId
        FROM transcripts t JOIN speech_segments s ON s.id = t.speechSegmentId JOIN audio_chunks a ON a.id = s.audioChunkId
        WHERE t.processingState = 'ASR_READY' AND t.text <> '' AND t.conversationId IN (:ids)
        ORDER BY t.startedAtMillis, t.id""")
    suspend fun getReadyRowsForConversations(ids: List<String>): List<TranscriptAudioRow>

    @Query("SELECT * FROM summary_runs WHERE sourceKey = :key")
    fun observeSummaryRun(key: String): Flow<SummaryRunEntity?>

    @Query("SELECT * FROM summary_runs WHERE sourceKey = :key")
    suspend fun getSummaryRun(key: String): SummaryRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSummaryRun(value: SummaryRunEntity)

    @Query("UPDATE summary_runs SET state = :state, message = :message, updatedAtMillis = :now WHERE sourceKey = :key")
    suspend fun updateSummaryRun(key: String, state: String, message: String?, now: Long)

    @Query("SELECT * FROM conversations WHERE startedAtMillis < :end AND endedAtMillis >= :start ORDER BY startedAtMillis ASC")
    fun observeTimeline(start: Long = Long.MIN_VALUE, end: Long = Long.MAX_VALUE): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT DISTINCT target.conversationId
        FROM transcripts target
        WHERE target.conversationId IS NOT NULL
          AND EXISTS (
              SELECT 1
              FROM transcripts seed, markers m
              WHERE seed.conversationId = target.conversationId
                AND seed.startedAtMillis <= m.markedAtMillis + m.windowAfterMillis
                AND seed.endedAtMillis >= m.markedAtMillis - m.windowBeforeMillis
          )
        """,
    )
    fun observeMarkedConversationIds(): Flow<List<String>>

    @Query("SELECT * FROM markers ORDER BY markedAtMillis ASC")
    suspend fun getMarkers(): List<MarkerEntity>

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conversations: List<ConversationEntity>)

    @Query(
        """
        SELECT
            t.id AS transcriptId,
            t.conversationId AS conversationId,
            t.startedAtMillis AS startedAtMillis,
            t.endedAtMillis AS endedAtMillis,
            t.text AS text,
            t.originalText AS originalText,
            a.localPath AS localPath,
            a.startedAtMillis AS chunkStartedAtMillis,
            a.recordedZoneId,
            CASE WHEN EXISTS (
                SELECT 1
                FROM transcripts seed, markers m
                WHERE seed.conversationId = t.conversationId
                  AND seed.startedAtMillis <= m.markedAtMillis + m.windowAfterMillis
                  AND seed.endedAtMillis >= m.markedAtMillis - m.windowBeforeMillis
            ) THEN 1 ELSE 0 END AS isMarked
        FROM transcripts t
        JOIN speech_segments s ON s.id = t.speechSegmentId
        JOIN audio_chunks a ON a.id = s.audioChunkId
        WHERE t.conversationId = COALESCE((SELECT canonicalId FROM conversation_aliases WHERE oldId = :conversationId), :conversationId)
          AND t.processingState = 'ASR_READY'
          AND t.text <> ''
        ORDER BY t.startedAtMillis ASC
        """,
    )
    fun observeTranscriptRows(conversationId: String): Flow<List<TranscriptAudioRow>>

    @Query("UPDATE transcripts SET conversationId = :conversationId WHERE id = :transcriptId")
    suspend fun attachTranscript(transcriptId: String, conversationId: String)

    @Query("UPDATE transcripts SET conversationId = :conversationId WHERE id IN (:transcriptIds)")
    suspend fun attachTranscripts(transcriptIds: List<String>, conversationId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversationSummaries(summaries: List<ConversationSummaryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyJournal(journal: DailyJournalEntity)

    @RawQuery(observedEntities = [TranscriptEntity::class, ConversationEntity::class, MarkerEntity::class])
    fun observeSearch(query: SupportSQLiteQuery): Flow<List<TranscriptSearchRow>>

    @Query("SELECT * FROM daily_journals WHERE localDate = :localDate LIMIT 1")
    fun observeDailyJournal(localDate: String): Flow<DailyJournalEntity?>

    @Query("SELECT * FROM daily_journals WHERE localDate = :localDate LIMIT 1")
    suspend fun getDailyJournal(localDate: String): DailyJournalEntity?

    @Query("SELECT * FROM conversation_summaries WHERE conversationId = COALESCE((SELECT canonicalId FROM conversation_aliases WHERE oldId = :conversationId), :conversationId) LIMIT 1")
    fun observeConversationSummary(conversationId: String): Flow<ConversationSummaryEntity?>

    @Query("SELECT * FROM conversations WHERE id = COALESCE((SELECT canonicalId FROM conversation_aliases WHERE oldId = :id), :id) LIMIT 1")
    suspend fun getConversation(id: String): ConversationEntity?

    /** 用户修改标题写入 titleOverride；自动生成的 generatedTitle 不被触碰。 */
    @Query("UPDATE conversations SET titleOverride = :title WHERE id = COALESCE((SELECT canonicalId FROM conversation_aliases WHERE oldId = :id), :id)")
    suspend fun updateConversationTitle(id: String, title: String)

    /** 用户放弃手工标题，回到自动标题。 */
    @Query("UPDATE conversations SET titleOverride = NULL WHERE id = COALESCE((SELECT canonicalId FROM conversation_aliases WHERE oldId = :id), :id)")
    suspend fun resetConversationTitle(id: String)

    @Query("UPDATE conversations SET note = :note WHERE id = COALESCE((SELECT canonicalId FROM conversation_aliases WHERE oldId = :id), :id)")
    suspend fun updateConversationNote(id: String, note: String?)

    /** 修正转写：首次修正时把原文字存进 originalText，之后只改 text，原始版本始终保留。 */
    @Query("UPDATE transcripts SET originalText = CASE WHEN originalText IS NULL THEN text ELSE originalText END, text = :text WHERE id = :id")
    suspend fun updateTranscriptText(id: String, text: String)

    /** 清空某条转写文字（被合并进相邻展示行后不再单独显示；originalText 仍保留）。 */
    @Query("UPDATE transcripts SET text = '' WHERE id = :id")
    suspend fun clearTranscriptText(id: String)

    @Query("SELECT id, conversationId, startedAtMillis, endedAtMillis FROM transcripts WHERE id = :id LIMIT 1")
    suspend fun getTranscriptWindow(id: String): TranscriptRef?

    @Query("SELECT id, text, originalText FROM transcripts WHERE id = :id LIMIT 1")
    suspend fun getTranscriptForCorrection(id: String): TranscriptCorrectionRow?

    /** 撤销标记：删除时间窗与这段对话重叠的标记。 */
    @Query("DELETE FROM markers WHERE markedAtMillis + windowAfterMillis >= :start AND markedAtMillis - windowBeforeMillis <= :end")
    suspend fun deleteMarkersOverlapping(start: Long, end: Long)
}
