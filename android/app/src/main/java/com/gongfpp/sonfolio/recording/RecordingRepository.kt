package com.gongfpp.sonfolio.recording

import com.gongfpp.sonfolio.data.local.AudioChunkEntity
import com.gongfpp.sonfolio.data.local.MarkerEntity
import com.gongfpp.sonfolio.data.local.RecordingDao
import com.gongfpp.sonfolio.data.local.RecordingGapEntity
import com.gongfpp.sonfolio.processing.ProcessingScheduler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RecordingRepository(
    private val recordingDao: RecordingDao,
    private val processingScheduler: ProcessingScheduler? = null,
) {
    fun observeStatus(): Flow<RecordingStatus> =
        recordingDao.observeActiveChunk().map { chunk ->
            RecordingStatus(
                isRecording = chunk != null,
                startedAtMillis = chunk?.startedAtMillis,
                activeChunkId = chunk?.id,
            )
        }

    suspend fun beginChunk(
        id: String,
        startedAtMillis: Long,
        file: File,
        sampleRateHz: Int,
        channelCount: Int,
    ) {
        recordingDao.insertChunk(
            AudioChunkEntity(
                id = id,
                startedAtMillis = startedAtMillis,
                endedAtMillis = null,
                localPath = file.absolutePath,
                byteSize = 0,
                sampleRateHz = sampleRateHz,
                channelCount = channelCount,
                processingState = "RECORDING",
                errorMessage = null,
            ),
        )
    }

    suspend fun finishChunk(
        id: String,
        endedAtMillis: Long,
        byteSize: Long,
        state: String = "RECORDED",
        errorMessage: String? = null,
    ) {
        recordingDao.finishChunk(
            id = id,
            endedAtMillis = endedAtMillis,
            byteSize = byteSize,
            processingState = state,
            errorMessage = errorMessage,
        )
        if (state == "RECORDED" || state == "RECOVERED") {
            processingScheduler?.enqueueVad(id)
        }
    }

    suspend fun markNow(markedAtMillis: Long = System.currentTimeMillis()) {
        recordingDao.insertMarker(
            MarkerEntity(
                id = UUID.randomUUID().toString(),
                markedAtMillis = markedAtMillis,
                windowBeforeMillis = MARK_WINDOW_MILLIS,
                windowAfterMillis = MARK_WINDOW_MILLIS,
                note = null,
            ),
        )
    }

    suspend fun recordGap(
        startedAtMillis: Long,
        endedAtMillis: Long,
        reason: String,
        recoveredAutomatically: Boolean,
    ) {
        recordingDao.insertGap(
            RecordingGapEntity(
                id = UUID.randomUUID().toString(),
                startedAtMillis = startedAtMillis,
                endedAtMillis = maxOf(startedAtMillis + 1, endedAtMillis),
                reason = reason,
                recoveredAutomatically = recoveredAutomatically,
            ),
        )
    }

    suspend fun recoverDanglingChunks(
        recoveryStartedAtMillis: Long = System.currentTimeMillis(),
    ): Int = withContext(Dispatchers.IO) {
        val chunks = recordingDao.getDanglingChunks()
        chunks.forEach { chunk ->
            val file = File(chunk.localPath)
            val byteSize = WavChunkWriter.repairHeader(
                file = file,
                sampleRateHz = chunk.sampleRateHz,
                channelCount = chunk.channelCount,
            )
            val lastWriteAt = file.lastModified()
                .takeIf { it >= chunk.startedAtMillis }
                ?: chunk.startedAtMillis
            finishChunk(
                id = chunk.id,
                endedAtMillis = lastWriteAt,
                byteSize = byteSize,
                state = if (byteSize > WavChunkWriter.WAV_HEADER_BYTES) {
                    "RECOVERED"
                } else {
                    "FAILED"
                },
                errorMessage = "录音服务异常退出，启动时已修复切片",
            )
            recordGap(
                startedAtMillis = lastWriteAt,
                endedAtMillis = recoveryStartedAtMillis,
                reason = "录音服务异常退出后恢复",
                recoveredAutomatically = true,
            )
        }
        chunks.size
    }

    suspend fun enqueuePendingVad() {
        recordingDao.getChunksWaitingForVad().forEach { chunk ->
            processingScheduler?.enqueueVad(chunk.id)
        }
    }

    suspend fun resetInterruptedProcessing() {
        recordingDao.resetInterruptedProcessing()
        recordingDao.resetInterruptedSpeechSegments()
    }

    suspend fun enqueuePendingAsr() {
        recordingDao.getChunksWaitingForAsr().forEach { chunk ->
            processingScheduler?.enqueueAsr(chunk.id)
        }
    }

    companion object {
        const val MARK_WINDOW_MILLIS = 3 * 60 * 1_000L
    }
}
