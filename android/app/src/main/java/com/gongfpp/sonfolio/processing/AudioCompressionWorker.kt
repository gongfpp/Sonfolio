package com.gongfpp.sonfolio.processing

import android.content.Context
import android.util.Log
import androidx.work.*
import com.gongfpp.sonfolio.SonfolioApplication
import java.io.File
import kotlinx.coroutines.CancellationException

/** 整理完成后异步生成 AAC 压缩音；失败可重试，不影响已经完成的转写与总结。 */
class AudioCompressionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SonfolioApplication
        val dao = app.database.recordingDao()
        val id = inputData.getString("chunk") ?: return Result.failure()
        val chunk = dao.getChunk(id) ?: return Result.success()
        if (chunk.processingState != "ASR_READY" || chunk.compressedPath != null) return Result.success()
        val source = File(chunk.localPath)
        if (!source.isFile) return Result.success()
        val target = File(source.parentFile, "$id.m4a")
        return try {
            val bytes = AudioTranscoder.compress(source, target, chunk.sampleRateHz, chunk.channelCount)
            // 状态可能已被用户清理；未登记成功就不把压缩文件当作有效产物。
            val registered = dao.setCompressedAudio(id, target.absolutePath, bytes)
            if (registered < 1) target.delete()
            Result.success()
        } catch (error: CancellationException) {
            target.delete()
            throw error
        } catch (error: Exception) {
            target.delete()
            if (runAttemptCount < 2) {
                dao.updateProcessingState(id, "ASR_READY", "文字与总结已保留；原音压缩遇到问题，稍后自动重试")
                Result.retry()
            } else {
                Log.e(TAG, "原音压缩失败，保留原始 WAV", error)
                dao.updateProcessingState(id, "ASR_READY", "文字与总结已保留；原音压缩未完成，仍可播放原声")
                Result.success()
            }
        }
    }

    companion object {
        const val TAG = "sonfolio-compress"
    }
}
