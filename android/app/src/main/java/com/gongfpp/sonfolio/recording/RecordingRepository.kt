package com.gongfpp.sonfolio.recording

import com.gongfpp.sonfolio.processing.ChunkProcessing
import com.gongfpp.sonfolio.SonfolioPreferences
import com.gongfpp.sonfolio.data.local.AudioChunkEntity
import com.gongfpp.sonfolio.data.local.MarkerEntity
import com.gongfpp.sonfolio.data.local.RecordingDao
import com.gongfpp.sonfolio.data.local.RecordingGapEntity
import com.gongfpp.sonfolio.processing.ProcessingScheduler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    captureDirectory: File? = null,
    private val audioFileMutex: Mutex = com.gongfpp.sonfolio.processing.AudioFileAccess.mutex,
) {
    private val recoveryLock = Mutex()
    private val journal = captureDirectory?.let(::CaptureJournal)
    private val metadataSignals = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val metadataScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    init {
        if (journal != null) metadataScope.launch {
            for (signal in metadataSignals) {
                try { recoveryLock.withLock { flushCaptureFacts(recoverOpen = false) } }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { android.util.Log.e("CaptureJournal", "录音已落盘，元数据待重试", error) }
            }
        }
    }

    internal fun saveCaptureFact(fact: CapturedChunk) {
        checkNotNull(journal) { "录音恢复日志未配置" }.save(fact)
        metadataSignals.trySend(Unit)
    }

    internal fun checkpointCaptureMetadata() { metadataSignals.trySend(Unit) }

    private suspend fun flushCaptureFacts(recoverOpen: Boolean) {
        val log = journal ?: return
        for (fact in log.pending()) {
            val file = File(fact.path)
            val existing = recordingDao.getChunk(fact.id)
            if (existing == null) beginChunk(fact.id, fact.startedAt, file, fact.sampleRate, fact.channels, fact.zoneId, fact.offsetSeconds, fact.localStartDate)
            if (existing?.endedAtMillis != null) { log.acknowledge(fact.id); continue }
            if (fact.endedAt == null && !recoverOpen) {
                recordingDao.checkpoint(fact.id, file.length())
                continue
            }
            val recovered = fact.endedAt == null
            val bytes = if (recovered) WavChunkWriter.repairHeader(file, fact.sampleRate, fact.channels) else fact.bytes
            val end = fact.endedAt ?: (fact.startedAt + WavChunkWriter.durationMillis(bytes, fact.sampleRate, fact.channels))
            finishChunk(fact.id, end, bytes, if (recovered) {
                if (bytes > WavChunkWriter.WAV_HEADER_BYTES) ChunkProcessing.RECOVERED else ChunkProcessing.FAILED
            } else fact.state, if (recovered) "已从录音日志恢复，原音保留" else fact.error)
            if (recovered) recordingDao.openGap(end, "录音进程中断，等待重新采集")
            log.acknowledge(fact.id)
        }
    }
    fun observeStatus(): Flow<RecordingStatus> =
        combine(recordingDao.observeActiveChunk(), RecordingController.health, recordingDao.observeGaps()) { chunk, health, gaps ->
            RecordingStatus(
                isRecording = health.serviceActive,
                startedAtMillis = if (health.serviceActive) preferences?.recordingSessionStartedAtMillis ?: chunk?.startedAtMillis else chunk?.startedAtMillis,
                health = health,
                interruptionPending = gaps.any { it.endedAtMillis == null },
            )
        }

    fun observeChunks(start: Long = Long.MIN_VALUE, end: Long = Long.MAX_VALUE, limit: Int = Int.MAX_VALUE, includeDeleted: Boolean = true): Flow<List<com.gongfpp.sonfolio.AudioChunkPreview>> =
        recordingDao.observeRecentChunks(start, end, limit, includeDeleted).map { chunks ->
            chunks.map { row ->
                val chunk = row.chunk
                com.gongfpp.sonfolio.AudioChunkPreview(
                    id = chunk.id,
                    startedAtMillis = chunk.startedAtMillis,
                    endedAtMillis = chunk.endedAtMillis,
                    // 压缩音生成后即成为可播放文件；原始 WAV 只剩归档用途。
                    localPath = chunk.compressedPath ?: chunk.localPath,
                    byteSize = chunk.byteSize,
                    processingState = chunk.processingState,
                    errorMessage = chunk.errorMessage,
                    transcriptCount = row.transcriptCount,
                    visibleTranscriptCount = row.visibleTranscriptCount,
                    speechCount = row.speechCount,
                    sampleRateHz = chunk.sampleRateHz,
                    channelCount = chunk.channelCount,
                    // 保留策略退役 WAV 后 byteSize 归零，展示压缩音大小而不是「待写入」。
                    displayBytes = if (chunk.byteSize > 0L) chunk.byteSize else (chunk.compressedBytes ?: 0L),
                    audioCompressed = chunk.byteSize <= 0L && (chunk.compressedBytes ?: 0L) > 0L,
                )
            }
        }

    suspend fun beginChunk(
        id: String,
        startedAtMillis: Long,
        file: File,
        sampleRateHz: Int,
        channelCount: Int,
        zoneId: String = "",
        offsetSeconds: Int = 0,
        localStartDate: String = "",
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
                processingState = ChunkProcessing.RECORDING,
                errorMessage = null,
                recordedZoneId = zoneId,
                recordedOffsetSeconds = offsetSeconds,
                localStartDate = localStartDate,
            ),
        )
    }

    suspend fun finishChunk(
        id: String,
        endedAtMillis: Long,
        byteSize: Long,
        state: String = ChunkProcessing.RECORDED,
        errorMessage: String? = null,
    ) {
        recordingDao.finishChunk(
            id = id,
            endedAtMillis = endedAtMillis,
            byteSize = byteSize,
            processingState = state,
            errorMessage = errorMessage,
        )
        if (state == ChunkProcessing.RECORDED || state == ChunkProcessing.RECOVERED) {
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
        flushCaptureFacts(recoverOpen = true)
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
                    ChunkProcessing.RECOVERED
                } else {
                    ChunkProcessing.FAILED
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
        if (chunk.endedAtMillis == null) return
        var state = chunk.processingState
        if (state.endsWith("RUNNING")) {
            // WorkManager 会自行恢复被中断的任务；只有在确认没有未完成任务时才允许重置。
            val scheduler = processingScheduler ?: return
            if (scheduler.hasUnfinishedProcessingWork(chunkId)) return
            when (state) {
                ChunkProcessing.VAD_RUNNING -> recordingDao.updateProcessingState(chunkId, ChunkProcessing.RECORDED, "应用退出后等待重新处理")
                ChunkProcessing.ASR_RUNNING -> {
                    recordingDao.updateProcessingState(chunkId, ChunkProcessing.VAD_READY, "应用退出后等待重新处理")
                    recordingDao.resetInterruptedSpeechSegmentsFor(chunkId)
                }
            }
            state = recordingDao.getChunk(chunkId)?.processingState ?: return
        }
        if (state in setOf(ChunkProcessing.ASSEMBLY_PENDING, ChunkProcessing.ASSEMBLY_FAILED)) {
            recordingDao.updateProcessingState(chunkId, ChunkProcessing.ASSEMBLY_PENDING, "文字已保存，等待整理")
            processingScheduler?.enqueueAssembly(chunkId)
        } else if (state in setOf(ChunkProcessing.ASR_FAILED, ChunkProcessing.VAD_READY)) {
            recordingDao.updateProcessingState(chunkId, ChunkProcessing.VAD_READY, null)
            processingScheduler?.enqueueAsr(chunkId)
        } else if (state in setOf(ChunkProcessing.VAD_FAILED, ChunkProcessing.FAILED, ChunkProcessing.RECORDED, ChunkProcessing.RECOVERED)) {
            recordingDao.updateProcessingState(chunkId, ChunkProcessing.RECORDED, null)
            processingScheduler?.enqueueVad(chunkId)
        }
    }

    /**
     * 应用启动时清理孤儿 RUNNING 状态：WorkManager 会自行恢复被中断的任务，因此只有在确认
     * 该切片已没有未完成的 VAD/ASR 任务（例如任务被强停清空）时，才把状态回退并重新入队。
     */
    suspend fun recoverOrphanedRunningStates() {
        recordingDao.getChunksInRunningStates().forEach { chunk ->
            val scheduler = processingScheduler ?: return
            if (scheduler.hasUnfinishedProcessingWork(chunk.id)) return@forEach
            when (chunk.processingState) {
                ChunkProcessing.VAD_RUNNING -> {
                    recordingDao.updateProcessingState(chunk.id, ChunkProcessing.RECORDED, "应用退出后等待重新处理")
                    processingScheduler?.enqueueVad(chunk.id)
                }
                ChunkProcessing.ASR_RUNNING -> {
                    recordingDao.updateProcessingState(chunk.id, ChunkProcessing.VAD_READY, "应用退出后等待重新处理")
                    recordingDao.resetInterruptedSpeechSegmentsFor(chunk.id)
                    processingScheduler?.enqueueAsr(chunk.id)
                }
            }
        }
    }

    suspend fun enqueuePendingAsr() {
        recordingDao.getChunksWaitingForAssembly().forEach { processingScheduler?.enqueueAssembly(it) }
        recordingDao.getChunksWaitingForAsr().forEach { chunk ->
            processingScheduler?.enqueueAsr(chunk.id)
        }
        recordingDao.getChunksWaitingForCompression().forEach { chunk ->
            processingScheduler?.enqueueCompression(chunk.id)
        }
    }

    /**
     * 原音保留策略：已完成压缩且超过保留期的切片，删除未压缩 WAV（文字、标记与压缩音保留）。
     * 标记时间窗内的录音受保护；保留期为 0 表示永久保留原音。
     */
    suspend fun applyRetention(nowMillis: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val days = preferences?.retentionDays ?: 0
        if (days <= 0) return@withContext 0
        val cutoff = nowMillis - days * 86_400_000L
        val protectedIds = getMarkedChunkIds()
        var retired = 0
        // 与导出/备份/清理共用同一把文件锁，避免删除与读取并发造成半成品文件。
        audioFileMutex.withLock {
            recordingDao.getWavRetirementCandidates(cutoff).forEach { chunk ->
                if (chunk.id in protectedIds) return@forEach
                val file = File(chunk.localPath)
                // 先改元数据再删文件会丢字节引用；沿用先删文件、失败不登记的顺序。
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (!file.exists() || file.delete()) {
                        recordingDao.markWavRetired(chunk.id)
                        retired++
                    }
                }
            }
        }
        retired
    }

    /**
     * 录音已中断且服务已退出时，由用户主动结束：闭合仍处于打开状态的缺口，使其停止累计，
     * 并清除失败状态，让首页回到可重新开始的干净状态。
     */
    suspend fun endInterruptedSession(nowMillis: Long = System.currentTimeMillis()) {
        recordingDao.closeOpenGaps("INTERRUPTION", nowMillis, automatic = false)
        RecordingController.updateHealth { it.copy(failure = null) }
    }

    /** 与任意标记时间窗重叠的切片 id 集合，用于批量清理时的标记保护。 */
    suspend fun getMarkedChunkIds(): Set<String> {
        val markers = recordingDao.getMarkers()
        if (markers.isEmpty()) return emptySet()
        val chunks = recordingDao.getAllChunks()
        val protected = mutableSetOf<String>()
        for (chunk in chunks) {
            val cStart = chunk.startedAtMillis
            val cEnd = chunk.endedAtMillis ?: Long.MAX_VALUE
            for (m in markers) {
                val mStart = m.markedAtMillis - m.windowBeforeMillis
                val mEnd = m.markedAtMillis + m.windowAfterMillis
                if (cStart <= mEnd && cEnd >= mStart) {
                    protected.add(chunk.id)
                    break
                }
            }
        }
        return protected + recordingDao.getChunksInMarkedConversations()
    }

    /** Explicit audio-only cleanup. Text, markers and conversation identity are never deleted. */
    suspend fun deleteChunks(ids: Set<String>, protectMarked: Boolean): String = withContext(Dispatchers.IO) {
        val lock = audioFileMutex
        if (!lock.tryLock()) return@withContext "正在识别录音，请处理结束后再清理；未删除任何文件"
        try {
            val protected = if (protectMarked) getMarkedChunkIds() else emptySet()
            var removed = 0; var skipped = 0; var failed = 0
            ids.toList().chunked(400).flatMap { recordingDao.getChunksByIds(it) }.forEach { chunk ->
                if (chunk.id in protected || chunk.endedAtMillis == null || chunk.processingState != ChunkProcessing.ASR_READY) {
                    skipped++
                } else {
                    // 清理动作删除全部音频文件（原始 WAV 与压缩音），文字与关系永不删除。
                    val files = listOfNotNull(File(chunk.localPath), chunk.compressedPath?.let(::File))
                    // Metadata survives a failed unlink or a crash between unlink and this update.
                    // Navigation/coroutine cancellation cannot leave a successful unlink unrecorded.
                    withContext(kotlinx.coroutines.NonCancellable) {
                        if (files.all { !it.exists() || it.delete() }) {
                            recordingDao.markAudioDeleted(chunk.id)
                            removed++
                        } else failed++
                    }
                }
            }
            "已清理 ${removed} 份原音，保留全部文字；跳过 ${skipped} 份标记或未处理文件，失败 ${failed} 份"
        } finally { lock.unlock() }
    }

    /** 收集批量导出所需的切片元信息与转写文本。 */
    suspend fun collectExportItems(ids: Set<String>): List<ChunkExport> {
        val chunks = ids.toList().chunked(400).flatMap { recordingDao.getChunksByIds(it) }
        return chunks.map { chunk ->
            ChunkExport(
                id = chunk.id,
                startedAtMillis = chunk.startedAtMillis,
                endedAtMillis = chunk.endedAtMillis,
                localPath = chunk.localPath,
                texts = recordingDao.getTranscriptTextsForChunk(chunk.id),
            )
        }
    }

    companion object {
        const val DEFAULT_MARK_MINUTES = 3
        const val MAX_MARK_MINUTES = 60
    }
}
