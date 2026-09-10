package com.gongfpp.sonfolio.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
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
