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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMarker(marker: MarkerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGap(gap: RecordingGapEntity)
}
