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
    val speechCount: Int,
)

@Dao
interface RecordingDao {
    @Query("SELECT id FROM audio_chunks WHERE processingState = 'ASSEMBLY_PENDING'")
    suspend fun getChunksWaitingForAssembly(): List<String>
    @Query("SELECT * FROM audio_chunks WHERE processingState IN ('RECORDED','RECOVERED','VAD_RUNNING','VAD_READY','ASR_RUNNING') ORDER BY startedAtMillis")
    suspend fun getChunksWithPendingProcessing(): List<AudioChunkEntity>

    @Query("SELECT * FROM audio_chunks WHERE id IN (:ids)")
    suspend fun getChunksByIds(ids: List<String>): List<AudioChunkEntity>

    @Query("SELECT * FROM recording_gaps WHERE startedAtMillis <= :end AND (endedAtMillis IS NULL OR endedAtMillis >= :start) ORDER BY startedAtMillis")
    suspend fun getGapsInWindow(start: Long, end: Long): List<RecordingGapEntity>

    @Query("SELECT * FROM markers WHERE markedAtMillis - windowBeforeMillis <= :end AND markedAtMillis + windowAfterMillis >= :start ORDER BY markedAtMillis")
    suspend fun getMarkersInWindow(start: Long, end: Long): List<MarkerEntity>

    @Query("UPDATE audio_chunks SET byteSize = 0, localPath = '', compressedPath = NULL, compressedBytes = NULL, processingState = 'AUDIO_DELETED', errorMessage = NULL WHERE id = :id AND endedAtMillis IS NOT NULL")
    suspend fun markAudioDeleted(id: String)

    @Query("""SELECT * FROM audio_chunks WHERE processingState = 'ASR_READY' AND compressedPath IS NULL
        AND localPath <> '' AND endedAtMillis IS NOT NULL ORDER BY startedAtMillis ASC""")
    suspend fun getChunksWaitingForCompression(): List<AudioChunkEntity>

    @Query("UPDATE audio_chunks SET compressedPath = :path, compressedBytes = :bytes WHERE id = :id AND compressedPath IS NULL AND processingState = 'ASR_READY'")
    suspend fun setCompressedAudio(id: String, path: String, bytes: Long): Int

    /** 原始 WAV 已按保留策略删除；压缩音继续保留，文字不受影响。 */
    @Query("UPDATE audio_chunks SET byteSize = 0, localPath = '' WHERE id = :id AND compressedPath IS NOT NULL AND localPath <> ''")
    suspend fun markWavRetired(id: String)

    @Query("""SELECT * FROM audio_chunks WHERE processingState = 'ASR_READY' AND compressedPath IS NOT NULL
        AND localPath <> '' AND endedAtMillis IS NOT NULL AND endedAtMillis < :cutoff ORDER BY startedAtMillis ASC""")
    suspend fun getWavRetirementCandidates(cutoff: Long): List<AudioChunkEntity>

