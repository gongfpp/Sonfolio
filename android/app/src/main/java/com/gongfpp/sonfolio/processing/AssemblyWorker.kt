package com.gongfpp.sonfolio.processing

import android.content.Context
import androidx.work.*
import com.gongfpp.sonfolio.SonfolioApplication
import kotlinx.coroutines.CancellationException

/** Text assembly is retryable without loading ASR again or marking successful ASR as failed. */
class AssemblyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SonfolioApplication
        val dao = app.database.recordingDao()
        val id = inputData.getString("chunk") ?: return Result.failure()
        val chunk = dao.getChunk(id) ?: return Result.success()
        if (chunk.processingState !in listOf(ChunkProcessing.ASSEMBLY_PENDING, ChunkProcessing.ASSEMBLY_FAILED)) return Result.success()
        return try {
            app.conversationRepository.rebuildFromTranscripts(chunk.startedAtMillis, chunk.endedAtMillis ?: chunk.startedAtMillis)
            dao.updateProcessingState(id, ChunkProcessing.ASR_READY, null)
            try { app.summaryCoordinator.enqueueForNewChunk(id) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { dao.updateProcessingState(id, ChunkProcessing.ASR_READY, "基础整理已完成；AI 未能排队，请进入对话手动生成") }
            // 转写完成后异步压缩录音；失败只影响存储体积，不回头影响录音与文字。
            app.processingScheduler.enqueueCompression(id)
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            dao.updateProcessingState(id, if (runAttemptCount < 2) ChunkProcessing.ASSEMBLY_PENDING else ChunkProcessing.ASSEMBLY_FAILED,
                if (runAttemptCount < 2) "文字已保存，整理遇到问题，稍后自动重试" else "文字已保存，整理未完成；点击继续处理，不会重新识别录音")
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
    companion object {
        fun enqueue(context: Context, id: String) {
            WorkManager.getInstance(context).enqueueUniqueWork("assemble:$id", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AssemblyWorker>().setInputData(workDataOf("chunk" to id)).build())
        }
    }
}
