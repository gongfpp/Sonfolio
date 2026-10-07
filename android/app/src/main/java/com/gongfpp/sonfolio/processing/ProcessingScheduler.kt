package com.gongfpp.sonfolio.processing

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.Data
import java.util.UUID
import com.gongfpp.sonfolio.SonfolioPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.gongfpp.sonfolio.SonfolioApplication

class ProcessingScheduler(context: Context) {
    private val appContext = context.applicationContext
    suspend fun enqueueAssembly(chunkId: String, manual: Boolean = false) = enqueueStage(
        chunkId, manual, "assemble:$chunkId", OneTimeWorkRequestBuilder<AssemblyWorker>(), "sonfolio-assembly", "chunk", constrained = false,
    )

    /** 第 5 步纠错；manual=true 表示用户「继续处理」，不受仅充电约束。 */
    suspend fun enqueueCorrection(chunkId: String, manual: Boolean = false) = enqueueStage(
        chunkId, manual, "sonfolio-correct-$chunkId", OneTimeWorkRequestBuilder<TranscriptCorrectionWorker>(), TranscriptCorrectionWorker.TAG, "chunk",
    )

    /** 整理完成后的录音压缩；约束比 ASR 宽松，但避免低电与低存储时写入大文件。 */
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
        }.onFailure { Log.e("ProcessingScheduler", "无法加入压缩队列，录音保持未压缩状态", it) }
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
                    if ("processing-manual:true" in info.tags || (info.tags.none { it.startsWith("processing-manual:") } && info.constraints == Constraints.NONE)) return@forEach
                    val builder = if (isVad) OneTimeWorkRequestBuilder<VadWorker>() else OneTimeWorkRequestBuilder<AsrWorker>()
                    builder.setId(info.id).setConstraints(constraints())
                        .setInputData(workDataOf(VadWorker.AUDIO_CHUNK_ID to chunk.id))
                    info.tags.forEach { builder.addTag(it) }
                    manager.updateWork(builder.build()).get()
                }
            }
            dao.getChunksWithPendingProcessing().forEach { chunk ->
                manager.getWorkInfosForUniqueWork("sonfolio-correct-${chunk.id}").get()
                    .filter { !it.state.isFinished }.forEach { info ->
                        if ("processing-manual:true" in info.tags || (info.tags.none { it.startsWith("processing-manual:") } && info.constraints == Constraints.NONE)) return@forEach
                        val builder = OneTimeWorkRequestBuilder<TranscriptCorrectionWorker>()
                        builder.setId(info.id).setConstraints(constraints())
                            .setInputData(workDataOf("chunk" to chunk.id))
                        info.tags.forEach { builder.addTag(it) }
                        manager.updateWork(builder.build()).get()
                    }
            }
        }
    }

    /** manual=true 表示用户主动「继续处理」：不受充电/仅充电设置约束，立即排入。 */
    suspend fun enqueueVad(audioChunkId: String, manual: Boolean = false) = enqueueStage(
        audioChunkId, manual, "sonfolio-vad-$audioChunkId", OneTimeWorkRequestBuilder<VadWorker>(), VadWorker.TAG, VadWorker.AUDIO_CHUNK_ID,
    )

    /**
     * 每份录音独立排队，避免手动请求被其他录音的充电约束挡住。
     * 真正的串行执行由 AsrWorker 的锁保证，不靠任务依赖链。
     */
    suspend fun enqueueAsr(audioChunkId: String, manual: Boolean = false) = enqueueStage(
        audioChunkId, manual, "sonfolio-asr-$audioChunkId", OneTimeWorkRequestBuilder<AsrWorker>(), AsrWorker.TAG, AsrWorker.AUDIO_CHUNK_ID,
        legacyAsrTag = "sonfolio-asr-chunk-$audioChunkId",
    )

    private suspend fun enqueueStage(
        chunkId: String, manual: Boolean, name: String, builder: OneTimeWorkRequest.Builder,
        tag: String, inputKey: String, constrained: Boolean = true, legacyAsrTag: String? = null,
    ): Unit = withContext(Dispatchers.IO) {
        scheduleLock.withLock {
            val manager = WorkManager.getInstance(appContext)
            val active = (legacyAsrTag?.let { manager.getWorkInfosByTag(it).get() }
                ?: manager.getWorkInfosForUniqueWork(name).get()).filter { !it.state.isFinished }
            if (!manual && active.isNotEmpty()) return@withLock
            // 旧版本 ASR 链中 BLOCKED 的节点无法用 updateWork 脱离依赖；另排独立任务。
            // 旧节点不取消（取消会级联到其他录音），日后运行时根据已完成状态直接退出。
            val existing = active.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: active.firstOrNull { it.state != WorkInfo.State.BLOCKED }
            builder.setConstraints(if (manual || !constrained) Constraints.NONE else constraints())
                .setInputData(workDataOf(inputKey to chunkId, MANUAL to manual))
                .addTag(tag).addTag("processing-manual:$manual")
            legacyAsrTag?.let { builder.addTag(it) }
            if (existing != null) {
                builder.setId(existing.id)
                manager.updateWork(builder.build()).get()
            } else {
                manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, builder.build()).result.get()
            }
        }
    }

    /** updateWork 不改变正在运行的本轮 inputData，阶段交接时读取最新持久化标记。 */
    suspend fun isManual(workId: UUID, input: Data): Boolean = withContext(Dispatchers.IO) {
        input.getBoolean(MANUAL, false) ||
            WorkManager.getInstance(appContext).getWorkInfoById(workId).get()?.tags?.contains("processing-manual:true") == true
    }

    /** 该切片是否仍有未完成的 VAD/ASR 任务；用于区分正在执行的 RUNNING 状态与进程被杀后的孤儿状态。 */
    suspend fun hasUnfinishedProcessingWork(audioChunkId: String): Boolean = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(appContext)
        val vad = manager.getWorkInfosForUniqueWork("sonfolio-vad-$audioChunkId").get()
        val asr = manager.getWorkInfosByTag("sonfolio-asr-chunk-$audioChunkId").get()
        val correction = manager.getWorkInfosForUniqueWork("sonfolio-correct-$audioChunkId").get()
        (vad + asr + correction).any { !it.state.isFinished }
    }

    private fun constraints() = Constraints.Builder()
        .setRequiresCharging(SonfolioPreferences(appContext).chargeOnly)
        .build()

    companion object {
        const val MANUAL = "processing_manual"
        private val scheduleLock = Mutex()
    }
}