    @Query("""SELECT DISTINCT s.audioChunkId FROM speech_segments s JOIN transcripts t ON t.speechSegmentId = s.id
        WHERE t.conversationId IS NOT NULL AND EXISTS (
            SELECT 1 FROM transcripts seed, markers m WHERE seed.conversationId = t.conversationId
            AND seed.startedAtMillis <= m.markedAtMillis + m.windowAfterMillis
            AND seed.endedAtMillis >= m.markedAtMillis - m.windowBeforeMillis)
    """)
    suspend fun getChunksInMarkedConversations(): List<String>
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
            COUNT(c.id) AS visibleTranscriptCount, COUNT(DISTINCT s.id) AS speechCount
        FROM (SELECT * FROM audio_chunks WHERE startedAtMillis < :end
            AND (:includeDeleted OR processingState <> 'AUDIO_DELETED')
            AND COALESCE(endedAtMillis, startedAtMillis + MAX(byteSize - 44, 0) * 1000 / (sampleRateHz * channelCount * 2)) >= :start
            ORDER BY startedAtMillis DESC, id DESC LIMIT :limit) a
        LEFT JOIN speech_segments s ON s.audioChunkId = a.id
        LEFT JOIN transcripts t ON t.speechSegmentId = s.id
        LEFT JOIN conversations c ON c.id = t.conversationId
        GROUP BY a.id ORDER BY a.startedAtMillis DESC, a.id DESC
    """)
    fun observeRecentChunks(start: Long = Long.MIN_VALUE, end: Long = Long.MAX_VALUE, limit: Int = Int.MAX_VALUE, includeDeleted: Boolean = true): Flow<List<AudioChunkRow>>

    @Query("SELECT COUNT(*) FROM audio_chunks WHERE processingState <> 'AUDIO_DELETED' AND startedAtMillis < :end AND COALESCE(endedAtMillis, startedAtMillis + MAX(byteSize - 44, 0) * 1000 / (sampleRateHz * channelCount * 2)) >= :start")
    fun observeChunkCount(start: Long, end: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(byteSize + COALESCE(compressedBytes, 0)), 0) FROM audio_chunks")
    fun observeStorageBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM audio_chunks WHERE endedAtMillis < :before AND processingState <> 'AUDIO_DELETED' AND compressedPath IS NULL AND localPath <> ''")
    fun observeExpiredCount(before: Long): Flow<Int>

    @Query("""SELECT a.id FROM audio_chunks a WHERE a.processingState = 'ASR_READY'
        AND a.startedAtMillis < :end AND a.endedAtMillis >= :start AND
        ((:silence = 1 AND NOT EXISTS (SELECT 1 FROM speech_segments s WHERE s.audioChunkId = a.id))
        OR (:silence = 0 AND EXISTS (SELECT 1 FROM transcripts t JOIN speech_segments s ON s.id = t.speechSegmentId WHERE s.audioChunkId = a.id)
        AND NOT EXISTS (SELECT 1 FROM transcripts t JOIN speech_segments s ON s.id = t.speechSegmentId WHERE s.audioChunkId = a.id AND t.conversationId IS NOT NULL)))""")
    suspend fun getCleanupCandidates(start: Long, end: Long, silence: Boolean): List<String>

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
        SELECT * FROM audio_chunks
        WHERE processingState IN ('VAD_RUNNING', 'ASR_RUNNING')
        ORDER BY startedAtMillis ASC
        """,
    )
    suspend fun getChunksInRunningStates(): List<AudioChunkEntity>

    @Query(
        """
        UPDATE speech_segments
        SET processingState = 'VAD_READY'
        WHERE audioChunkId = :audioChunkId AND processingState = 'ASR_RUNNING'
        """,
    )
    suspend fun resetInterruptedSpeechSegmentsFor(audioChunkId: String)

    @Query("DELETE FROM speech_segments WHERE audioChunkId = :audioChunkId")
    suspend fun deleteSpeechSegments(audioChunkId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpeechSegments(segments: List<SpeechSegmentEntity>)

    @Query(
        """
        SELECT * FROM transcripts
        WHERE speechSegmentId IN (
            SELECT id FROM speech_segments WHERE audioChunkId = :audioChunkId
        )
        """,
    )
    suspend fun getTranscriptsForChunk(audioChunkId: String): List<TranscriptEntity>

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

    @Query("SELECT * FROM markers ORDER BY markedAtMillis ASC")
    suspend fun getMarkers(): List<MarkerEntity>

    @Query("SELECT * FROM audio_chunks")
    suspend fun getAllChunks(): List<AudioChunkEntity>

    @Query("DELETE FROM audio_chunks WHERE id IN (:ids)")
    suspend fun deleteChunks(ids: List<String>)

    @Transaction
    suspend fun deleteChunksByIds(ids: List<String>) {
        ids.forEach { id ->
            deleteTranscriptsForChunk(id)
            deleteSpeechSegments(id)
        }
        deleteChunks(ids)
    }

    @Query(
        """
        SELECT text FROM transcripts t
        JOIN speech_segments s ON t.speechSegmentId = s.id
        WHERE s.audioChunkId = :chunkId
        ORDER BY s.startOffsetMillis ASC
        """,
    )
    suspend fun getTranscriptTextsForChunk(chunkId: String): List<String>
}
