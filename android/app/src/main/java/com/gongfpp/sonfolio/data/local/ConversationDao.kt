package com.gongfpp.sonfolio.data.local

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class TranscriptAudioRow(
    @ColumnInfo(name = "transcriptId") val transcriptId: String,
    @ColumnInfo(name = "conversationId") val conversationId: String?,
    @ColumnInfo(name = "startedAtMillis") val startedAtMillis: Long,
    @ColumnInfo(name = "endedAtMillis") val endedAtMillis: Long,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "localPath") val localPath: String,
    @ColumnInfo(name = "chunkStartedAtMillis") val chunkStartedAtMillis: Long,
)

data class TranscriptSearchRow(
    @ColumnInfo(name = "transcriptId") val transcriptId: String,
    @ColumnInfo(name = "conversationId") val conversationId: String,
    @ColumnInfo(name = "startedAtMillis") val startedAtMillis: Long,
    @ColumnInfo(name = "endedAtMillis") val endedAtMillis: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "text") val text: String,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY startedAtMillis ASC")
    fun observeTimeline(): Flow<List<ConversationEntity>>

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
            a.startedAtMillis AS chunkStartedAtMillis
        FROM transcripts t
        JOIN speech_segments s ON s.id = t.speechSegmentId
        JOIN audio_chunks a ON a.id = s.audioChunkId
        WHERE t.processingState = 'ASR_READY'
          AND t.text <> ''
        ORDER BY t.startedAtMillis ASC
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
            a.startedAtMillis AS chunkStartedAtMillis
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

    @Query("UPDATE transcripts SET conversationId = :conversationId WHERE id = :transcriptId")
    suspend fun attachTranscript(transcriptId: String, conversationId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversationSummaries(summaries: List<ConversationSummaryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyJournal(journal: DailyJournalEntity)

    @Query(
        """
        SELECT
            t.id AS transcriptId,
            c.id AS conversationId,
            t.startedAtMillis AS startedAtMillis,
            t.endedAtMillis AS endedAtMillis,
            c.title AS title,
            t.text AS text
        FROM transcripts t
        JOIN conversations c ON c.id = t.conversationId
        WHERE t.processingState = 'ASR_READY'
          AND (:term1 = '' OR t.text LIKE '%' || :term1 || '%')
          AND (:term2 = '' OR t.text LIKE '%' || :term2 || '%')
          AND (:term3 = '' OR t.text LIKE '%' || :term3 || '%')
          AND (:term4 = '' OR t.text LIKE '%' || :term4 || '%')
        ORDER BY t.startedAtMillis DESC
        LIMIT 100
        """,
    )
    fun observeSearch(
        term1: String,
        term2: String,
        term3: String,
        term4: String,
    ): Flow<List<TranscriptSearchRow>>

    @Query("SELECT * FROM daily_journals WHERE localDate = :localDate LIMIT 1")
    fun observeDailyJournal(localDate: String): Flow<DailyJournalEntity?>

    @Query("SELECT * FROM conversation_summaries WHERE conversationId = :conversationId LIMIT 1")
    fun observeConversationSummary(conversationId: String): Flow<ConversationSummaryEntity?>
}
