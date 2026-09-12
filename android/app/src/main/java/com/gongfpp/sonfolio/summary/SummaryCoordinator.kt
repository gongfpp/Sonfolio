package com.gongfpp.sonfolio.summary

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.gongfpp.sonfolio.SonfolioApplication
import com.gongfpp.sonfolio.data.local.SummaryRunEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SummaryCoordinator(private val app: SonfolioApplication) {
    private val dao get() = app.database.conversationDao()
    private val work get() = WorkManager.getInstance(app)

    internal suspend fun input(key: String): SummaryInput = app.database.withTransaction {
        summaryInput(key, dao.getReadyTranscriptRows(), dao.getMarkers(), app.database.recordingDao().getGaps())
    }

    fun observe(key: String) = combine(dao.observeSummaryRun(key), dao.observeTimeline(), app.database.recordingDao().observeGaps()) { run, _, _ ->
        if (run != null && run.state !in listOf("QUEUED", "RUNNING") && run.sourceHash != input(key).fingerprint)
            run.copy(state = "STALE", message = "内容有更新，当前显示基础整理，可重新生成 AI 总结") else run
    }

    suspend fun enqueue(key: String, automatic: Boolean = false): Unit = enqueueMutex.withLock {
        val config = app.summarySettings.read()
        if (config.mode == SummaryMode.BASIC || (automatic && !config.automatic)) return
        val source = input(key)
        if (source.rows.isEmpty()) return
        val old = dao.getSummaryRun(key)
        if (!automatic && old?.state in listOf("QUEUED", "RUNNING")) return
        if (automatic && old?.sourceHash == source.fingerprint && old.outputJson != null && old.model == identity(config)) return
        val request = OneTimeWorkRequestBuilder<SummaryWorker>()
            .setInputData(workDataOf("key" to key, "revision" to config.revision, "force" to !automatic))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true)
                .setRequiresCharging(automatic && app.preferences.chargeOnly).build())
            .addTag(TAG).addTag("summary-key:$key").build()
        // 单队列隔离于 VAD/ASR；每个任务读取执行时的最新转写，不并行加载多个语言模型。
        dao.saveSummaryRun(old?.copy(state = "QUEUED", message = "等待总结；电量过低时暂停", updatedAtMillis = System.currentTimeMillis())
            ?: SummaryRunEntity(key, source.fingerprint, config.mode.name, identity(config), null, "QUEUED", "等待总结；电量过低时暂停", System.currentTimeMillis()))
        work.enqueueUniqueWork("sonfolio-summary:$key", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        Unit
    }

    suspend fun enqueueForNewChunk(chunkId: String) {
        if (!app.summarySettings.read().automatic) return
        val rows = dao.getReadyTranscriptRows()
        val transcriptIds = app.database.recordingDao().getTranscriptsForSummaryChunk(chunkId).toSet()
        val ids = rows.filter { row ->
            // 从已落库切片关联出本次对话；不回填发送全部历史记录。
            row.transcriptId in transcriptIds
        }.mapNotNull { it.conversationId }.distinct()
        for (id in ids) enqueue("conversation:$id", automatic = true)
        val zone = ZoneId.systemDefault()
        val dates = rows.filter { it.conversationId in ids }.flatMap { row ->
            val start = Instant.ofEpochMilli(row.startedAtMillis).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli((row.endedAtMillis - 1).coerceAtLeast(row.startedAtMillis)).atZone(zone).toLocalDate()
            generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        }.distinct()
        for (date in dates) enqueue("day:$date", automatic = true)
    }

    suspend fun cancelAll() {
        work.cancelAllWorkByTag(TAG).result.get()
        dao.getSummaryRuns().filter { it.state in listOf("QUEUED", "RUNNING") }.forEach {
            dao.updateSummaryRun(it.sourceKey, "CANCELLED", "配置改变或用户取消，已有小结保留", System.currentTimeMillis())
        }
    }

    suspend fun cancel(key: String) {
        work.cancelAllWorkByTag("summary-key:$key").result.get()
        dao.updateSummaryRun(key, "CANCELLED", "已取消，已有小结保留", System.currentTimeMillis())
    }

    suspend fun test(config: SummaryConfig): String {
        check(config.mode != SummaryMode.BASIC) { "基础整理不需要测试连接" }
        val input = SummaryInput("conversation:connection-test", listOf(SummaryText("test", 0, 1, "这是一段连接测试文字，不包含用户录音。我们决定明天上午检查录音按钮。", false)))
        val text = generate(config, SummaryPrompt.SYSTEM, SummaryPrompt.user(input, input.parts(500).single(), null, 0, 1))
        AiSummary.parse(text)
        return "测试通过：已返回结构化小结；未发送真实录音或转写"
    }

    internal suspend fun generate(config: SummaryConfig, system: String, user: String): String {
        check(app.summarySettings.read().revision == config.revision) { "总结配置已改变，任务已取消" }
        return when (config.mode) {
            SummaryMode.REMOTE -> RemoteSummaryTransport(app.summarySettings).generate(config, system, user)
            SummaryMode.LOCAL -> localMutex.withLock {
                val file = app.summarySettings.modelFile(config)
                require(file?.isFile == true) { "请先导入本地模型" }
                LocalSummaryTransport(app).generate(file, system, user)
            }
            SummaryMode.BASIC -> error("请先在设置中选择 AI 总结方式")
        }
    }

    companion object {
        const val TAG = "sonfolio-summary"
        private val enqueueMutex = Mutex()
        internal val localMutex = Mutex()
        internal fun identity(config: SummaryConfig) = if (config.mode == SummaryMode.LOCAL) "${config.localLabel} [${config.localFile}]" else "${config.model} @ ${config.endpoint}"
    }
}

