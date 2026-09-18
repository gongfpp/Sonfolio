package com.gongfpp.sonfolio.processing

import android.content.Context
import android.util.Log
import androidx.work.*
import com.gongfpp.sonfolio.SonfolioApplication
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/** 整理完成后异步生成 AAC 压缩音；失败可重试，不影响已经完成的转写与总结。 */
class AudioCompressionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = AudioFileAccess.mutex.withLock { process() }

    private suspend fun process(): Result {
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
            Log.e(TAG, "录音压缩失败，保留原始 WAV", error)
            // 只回写仍处于 ASR_READY 的行：清理并发把状态改成 AUDIO_DELETED 时不得覆盖回去，
            // 否则会产生 localPath 为空但状态又可备份的永久不一致行。
            val noted = dao.noteCompressionFailure(id, if (runAttemptCount < 2) {
                "文字与总结已保留；录音压缩遇到问题，稍后自动重试"
            } else {
                "文字与总结已保留；录音压缩未完成，仍可播放录音"
            })
            if (noted < 1) Log.i(TAG, "录音已在压缩期间被清理，放弃登记压缩结果")
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        const val TAG = "sonfolio-compress"
    }
}
