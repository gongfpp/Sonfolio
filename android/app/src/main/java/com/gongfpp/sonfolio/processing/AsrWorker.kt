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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        if (chunk.endedAtMillis == null || chunk.processingState == ChunkProcessing.AUDIO_DELETED) return Result.success()
        if (chunk.processingState == ChunkProcessing.ASR_READY) return Result.success()
        if (chunk.processingState in listOf(ChunkProcessing.ASSEMBLY_PENDING, ChunkProcessing.ASSEMBLY_FAILED)) {
            AssemblyWorker.enqueue(applicationContext, chunkId); return Result.success()
        }
        val segments = dao.getSpeechSegments(chunkId)
        val config = app.transcriptionSettings.read()
        val engine = config.localEngine
        val model = requireNotNull(com.gongfpp.sonfolio.models.ModelCatalog.byId(engine.artifactId)) { "未知的本地识别引擎" }
        if (segments.isNotEmpty() && config.mode == TranscriptionMode.REMOTE && !app.transcriptionSettings.isAuthorized(config, chunk.id, chunk.startedAtMillis)) {
            dao.updateProcessingState(chunkId, ChunkProcessing.VAD_READY, "尚未授权上传历史音频：在原始录音中点继续处理并确认，或在设置 → 转文字方式选择本地识别")
            return Result.success()
        }
        if (segments.isNotEmpty() && config.mode == TranscriptionMode.LOCAL && !com.gongfpp.sonfolio.models.ModelCatalog.installed(applicationContext.filesDir, model)) {
            dao.updateProcessingState(chunkId, ChunkProcessing.VAD_READY, "等待语音识别模型：请打开设置 → 转文字方式，下载模型后继续转写；也可选择在线识别")
            return Result.success()
        }
        val file = File(chunk.localPath)
        if (!file.exists() || file.length() <= 44L) {
            dao.updateProcessingState(chunkId, ChunkProcessing.ASR_FAILED, "录音文件不存在或为空")
            return Result.success()
        }

        dao.updateProcessingState(chunkId, ChunkProcessing.ASR_RUNNING, null)
        return try {
            val language = app.preferences.preferredLanguage
            val windows = segments.map {
                DetectedSpeechWindow(it.startOffsetMillis, it.endOffsetMillis)
            }
            val texts = when {
                segments.isEmpty() -> emptyList()
                config.mode == TranscriptionMode.REMOTE -> remoteTranscribe(
                    app, dao, file, segments, config, chunk.id, chunk.startedAtMillis, language,
                )
                else -> {
                    // 只有支持热词的引擎才读取个人词汇，避免每次本地转写都查库。
                    val hotwords = if (engine.supportsHotwords) app.vocabularyRepository.hotwords() else ""
                    val localTexts = InferenceClient(applicationContext).transcribe(file, windows, language, engine, hotwords)
                    // 不能静默丢文字：整段为空时明确报错并保留录音，让用户改用 SenseVoice 重试。
                    if (engine == LocalAsrEngine.QWEN3_ASR && windows.isNotEmpty() && localTexts.all { it.isBlank() }) {
                        error("Qwen3-ASR 本次没有返回文字（可能是纯音乐/噪声或模型异常）；录音已保留，可在设置改用 SenseVoice 后重试")
                    }
                    localTexts
                }
            }
            check(segments.size == texts.size) { "转写片段数量不完整，录音保留，请重试" }
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
                                modelName = if (config.mode == TranscriptionMode.REMOTE) config.model else engine.displayName,
                                modelVersion = if (config.mode == TranscriptionMode.REMOTE) "remote:${config.provider.name}:vad-window-v1" else when (engine) {
                                    LocalAsrEngine.SENSE_VOICE -> SenseVoiceAsrProcessor.MODEL_VERSION
                                    LocalAsrEngine.QWEN3_ASR -> Qwen3AsrProcessor.MODEL_VERSION
                                    LocalAsrEngine.FIRE_RED_ASR_CTC -> FireRedAsrCtcProcessor.MODEL_VERSION
                                },
                                processingState = ChunkProcessing.ASR_READY,
                                errorMessage = null,
                            ),
                        )
                    }
                    dao.updateSpeechSegmentState(segment.id, ChunkProcessing.ASR_READY)
                }
                dao.updateProcessingState(chunkId, ChunkProcessing.ASSEMBLY_PENDING, "文字已保存，等待第 4 步整理对话")
            }
            // Scheduling errors leave persisted text pending; opening the app can re-enqueue it.
            runCatching { AssemblyWorker.enqueue(applicationContext, chunkId) }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: OutOfMemoryError) {
            dao.updateProcessingState(chunkId, ChunkProcessing.ASR_FAILED, "ASR 内存不足")
            // 失败已落库，队列本身成功结束这一项，避免取消后续其他录音。
            Result.success()
        } catch (error: Throwable) {
            dao.updateProcessingState(
                chunkId,
                ChunkProcessing.ASR_FAILED,
                error.message ?: error.javaClass.simpleName,
            )
            Result.success()
        }
    }

    /**
     * 远程转写：并发上传各人声窗口，避免逐窗口串行等待。已由相同模型+提供商转写过的窗口直接
     * 复用现有文字；部分窗口失败时，把已成功的文字落库并把状态置为 ASR_FAILED，重试只上传
     * 缺失片段，不会对已计费的窗口重复请求。
     */
    private suspend fun remoteTranscribe(
        app: SonfolioApplication,
        dao: com.gongfpp.sonfolio.data.local.RecordingDao,
        file: File,
        segments: List<com.gongfpp.sonfolio.data.local.SpeechSegmentEntity>,
        config: TranscriptionConfig,
        chunkId: String,
        startedAt: Long,
        language: String,
    ): List<String> {
        val transport = RemoteSpeechTransport(app.transcriptionSettings)
        val versionTag = "remote:${config.provider.name}:vad-window-v1"
        val reusable = dao.getTranscriptsForChunk(chunkId)
            .filter { it.modelName == config.model && it.modelVersion == versionTag }
            .associateBy { it.speechSegmentId }
        val pending = segments.filter { it.id !in reusable }
        val results = coroutineScope {
            val permits = Semaphore(REMOTE_CONCURRENCY)
            pending.map { segment ->
                async {
                    permits.withPermit {
                        segment to runCatching {
                            transport.transcribeWindow(
                                file,
                                DetectedSpeechWindow(segment.startOffsetMillis, segment.endOffsetMillis),
                                config, chunkId, startedAt, language,
                            )
                        }
                    }
                }
            }.awaitAll()
        }
        // 用户停止或系统取消时不允许把取消当成转写失败落库。
        currentCoroutineContext().ensureActive()
        val failures = results.filter { it.second.isFailure }
        if (failures.isNotEmpty()) {
            val succeeded = results.mapNotNull { (segment, result) -> result.getOrNull()?.let { segment to it } }
            app.database.withTransaction {
                dao.deleteTranscriptsForChunk(chunkId)
                reusable.values.forEach { dao.insertTranscript(it) }
                succeeded.forEach { (segment, text) ->
                    if (text.isNotBlank()) {
                        dao.insertTranscript(
                            TranscriptEntity(
                                id = "transcript-${segment.id}",
                                speechSegmentId = segment.id,
                                conversationId = null,
                                startedAtMillis = startedAt + segment.startOffsetMillis,
                                endedAtMillis = startedAt + segment.endOffsetMillis,
                                text = text,
                                languageTag = if (config.provider == SpeechProvider.SILICONFLOW) "auto" else language,
                                modelName = config.model,
                                modelVersion = versionTag,
                                processingState = ChunkProcessing.ASR_READY,
                                errorMessage = null,
                            ),
                        )
                    }
                    dao.updateSpeechSegmentState(segment.id, ChunkProcessing.ASR_READY)
                }
            }
            error("${failures.size}/${pending.size} 个片段转写失败：${failures.first().second.exceptionOrNull()?.message}；已完成的文字已保留，重试只处理剩余片段")
        }
        val textBySegment = reusable.mapValues { it.value.text }.toMutableMap()
        results.forEach { (segment, result) -> textBySegment[segment.id] = result.getOrThrow() }
        return segments.map { textBySegment.getValue(it.id) }
    }

    companion object {
        const val AUDIO_CHUNK_ID = "audio_chunk_id"
        const val TAG = "sonfolio-asr"
        /** 远程窗口并发上传数；过高容易触发提供商限流（429），过低失去并行收益。 */
        private const val REMOTE_CONCURRENCY = 3
    }
}
