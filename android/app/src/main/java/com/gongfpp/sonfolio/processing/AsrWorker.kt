package com.gongfpp.sonfolio.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gongfpp.sonfolio.SonfolioApplication
import com.gongfpp.sonfolio.data.local.TranscriptEntity
import java.io.File
import java.util.UUID
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException

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
        if (chunk.processingState == "ASR_READY") return Result.success()
        val file = File(chunk.localPath)
        if (!file.exists() || file.length() <= 44L) {
            dao.updateProcessingState(chunkId, "ASR_FAILED", "录音文件不存在或为空")
            return Result.success()
        }

        dao.updateProcessingState(chunkId, "ASR_RUNNING", null)
        return try {
            val segments = dao.getSpeechSegments(chunkId)
            val language = app.preferences.preferredLanguage
            val texts = InferenceClient(applicationContext).transcribe(file, segments.map {
                DetectedSpeechWindow(it.startOffsetMillis, it.endOffsetMillis)
            }, language)
            app.database.withTransaction {
                dao.deleteTranscriptsForChunk(chunkId)
                segments.zip(texts).forEach { (segment, text) ->
                    if (text.isNotBlank()) {
                        dao.insertTranscript(
                            TranscriptEntity(
                                id = "transcript-${segment.id}",
                                speechSegmentId = segment.id,
                                conversationId = null,
                                startedAtMillis = chunk.startedAtMillis + segment.startOffsetMillis,
                                endedAtMillis = chunk.startedAtMillis + segment.endOffsetMillis,
                                text = text,
                                languageTag = language,
                                modelName = "SenseVoice",
                                modelVersion = SenseVoiceAsrProcessor.MODEL_VERSION,
                                processingState = "ASR_READY",
                                errorMessage = null,
                            ),
                        )
                    }
                    dao.updateSpeechSegmentState(segment.id, "ASR_READY")
                }
                dao.updateProcessingState(chunkId, "ASR_READY", null)
                app.conversationRepository.rebuildFromTranscripts()
            }
            // AI 总结排队失败不能把已经成功的 ASR 标记为失败。
            try { app.summaryCoordinator.enqueueForNewChunk(chunkId) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: OutOfMemoryError) {
            dao.updateProcessingState(chunkId, "ASR_FAILED", "ASR 内存不足")
            // 失败已落库，队列本身成功结束这一项，避免取消后续其他录音。
            Result.success()
        } catch (error: Throwable) {
            dao.updateProcessingState(
                chunkId,
                "ASR_FAILED",
                error.message ?: error.javaClass.simpleName,
            )
            Result.success()
        }
    }

    companion object {
        const val AUDIO_CHUNK_ID = "audio_chunk_id"
        const val TAG = "sonfolio-asr"
    }
}
