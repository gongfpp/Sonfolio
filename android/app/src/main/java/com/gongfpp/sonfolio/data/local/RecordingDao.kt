package com.gongfpp.sonfolio.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Embedded
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class AudioChunkRow(
    @Embedded val chunk: AudioChunkEntity,
    val transcriptCount: Int,
    val visibleTranscriptCount: Int,
)

@Dao
interface RecordingDao {
    @Query("SELECT t.id FROM transcripts t JOIN speech_segments s ON t.speechSegmentId = s.id WHERE s.audioChunkId = :chunkId")
    suspend fun getTranscriptsForSummaryChunk(chunkId: String): List<String>
    @Query(
        """
        SELECT * FROM audio_chunks
        WHERE processingState = 'RECORDING' AND endedAtMillis IS NULL
        ORDER BY startedAtMillis DESC
        LIMIT 1
        """,
    )
    fun observeActiveChunk(): Flow<AudioChunkEntity?>

    @Query(
        """
        SELECT * FROM audio_chunks
        WHERE processingState = 'RECORDING' AND endedAtMillis IS NULL
        ORDER BY startedAtMillis ASC
        """,
    )
    suspend fun getDanglingChunks(): List<AudioChunkEntity>

    @Query("SELECT * FROM audio_chunks WHERE id = :id LIMIT 1")
    suspend fun getChunk(id: String): AudioChunkEntity?

    @Query("""
        SELECT a.*, COUNT(t.id) AS transcriptCount,
            COUNT(c.id) AS visibleTranscriptCount
        FROM audio_chunks a
        LEFT JOIN speech_segments s ON s.audioChunkId = a.id
        LEFT JOIN transcripts t ON t.speechSegmentId = s.id
        LEFT JOIN conversations c ON c.id = t.conversationId
        GROUP BY a.id ORDER BY a.startedAtMillis DESC
    """)
    fun observeRecentChunks(): Flow<List<AudioChunkRow>>

    @Query("SELECT * FROM recording_gaps ORDER BY startedAtMillis DESC")
    fun observeGaps(): Flow<List<RecordingGapEntity>>

    @Query("SELECT * FROM recording_gaps ORDER BY startedAtMillis ASC")
    suspend fun getGaps(): List<RecordingGapEntity>

    @Query("SELECT * FROM recording_gaps WHERE endedAtMillis IS NULL AND kind = :kind ORDER BY startedAtMillis ASC LIMIT 1")
    suspend fun getOpenGap(kind: String): RecordingGapEntity?

    @Query("UPDATE recording_gaps SET endedAtMillis = MAX(startedAtMillis + 1, :endedAtMillis), recoveredAutomatically = :automatic WHERE endedAtMillis IS NULL AND kind = :kind AND startedAtMillis <= :endedAtMillis")
    suspend fun closeOpenGaps(kind: String, endedAtMillis: Long, automatic: Boolean)

    @Query("UPDATE recording_gaps SET startedAtMillis = MIN(startedAtMillis, :startedAtMillis) WHERE id = :id")
    suspend fun extendGapStart(id: String, startedAtMillis: Long)

    @Transaction
    suspend fun openGap(startedAtMillis: Long, reason: String, kind: String = "INTERRUPTION") {
        val existing = getOpenGap(kind)
        if (existing == null) insertGap(RecordingGapEntity(java.util.UUID.randomUUID().toString(), startedAtMillis, null, reason, false, kind))
        else extendGapStart(existing.id, startedAtMillis)
    }

    @Query("UPDATE audio_chunks SET byteSize = :byteSize WHERE id = :id AND endedAtMillis IS NULL")
    suspend fun checkpoint(id: String, byteSize: Long)

    @Query(
        """
        SELECT * FROM audio_chunks
        WHERE processingState IN ('RECORDED', 'RECOVERED')
        ORDER BY startedAtMillis ASC
        """,
    )
    suspend fun getChunksWaitingForVad(): List<AudioChunkEntity>

    @Query(
        """
        SELECT * FROM audio_chunks
        WHERE processingState = 'VAD_READY'
        ORDER BY startedAtMillis ASC
        """,
    )
    suspend fun getChunksWaitingForAsr(): List<AudioChunkEntity>

    @Query(
        """
        SELECT * FROM speech_segments
        WHERE audioChunkId = :audioChunkId
        ORDER BY startOffsetMillis ASC
        """,
    )
    suspend fun getSpeechSegments(audioChunkId: String): List<SpeechSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChunk(chunk: AudioChunkEntity)

    @Query(
        """
        UPDATE audio_chunks
        SET endedAtMillis = :endedAtMillis,
            byteSize = :byteSize,
            processingState = :processingState,
            errorMessage = :errorMessage
        WHERE id = :id
        """,
    )
    suspend fun finishChunk(
        id: String,
        endedAtMillis: Long,
        byteSize: Long,
        processingState: String,
        errorMessage: String?,
    )

    @Query(
        """
        UPDATE audio_chunks
        SET processingState = :processingState,
            errorMessage = :errorMessage
        WHERE id = :id
        """,
    )
    suspend fun updateProcessingState(
        id: String,
        processingState: String,
        errorMessage: String?,
    )

    @Query(
        """
        UPDATE audio_chunks
        SET processingState = CASE
                WHEN processingState = 'VAD_RUNNING' THEN 'RECORDED'
                WHEN processingState = 'ASR_RUNNING' THEN 'VAD_READY'
                ELSE processingState
            END,
            errorMessage = CASE
                WHEN processingState IN ('VAD_RUNNING', 'ASR_RUNNING')
                    THEN '应用退出后等待重新处理'
                ELSE errorMessage
            END
        WHERE processingState IN ('VAD_RUNNING', 'ASR_RUNNING')
        """,
    )
    suspend fun resetInterruptedProcessing()

    @Query(
        """
        UPDATE speech_segments
        SET processingState = 'VAD_READY'
        WHERE processingState = 'ASR_RUNNING'
        """,
    )
    suspend fun resetInterruptedSpeechSegments()

    @Query("DELETE FROM speech_segments WHERE audioChunkId = :audioChunkId")
    suspend fun deleteSpeechSegments(audioChunkId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpeechSegments(segments: List<SpeechSegmentEntity>)

    @Query(
        """
        DELETE FROM transcripts
        WHERE speechSegmentId IN (
            SELECT id FROM speech_segments WHERE audioChunkId = :audioChunkId
        )
        """,
    )
    suspend fun deleteTranscriptsForChunk(audioChunkId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscript(transcript: TranscriptEntity)

    @Query(
        """
        UPDATE speech_segments
        SET processingState = :processingState
        WHERE id = :id
        """,
    )
    suspend fun updateSpeechSegmentState(id: String, processingState: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMarker(marker: MarkerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGap(gap: RecordingGapEntity)
}
