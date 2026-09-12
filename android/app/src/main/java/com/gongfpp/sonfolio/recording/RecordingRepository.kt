package com.gongfpp.sonfolio.recording

import com.gongfpp.sonfolio.SonfolioPreferences
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecordingRepository(
    private val recordingDao: RecordingDao,
    private val processingScheduler: ProcessingScheduler? = null,
    private val preferences: SonfolioPreferences? = null,
) {
    private val recoveryLock = Mutex()
    fun observeStatus(): Flow<RecordingStatus> =
        combine(recordingDao.observeActiveChunk(), RecordingController.health, recordingDao.observeGaps()) { chunk, health, gaps ->
            RecordingStatus(
                isRecording = health.serviceActive,
                startedAtMillis = chunk?.let {
                    preferences?.recordingSessionStartedAtMillis ?: it.startedAtMillis
                },
                activeChunkId = chunk?.id,
                health = health,
                interruptionPending = gaps.any { it.endedAtMillis == null },
            )
        }

    fun observeChunks(): Flow<List<com.gongfpp.sonfolio.AudioChunkPreview>> =
        recordingDao.observeRecentChunks().map { chunks ->
            chunks.map { row ->
                val chunk = row.chunk
                com.gongfpp.sonfolio.AudioChunkPreview(
                    id = chunk.id,
                    startedAtMillis = chunk.startedAtMillis,
                    endedAtMillis = chunk.endedAtMillis,
                    localPath = chunk.localPath,
                    byteSize = chunk.byteSize,
                    processingState = chunk.processingState,
                    errorMessage = chunk.errorMessage,
                    transcriptCount = row.transcriptCount,
                    visibleTranscriptCount = row.visibleTranscriptCount,
                    sampleRateHz = chunk.sampleRateHz,
                    channelCount = chunk.channelCount,
                )
            }
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

    suspend fun markNow(
        markedAtMillis: Long = System.currentTimeMillis(),
        windowMinutes: Int = DEFAULT_MARK_MINUTES,
    ) {
        val windowMillis = windowMinutes.coerceIn(1, MAX_MARK_MINUTES) * 60 * 1_000L
        recordingDao.insertMarker(
            MarkerEntity(
                id = UUID.randomUUID().toString(),
                markedAtMillis = markedAtMillis,
                windowBeforeMillis = windowMillis,
                windowAfterMillis = 0L,
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
        skipWhenServiceRunning: Boolean = false,
    ): Int = withContext(Dispatchers.IO) { recoveryLock.withLock {
        if (skipWhenServiceRunning && RecordingService.isRunningInProcess) return@withLock 0
        val chunks = recordingDao.getDanglingChunks()
        chunks.forEach { chunk ->
            val file = File(chunk.localPath)
            val byteSize = WavChunkWriter.repairHeader(
                file = file,
                sampleRateHz = chunk.sampleRateHz,
                channelCount = chunk.channelCount,
            )
            // 修复文件头会改变修改时间；录到何时应由真实 PCM 样本数计算。
            val lastWriteAt = chunk.startedAtMillis + WavChunkWriter.durationMillis(
                byteSize, chunk.sampleRateHz, chunk.channelCount,
            )
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
            if (recoveryStartedAtMillis > lastWriteAt) recordingDao.openGap(lastWriteAt, "录音服务异常退出，等待恢复采集")
        }
        chunks.size
    } }

    suspend fun enqueuePendingVad() {
        recordingDao.getChunksWaitingForVad().forEach { chunk ->
            processingScheduler?.enqueueVad(chunk.id)
        }
    }

    suspend fun retryProcessing(chunkId: String) {
        val chunk = recordingDao.getChunk(chunkId) ?: return
        if (chunk.endedAtMillis == null || chunk.processingState.endsWith("RUNNING")) return
        if (chunk.processingState == "ASR_FAILED") {
            recordingDao.updateProcessingState(chunkId, "VAD_READY", null)
            processingScheduler?.enqueueAsr(chunkId)
        } else if (chunk.processingState in setOf("VAD_FAILED", "FAILED", "RECORDED", "RECOVERED")) {
            recordingDao.updateProcessingState(chunkId, "RECORDED", null)
            processingScheduler?.enqueueVad(chunkId)
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
        const val DEFAULT_MARK_MINUTES = 3
        const val MAX_MARK_MINUTES = 20
    }
}
