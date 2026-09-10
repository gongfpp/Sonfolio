package com.gongfpp.sonfolio.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gongfpp.sonfolio.SonfolioApplication
import com.gongfpp.sonfolio.data.local.TranscriptEntity
import java.io.File
import java.util.UUID

class AsrWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val chunkId = inputData.getString(AUDIO_CHUNK_ID)
            ?: return Result.failure()
        val app = applicationContext as SonfolioApplication
        val dao = app.database.recordingDao()
        val chunk = dao.getChunk(chunkId) ?: return Result.failure()
        val file = File(chunk.localPath)
        if (!file.exists() || file.length() <= 44L) {
            dao.updateProcessingState(chunkId, "ASR_FAILED", "录音文件不存在或为空")
            return Result.failure()
        }

        dao.updateProcessingState(chunkId, "ASR_RUNNING", null)
        return try {
            val segments = dao.getSpeechSegments(chunkId)
            dao.deleteTranscriptsForChunk(chunkId)
            SenseVoiceAsrProcessor(applicationContext).use { processor ->
                segments.forEach { segment ->
                    dao.updateSpeechSegmentState(segment.id, "ASR_RUNNING")
                    val text = processor.transcribe(
                        file = file,
                        startOffsetMillis = segment.startOffsetMillis,
                        endOffsetMillis = segment.endOffsetMillis,
                    )
                    if (text.isNotBlank()) {
                        dao.insertTranscript(
                            TranscriptEntity(
                                id = UUID.randomUUID().toString(),
                                speechSegmentId = segment.id,
                                conversationId = null,
                                startedAtMillis = chunk.startedAtMillis + segment.startOffsetMillis,
                                endedAtMillis = chunk.startedAtMillis + segment.endOffsetMillis,
                                text = text,
                                languageTag = "zh",
                                modelName = "SenseVoice",
                                modelVersion = SenseVoiceAsrProcessor.MODEL_VERSION,
                                processingState = "ASR_READY",
                                errorMessage = null,
                            ),
                        )
                    }
                    dao.updateSpeechSegmentState(segment.id, "ASR_READY")
                }
            }
            dao.updateProcessingState(chunkId, "ASR_READY", null)
            app.conversationRepository.rebuildFromTranscripts()
            Result.success()
        } catch (error: OutOfMemoryError) {
            dao.updateProcessingState(chunkId, "ASR_FAILED", "ASR 内存不足")
            Result.failure()
        } catch (error: Throwable) {
            dao.updateProcessingState(
                chunkId,
                "ASR_FAILED",
                error.message ?: error.javaClass.simpleName,
            )
            Result.failure()
        }
    }

    companion object {
        const val AUDIO_CHUNK_ID = "audio_chunk_id"
        const val TAG = "sonfolio-asr"
    }
}
