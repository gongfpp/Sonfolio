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
import kotlinx.coroutines.sync.withLock

class AsrWorker(
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
        if (chunk.endedAtMillis == null || chunk.processingState == "AUDIO_DELETED") return Result.success()
        if (chunk.processingState == "ASR_READY") return Result.success()
        if (chunk.processingState in listOf("ASSEMBLY_PENDING", "ASSEMBLY_FAILED")) {
            AssemblyWorker.enqueue(applicationContext, chunkId); return Result.success()
        }
        val segments = dao.getSpeechSegments(chunkId)
        val config = app.transcriptionSettings.read()
        val model = com.gongfpp.sonfolio.models.ModelCatalog.speech
        if (segments.isNotEmpty() && config.mode == TranscriptionMode.REMOTE && !app.transcriptionSettings.isAuthorized(config, chunk.id, chunk.startedAtMillis)) {
            dao.updateProcessingState(chunkId, "VAD_READY", "尚未授权上传历史音频：在原始录音中点继续处理并确认，或在设置 → 转文字方式选择本地识别")
            return Result.success()
        }
        if (segments.isNotEmpty() && config.mode == TranscriptionMode.LOCAL && com.gongfpp.sonfolio.models.ModelCatalog.file(applicationContext.filesDir, model).length() != model.bytes) {
            dao.updateProcessingState(chunkId, "VAD_READY", "等待语音识别模型：请打开设置 → 转文字方式，下载模型后继续转写；也可选择在线识别")
            return Result.success()
        }
        val file = File(chunk.localPath)
        if (!file.exists() || file.length() <= 44L) {
            dao.updateProcessingState(chunkId, "ASR_FAILED", "录音文件不存在或为空")
            return Result.success()
        }

        dao.updateProcessingState(chunkId, "ASR_RUNNING", null)
        return try {
            val language = app.preferences.preferredLanguage
            val windows = segments.map {
                DetectedSpeechWindow(it.startOffsetMillis, it.endOffsetMillis)
            }
            val texts = when {
                segments.isEmpty() -> emptyList()
                config.mode == TranscriptionMode.REMOTE -> RemoteSpeechTransport(app.transcriptionSettings)
                    .transcribe(file, windows, config, chunk.id, chunk.startedAtMillis, language)
                else -> InferenceClient(applicationContext).transcribe(file, windows, language)
            }
            check(segments.size == texts.size) { "转写片段数量不完整，原音保留，请重试" }
            check(app.transcriptionSettings.read().revision == config.revision) { "转文字设置已改变，请继续处理以使用新设置" }
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
                                languageTag = if (config.mode == TranscriptionMode.REMOTE && config.provider == SpeechProvider.SILICONFLOW) "auto" else language,
                                modelName = if (config.mode == TranscriptionMode.REMOTE) config.model else "SenseVoice",
                                modelVersion = if (config.mode == TranscriptionMode.REMOTE) "remote:${config.provider.name}:vad-window-v1" else SenseVoiceAsrProcessor.MODEL_VERSION,
                                processingState = "ASR_READY",
                                errorMessage = null,
                            ),
                        )
                    }
                    dao.updateSpeechSegmentState(segment.id, "ASR_READY")
                }
                dao.updateProcessingState(chunkId, "ASSEMBLY_PENDING", "文字已保存，等待第 4 步整理对话")
            }
            // Scheduling errors leave persisted text pending; opening the app can re-enqueue it.
            runCatching { AssemblyWorker.enqueue(applicationContext, chunkId) }
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
