package com.gongfpp.sonfolio

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.gongfpp.sonfolio.processing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 使用内存 WorkManager 和唯一偏好沙箱；不写个人库，不运行模型或生成录音。 */
class ProcessingSchedulerIntegrationTest {
    @Test fun manualWorkEscapesLegacyChargingChainAndCarriesIntentThroughAssembly() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "qa-scheduler-${System.nanoTime()}-"
        val prefNames = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                prefNames += prefix + name
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        val seen = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                object : Worker(appContext, workerParameters) {
                    override fun doWork(): Result = runBlocking {
                        val scheduler = ProcessingScheduler(context)
                        val manual = scheduler.isManual(id, inputData)
                        seen += workerClassName to manual
                        val chunk = inputData.getString("audio_chunk_id") ?: inputData.getString("chunk")!!
                        when (workerClassName) {
                            VadWorker::class.java.name -> scheduler.enqueueAsr(chunk, manual)
                            AsrWorker::class.java.name -> scheduler.enqueueAssembly(chunk, manual)
                            AssemblyWorker::class.java.name -> scheduler.enqueueCorrection(chunk, manual)
                        }
                        Result.success()
                    }
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(factory).build())
        val manager = WorkManager.getInstance(context)
        val scheduler = ProcessingScheduler(context)
        try {
            SonfolioPreferences(context).setChargeOnly(true)
            val oldHead = OneTimeWorkRequestBuilder<AsrWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .setInputData(workDataOf("audio_chunk_id" to "qa-other")).build()
            val oldTarget = OneTimeWorkRequestBuilder<AsrWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .setInputData(workDataOf("audio_chunk_id" to "qa-target"))
                .addTag("sonfolio-asr-chunk-qa-target").build()
            manager.beginUniqueWork("qa-legacy-${UUID.randomUUID()}", ExistingWorkPolicy.KEEP, oldHead)
                .then(oldTarget).enqueue().result.get()
            assertEquals(WorkInfo.State.BLOCKED, manager.getWorkInfoById(oldTarget.id).get()!!.state)
            scheduler.enqueueAsr("qa-target", manual = true)
            awaitFinished(manager, "sonfolio-correct-qa-target")
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(oldHead.id).get()!!.state)
            assertEquals(WorkInfo.State.BLOCKED, manager.getWorkInfoById(oldTarget.id).get()!!.state)
            assertEquals(listOf(AsrWorker::class.java.name, AssemblyWorker::class.java.name, TranscriptCorrectionWorker::class.java.name), seen.map { it.first })
            assertTrue(seen.all { it.second })

            // 原有 VAD 等待任务原位提升，不取消重建，也不会被下一次自动投递降级。
            seen.clear()
            scheduler.enqueueVad("qa-vad")
            val before = manager.getWorkInfosForUniqueWork("sonfolio-vad-qa-vad").get().single()
            assertTrue(before.constraints.requiresCharging())
            scheduler.enqueueVad("qa-vad", manual = true)
            scheduler.enqueueVad("qa-vad")
            awaitFinished(manager, "sonfolio-correct-qa-vad")
            val after = manager.getWorkInfosForUniqueWork("sonfolio-vad-qa-vad").get().first { it.id == before.id }
            assertFalse(after.constraints.requiresCharging())
            assertTrue(seen.all { it.second })
        } finally {
            manager.cancelAllWork().result.get()
            WorkManagerTestInitHelper.closeWorkDatabase()
            prefNames.forEach { base.deleteSharedPreferences(it) }
        }
    }

    private suspend fun awaitFinished(manager: WorkManager, name: String) {
        kotlinx.coroutines.withTimeout(15_000) {
            while (manager.getWorkInfosForUniqueWork(name).get().none { it.state == WorkInfo.State.SUCCEEDED }) {
                kotlinx.coroutines.delay(20)
            }
        }
    }
}
