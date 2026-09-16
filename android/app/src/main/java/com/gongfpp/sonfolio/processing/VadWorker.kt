package com.gongfpp.sonfolio.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gongfpp.sonfolio.SonfolioApplication
import com.gongfpp.sonfolio.data.local.SpeechSegmentEntity
import java.io.File
import java.util.UUID
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

class VadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = AudioFileAccess.mutex.withLock { process() }

    private suspend fun process(): Result {
        val chunkId = inputData.getString(AUDIO_CHUNK_ID)
            ?: return Result.failure()
        val app = applicationContext as SonfolioApplication
        val dao = app.database.recordingDao()
        val chunk = dao.getChunk(chunkId) ?: return Result.failure()
        if (chunk.endedAtMillis == null || chunk.processingState == ChunkProcessing.AUDIO_DELETED) return Result.success()
        if (chunk.processingState in setOf(ChunkProcessing.ASR_READY, ChunkProcessing.ASR_RUNNING, ChunkProcessing.VAD_READY)) return Result.success()
        val file = File(chunk.localPath)
        if (!file.exists() || file.length() <= 44L) {
            dao.updateProcessingState(chunkId, ChunkProcessing.VAD_FAILED, "录音文件不存在或为空")
            return Result.failure()
        }

        dao.updateProcessingState(chunkId, ChunkProcessing.VAD_RUNNING, null)
        return try {
            val windows = InferenceClient(applicationContext).detect(file)
            val entities = windows.map { window ->
                SpeechSegmentEntity(
                    id = UUID.randomUUID().toString(),
                    audioChunkId = chunkId,
                    startOffsetMillis = window.startOffsetMillis,
                    endOffsetMillis = window.endOffsetMillis,
                    speechProbability = 1.0F,
                    processingState = ChunkProcessing.VAD_READY,
                )
            }
            app.database.withTransaction {
                dao.deleteTranscriptsForChunk(chunkId)
                dao.deleteSpeechSegments(chunkId)
                if (entities.isNotEmpty()) dao.insertSpeechSegments(entities)
                dao.updateProcessingState(chunkId, ChunkProcessing.VAD_READY, null)
            }
            app.processingScheduler.enqueueAsr(chunkId)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: OutOfMemoryError) {
            dao.updateProcessingState(chunkId, ChunkProcessing.VAD_FAILED, "VAD 内存不足")
            Result.failure()
        } catch (error: Throwable) {
            dao.updateProcessingState(
                chunkId,
                ChunkProcessing.VAD_FAILED,
                error.message ?: error.javaClass.simpleName,
            )
            Result.failure()
        }
    }

    companion object {
        const val AUDIO_CHUNK_ID = "audio_chunk_id"
        const val TAG = "sonfolio-vad"
    }
}
