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
)

data class TranscriptSearchRow(
    @ColumnInfo(name = "transcriptId") val transcriptId: String,
    @ColumnInfo(name = "conversationId") val conversationId: String,
    @ColumnInfo(name = "startedAtMillis") val startedAtMillis: Long,
    @ColumnInfo(name = "endedAtMillis") val endedAtMillis: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "isMarked") val isMarked: Boolean,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM summary_runs")
    suspend fun getSummaryRuns(): List<SummaryRunEntity>

    @Query("SELECT * FROM summary_runs WHERE sourceKey = :key")
    fun observeSummaryRun(key: String): Flow<SummaryRunEntity?>

    @Query("SELECT * FROM summary_runs WHERE sourceKey = :key")
    suspend fun getSummaryRun(key: String): SummaryRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSummaryRun(value: SummaryRunEntity)

    @Query("UPDATE summary_runs SET state = :state, message = :message, updatedAtMillis = :now WHERE sourceKey = :key")
    suspend fun updateSummaryRun(key: String, state: String, message: String?, now: Long)

    @Query("SELECT * FROM conversations ORDER BY startedAtMillis ASC")
    fun observeTimeline(): Flow<List<ConversationEntity>>

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
            a.localPath AS localPath,
            a.startedAtMillis AS chunkStartedAtMillis,
            0 AS isMarked
        FROM transcripts t
        JOIN speech_segments s ON s.id = t.speechSegmentId
        JOIN audio_chunks a ON a.id = s.audioChunkId
        WHERE t.processingState = 'ASR_READY'
          AND t.text <> ''
        ORDER BY t.startedAtMillis ASC, t.id ASC
        """,
    )
    suspend fun getReadyTranscriptRows(): List<TranscriptAudioRow>

    @Query(
        """
        SELECT
            t.id AS transcriptId,
            t.conversationId AS conversationId,
            t.startedAtMillis AS startedAtMillis,
            t.endedAtMillis AS endedAtMillis,
            t.text AS text,
            a.localPath AS localPath,
            a.startedAtMillis AS chunkStartedAtMillis,
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
        WHERE t.conversationId = :conversationId
          AND t.processingState = 'ASR_READY'
          AND t.text <> ''
        ORDER BY t.startedAtMillis ASC
        """,
    )
    fun observeTranscriptRows(conversationId: String): Flow<List<TranscriptAudioRow>>

    @Query("UPDATE transcripts SET conversationId = NULL WHERE conversationId LIKE 'auto-%'")
    suspend fun clearGeneratedConversationLinks()

    @Query("DELETE FROM conversations WHERE id LIKE 'auto-%'")
    suspend fun deleteGeneratedConversations()

    @Query("DELETE FROM conversations WHERE id LIKE 'demo-%'")
    suspend fun deleteDemoConversations()

    @Query("DELETE FROM daily_journals WHERE id LIKE 'journal-%'")
    suspend fun deleteGeneratedDailyJournals()

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

    @Query("SELECT * FROM conversation_summaries WHERE conversationId = :conversationId LIMIT 1")
    fun observeConversationSummary(conversationId: String): Flow<ConversationSummaryEntity?>
}
