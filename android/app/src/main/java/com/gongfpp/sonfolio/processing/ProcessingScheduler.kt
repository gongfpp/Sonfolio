package com.gongfpp.sonfolio.processing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class ProcessingScheduler(context: Context) {
    private val appContext = context.applicationContext

    fun enqueueVad(audioChunkId: String) {
        val request = OneTimeWorkRequestBuilder<VadWorker>()
            .setInputData(workDataOf(VadWorker.AUDIO_CHUNK_ID to audioChunkId))
            .addTag(VadWorker.TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "sonfolio-vad-$audioChunkId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * ASR is kept in one unique chain because the int8 SenseVoice model is large. Serializing
     * chunks prevents two native recognizers from competing for the phone's memory.
     */
    fun enqueueAsr(audioChunkId: String) {
        val request = OneTimeWorkRequestBuilder<AsrWorker>()
            .setInputData(workDataOf(AsrWorker.AUDIO_CHUNK_ID to audioChunkId))
            .addTag(AsrWorker.TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            ASR_QUEUE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    companion object {
        private const val ASR_QUEUE_NAME = "sonfolio-asr-queue"
    }
}
