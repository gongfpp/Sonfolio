package com.gongfpp.sonfolio.processing

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.Constraints
import com.gongfpp.sonfolio.SonfolioPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProcessingScheduler(context: Context) {
    private val appContext = context.applicationContext

    fun enqueueVad(audioChunkId: String) {
        runCatching {
        val request = OneTimeWorkRequestBuilder<VadWorker>()
            .setConstraints(constraints())
            .setInputData(workDataOf(VadWorker.AUDIO_CHUNK_ID to audioChunkId))
            .addTag(VadWorker.TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "sonfolio-vad-$audioChunkId",
            ExistingWorkPolicy.KEEP,
            request,
        )
        }.onFailure { Log.e("ProcessingScheduler", "无法加入处理队列，原音保持待处理状态", it) }
    }

    /**
     * ASR is kept in one unique chain because the int8 SenseVoice model is large. Serializing
     * chunks prevents two native recognizers from competing for the phone's memory.
     */
    fun enqueueAsr(audioChunkId: String) {
        scope.launch {
            runCatching {
                scheduleLock.withLock {
                    val manager = WorkManager.getInstance(appContext)
                    val chunkTag = "sonfolio-asr-chunk-$audioChunkId"
                    if (manager.getWorkInfosByTag(chunkTag).get().any { !it.state.isFinished }) return@withLock
                    val request = OneTimeWorkRequestBuilder<AsrWorker>()
                        .setConstraints(constraints())
                        .setInputData(workDataOf(AsrWorker.AUDIO_CHUNK_ID to audioChunkId))
                        .addTag(AsrWorker.TAG)
                        .addTag(chunkTag)
                        .build()
                    manager.enqueueUniqueWork(ASR_QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
                }
            }.onFailure { Log.e("ProcessingScheduler", "无法加入转写队列，原音保持待处理状态", it) }
        }
    }

    private fun constraints() = Constraints.Builder()
        .setRequiresCharging(SonfolioPreferences(appContext).chargeOnly)
        .build()

    companion object {
        private const val ASR_QUEUE_NAME = "sonfolio-asr-queue"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val scheduleLock = Mutex()
    }
}
