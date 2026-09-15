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
import kotlinx.coroutines.withContext
import com.gongfpp.sonfolio.SonfolioApplication

class ProcessingScheduler(context: Context) {
    private val appContext = context.applicationContext
    fun enqueueAssembly(chunkId: String) = AssemblyWorker.enqueue(appContext, chunkId)

    /** 整理完成后的原音压缩；约束比 ASR 宽松，但避免低电与低存储时写入大文件。 */
    fun enqueueCompression(chunkId: String) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<AudioCompressionWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .setInputData(workDataOf("chunk" to chunkId))
                .addTag(AudioCompressionWorker.TAG)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                "sonfolio-compress-$chunkId", ExistingWorkPolicy.KEEP, request,
            )
        }.onFailure { Log.e("ProcessingScheduler", "无法加入压缩队列，原音保持未压缩状态", it) }
    }

    /** Update persisted constraints, including requests created by older versions. Running
     * iterations finish with their current constraints; queued requests use the new policy. */
    suspend fun refreshConstraints() = withContext(Dispatchers.IO) {
        scheduleLock.withLock {
            val manager = WorkManager.getInstance(appContext)
            val dao = (appContext as SonfolioApplication).database.recordingDao()
            dao.getChunksWithPendingProcessing().forEach { chunk ->
                val vad = manager.getWorkInfosForUniqueWork("sonfolio-vad-${chunk.id}").get()
                val asr = manager.getWorkInfosByTag("sonfolio-asr-chunk-${chunk.id}").get()
                (vad.map { it to true } + asr.map { it to false }).filter { !it.first.state.isFinished }.forEach { (info, isVad) ->
                    val builder = if (isVad) OneTimeWorkRequestBuilder<VadWorker>() else OneTimeWorkRequestBuilder<AsrWorker>()
                    builder.setId(info.id).setConstraints(constraints())
                        .setInputData(workDataOf(VadWorker.AUDIO_CHUNK_ID to chunk.id))
                    info.tags.forEach { builder.addTag(it) }
                    manager.updateWork(builder.build()).get()
                }
            }
        }
    }

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

    /** 该切片是否仍有未完成的 VAD/ASR 任务；用于区分正在执行的 RUNNING 状态与进程被杀后的孤儿状态。 */
    suspend fun hasUnfinishedProcessingWork(audioChunkId: String): Boolean = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(appContext)
        val vad = manager.getWorkInfosForUniqueWork("sonfolio-vad-$audioChunkId").get()
        val asr = manager.getWorkInfosByTag("sonfolio-asr-chunk-$audioChunkId").get()
        (vad + asr).any { !it.state.isFinished }
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
