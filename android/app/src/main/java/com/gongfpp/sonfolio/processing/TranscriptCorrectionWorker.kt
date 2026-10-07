package com.gongfpp.sonfolio.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gongfpp.sonfolio.SonfolioApplication
import com.gongfpp.sonfolio.summary.SummaryMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 第 5 步：把本切片的结构化转写交给已配置的总结模型，纠正错别字与标点。
 * 本地模型默认自动执行；在线模型需要用户在设置里单独开启。未配置模型时直接跳过。
 * 纠错失败不阻塞后续整理：仍标记完成并排队 AI 总结，只是状态标红可手动重试。
 */
class TranscriptCorrectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SonfolioApplication
        val dao = app.database.recordingDao()
        val chunkId = inputData.getString("chunk") ?: return Result.failure()
        val chunk = dao.getChunk(chunkId) ?: return Result.success()
        if (chunk.processingState !in listOf(ChunkProcessing.CORRECTION_PENDING, ChunkProcessing.CORRECTION_FAILED, ChunkProcessing.CORRECTION_RUNNING)) return Result.success()
        val config = app.summarySettings.read()
        if (!correctionEnabled(app, config.mode)) { complete(app, chunkId); return Result.success() }
        dao.updateProcessingState(chunkId, ChunkProcessing.CORRECTION_RUNNING, null)
        return try {
            val rows = dao.getTranscriptsForChunk(chunkId).filter { it.text.isNotBlank() }.map { it.id to it.text }
            if (rows.isNotEmpty()) {
                val result = app.summaryCoordinator.correctRows(config, rows)
                app.conversationRepository.applyTranscriptCorrections(result.corrections)
            }
            complete(app, chunkId)
            Result.success()
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            complete(app, chunkId)
            dao.updateProcessingState(chunkId, ChunkProcessing.CORRECTION_FAILED, "本次纠错超时，文字已保留；可点继续处理重试")
            Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                if (dao.getChunk(chunkId)?.processingState == ChunkProcessing.CORRECTION_RUNNING) {
                    dao.updateProcessingState(chunkId, ChunkProcessing.CORRECTION_PENDING, "纠错已中断，文字已保留，等待继续处理")
                }
            }
            throw error
        } catch (error: Throwable) {
            if (runAttemptCount < 1) {
                dao.updateProcessingState(chunkId, ChunkProcessing.CORRECTION_PENDING, "纠错遇到问题，稍后自动重试")
                Result.retry()
            } else {
                // 仍完成并排队总结，避免纠错失败拖住整条时间线。
                complete(app, chunkId)
                dao.updateProcessingState(chunkId, ChunkProcessing.CORRECTION_FAILED, "纠错未完成，文字已保留；可点继续处理重试")
                Result.success()
            }
        }
    }

    private suspend fun complete(app: SonfolioApplication, chunkId: String) {
        val dao = app.database.recordingDao()
        dao.updateProcessingState(chunkId, ChunkProcessing.ASR_READY, null)
        try { app.summaryCoordinator.enqueueForNewChunk(chunkId) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { dao.updateProcessingState(chunkId, ChunkProcessing.ASR_READY, "基础整理已完成；AI 未能排队，请进入对话手动生成") }
        app.processingScheduler.enqueueCompression(chunkId)
    }

    companion object {
        const val TAG = "sonfolio-correction-pipeline"

        fun correctionEnabled(app: SonfolioApplication, mode: SummaryMode): Boolean = when (mode) {
            SummaryMode.BASIC -> false
            // 模型或密钥尚未就绪时跳过，避免每个切片都因缺模型而标红。
            SummaryMode.LOCAL -> app.summarySettings.modelFile()?.isFile == true
            SummaryMode.REMOTE -> app.preferences.correctionOnlineEnabled && app.summarySettings.read().hasKey
        }
    }
}