class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = workerMutex.withLock { execute() }
    private suspend fun execute(): Result {
        val app = applicationContext as SonfolioApplication
        val coordinator = app.summaryCoordinator
        val dao = app.database.conversationDao()
        val key = inputData.getString("key") ?: return Result.failure()
        val config = app.summarySettings.read()
        if (config.mode == SummaryMode.BASIC || config.revision != inputData.getString("revision")) return Result.success()
        return try {
            val input = coordinator.input(key)
            if (input.rows.isEmpty()) {
                dao.updateSummaryRun(key, "FAILED", "暂无可总结的转写", System.currentTimeMillis()); return Result.success()
            }
            val cached = dao.getSummaryRun(key)
            if (!inputData.getBoolean("force", false) && cached?.sourceHash == input.fingerprint && cached.outputJson != null && cached.model == SummaryCoordinator.identity(config)) {
                dao.updateSummaryRun(key, "READY", null, System.currentTimeMillis()); return Result.success()
            }
            var summary: AiSummary? = null
            val parts = input.parts(if (config.mode == SummaryMode.LOCAL) 500 else 4000)
            withTimeout(9 * 60_000L) {
                for ((index, part) in parts.withIndex()) {
                    ensureActive()
                    dao.updateSummaryRun(key, "RUNNING", "正在整理 ${index + 1}/${parts.size} 部分", System.currentTimeMillis())
                    summary = AiSummary.parse(coordinator.generate(config, SummaryPrompt.SYSTEM, SummaryPrompt.user(input, part, summary, index, parts.size)))
                }
            }
            app.database.withTransaction {
                check(config.revision == app.summarySettings.read().revision) { "总结配置已改变，旧结果未应用" }
                if (coordinator.input(key).fingerprint != input.fingerprint) {
                    dao.updateSummaryRun(key, "STALE", "录音内容已更新，请重新生成", System.currentTimeMillis())
                } else {
                    dao.saveSummaryRun(SummaryRunEntity(key, input.fingerprint, config.mode.name, SummaryCoordinator.identity(config),
                        requireNotNull(summary).json(), "READY", null, System.currentTimeMillis()))
                    app.conversationRepository.rebuildFromTranscripts()
                }
            }
            Result.success()
        } catch (_: TimeoutCancellationException) {
            withContext(NonCancellable) { dao.updateSummaryRun(key, "FAILED", "总结耗时过长，已有小结保留，可手动重试", System.currentTimeMillis()) }
            Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable) { dao.updateSummaryRun(key, "CANCELLED", "已取消，已有小结保留", System.currentTimeMillis()) }
            throw error
        } catch (error: Throwable) {
            dao.updateSummaryRun(key, "FAILED", if (error is OutOfMemoryError) "总结内存不足，已有小结保留" else error.message?.take(180) ?: "总结失败，已有小结保留", System.currentTimeMillis())
            // 业务失败不取消后续其他对话，也不无限自动重试产生费用。
            Result.success()
        }
    }
    companion object { private val workerMutex = Mutex() }
}
