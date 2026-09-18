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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SummaryCoordinator(private val app: SonfolioApplication) {
    private val dao get() = app.database.conversationDao()
    private val work get() = WorkManager.getInstance(app)

    internal suspend fun input(key: String): SummaryInput {
        val snapshot = app.database.withTransaction {
            // 日期窗口按整理该日时使用的录音时区展开，与 ConversationRepository 保持一致。
            var dayZone: java.time.ZoneId? = null
            val rows = if (key.startsWith("day:")) {
                val date = java.time.LocalDate.parse(key.removePrefix("day:"))
                val zone = dao.getDailyJournal(date.toString())?.zoneId
                    ?.let { zoneId -> runCatching { java.time.ZoneId.of(zoneId) }.getOrNull() }
                    ?: java.time.ZoneId.systemDefault()
                dayZone = zone
                val day = com.gongfpp.sonfolio.DayWindow.of(date, zone)
                val ids = dao.getReadyRowsInWindow(day.start, day.end).mapNotNull { it.conversationId }.distinct()
                ids.chunked(400).flatMap { dao.getReadyRowsForConversations(it) }.sortedWith(compareBy({ it.startedAtMillis }, { it.transcriptId }))
            } else {
                dao.getReadyRowsForConversations(listOf(key.removePrefix("conversation:")))
            }
            val day = dayZone?.let { zone ->
                com.gongfpp.sonfolio.DayWindow.of(java.time.LocalDate.parse(key.removePrefix("day:")), zone)
            }
            val start = minOf(rows.minOfOrNull { it.startedAtMillis } ?: day?.start ?: 0L, day?.start ?: Long.MAX_VALUE) - 120_000
            val end = maxOf(rows.maxOfOrNull { it.endedAtMillis } ?: day?.end ?: 0L, day?.end ?: Long.MIN_VALUE) + 120_000
            Triple(rows, app.database.recordingDao().getMarkersInWindow(start, end), app.database.recordingDao().getGapsInWindow(start, end)) to dayZone
        }
        return summaryInput(key, snapshot.first.first, snapshot.first.second, snapshot.first.third, snapshot.second ?: java.time.ZoneId.systemDefault())
    }

    /**
     * 观察某条总结的运行状态。时间线/缺口只作为「内容可能变了」的触发器，但每次触发都要重算
     * 该 sourceKey 的输入指纹（含全部转写文本哈希），所以：
     * - conflate 丢弃来不及处理的中间触发，只保留最新一次；
     * - mapLatest 让被取代的计算直接取消；
     * - flowOn(Default) 把指纹计算移出主线程，避免持续录音时卡 UI。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(key: String) = combine(dao.observeSummaryRun(key), dao.observeTimeline(), app.database.recordingDao().observeGaps()) { run, _, _ -> run }
        .conflate()
        .mapLatest { run ->
            if (run != null && run.state !in listOf("QUEUED", "RUNNING") && run.sourceHash != input(key).fingerprint)
                run.copy(state = "STALE", message = "内容有更新，当前显示基础整理，可重新生成 AI 总结") else run
        }
        .flowOn(Dispatchers.Default)

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
            .addTag(TAG).addTag("summary-key:$key").addTag("summary-revision:${config.revision}").addTag("summary-force:${!automatic}")
        if (automatic) {
            // 持续录音时同一对话/日期会随每个新切片被反复触发；延迟并入队替换（REPLACE），
            // 只保留静默 10 分钟后的最新一次，避免长内容被逐切片重复计费生成。
            request.setInitialDelay(java.time.Duration.ofMinutes(10))
        }
        // 单队列隔离于 VAD/ASR；每个任务读取执行时的最新转写，不并行加载多个语言模型。
        val waiting = if (automatic && app.preferences.chargeOnly) "等待充电后自动总结；也可取消后手动生成" else "已排队，电量正常时系统将自动继续；可取消后手动重试"
        dao.saveSummaryRun(old?.copy(state = "QUEUED", message = waiting, updatedAtMillis = System.currentTimeMillis())
            ?: SummaryRunEntity(key, source.fingerprint, config.mode.name, identity(config), null, "QUEUED", waiting, System.currentTimeMillis()))
        work.enqueueUniqueWork(
            "sonfolio-summary:$key",
            if (automatic) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request.build(),
        )
        Unit
    }

    suspend fun enqueueForNewChunk(chunkId: String) {
        if (!app.summarySettings.read().automatic) return
        val transcriptIds = app.database.recordingDao().getTranscriptsForSummaryChunk(chunkId).toSet()
        val chunk = app.database.recordingDao().getChunk(chunkId) ?: return
        val chunkRows = dao.getReadyRowsInWindow(chunk.startedAtMillis, chunk.endedAtMillis ?: chunk.startedAtMillis)
        val ids = chunkRows.filter { row ->
            // 从已落库切片关联出本次对话；不回填发送全部历史记录。
            row.transcriptId in transcriptIds
        }.mapNotNull { it.conversationId }.distinct()
        val rows = ids.chunked(400).flatMap { dao.getReadyRowsForConversations(it) }
        for (id in ids) enqueue("conversation:$id", automatic = true)
        // 日期按切片录音发生时的时区归属，不随设备时区漂移。
        val zone = chunk.recordedZoneId.takeIf { it.isNotBlank() }
            ?.let { zoneId -> runCatching { ZoneId.of(zoneId) }.getOrNull() } ?: ZoneId.systemDefault()
        val dates = rows.filter { it.conversationId in ids }.flatMap { row ->
            val start = Instant.ofEpochMilli(row.startedAtMillis).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli((row.endedAtMillis - 1).coerceAtLeast(row.startedAtMillis)).atZone(zone).toLocalDate()
            generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        }.distinct()
        for (date in dates) enqueue("day:$date", automatic = true)
    }

    suspend fun cancelAll() = enqueueMutex.withLock {
        work.cancelAllWorkByTag(TAG).result.get()
        dao.getSummaryRuns().filter { it.state in listOf("QUEUED", "RUNNING") }.forEach {
            dao.updateSummaryRun(it.sourceKey, "CANCELLED", "配置改变或用户取消，已有小结保留", System.currentTimeMillis())
        }
    }

    suspend fun refreshConstraints() = withContext(Dispatchers.IO) { enqueueMutex.withLock {
        val config = app.summarySettings.read()
        work.getWorkInfosByTag(TAG).get().filter { !it.state.isFinished }.forEach { info ->
            val key = info.tags.firstOrNull { it.startsWith("summary-key:") }?.removePrefix("summary-key:") ?: return@forEach
            val revision = info.tags.firstOrNull { it.startsWith("summary-revision:") }?.removePrefix("summary-revision:") ?: config.revision
            val force = info.tags.firstOrNull { it.startsWith("summary-force:") }?.removePrefix("summary-force:")?.toBooleanStrictOrNull() ?: false
            val builder = OneTimeWorkRequestBuilder<SummaryWorker>().setId(info.id)
                .setInputData(workDataOf("key" to key, "revision" to revision, "force" to force))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(!force && app.preferences.chargeOnly).build())
            info.tags.forEach { builder.addTag(it) }
            work.updateWork(builder.build()).get()
            if (info.state != WorkInfo.State.RUNNING) dao.updateSummaryRun(key, "QUEUED",
                if (!force && app.preferences.chargeOnly) "等待充电后自动总结；也可取消后手动生成" else "已解除充电等待，电量正常时系统将继续", System.currentTimeMillis())
        }
    } }

    suspend fun cancel(key: String) {
        work.cancelAllWorkByTag("summary-key:$key").result.get()
        dao.updateSummaryRun(key, "CANCELLED", "已取消，已有小结保留", System.currentTimeMillis())
    }

    // ---- LLM 转写纠错（4.1）：复用同一套模型/密钥，默认手动触发，不改动原始转写（originalText 保留）。 ----

    fun correctionKey(key: String) = "correction:$key"

    suspend fun enqueueCorrection(key: String): Unit = enqueueMutex.withLock {
        val config = app.summarySettings.read()
        check(config.mode != SummaryMode.BASIC) { "请先在设置中选择「在手机上总结」或「在线总结」作为纠错模型" }
        val source = input(key)
        if (source.rows.isEmpty()) return
        val runKey = correctionKey(key)
        if (dao.getSummaryRun(runKey)?.state in listOf("QUEUED", "RUNNING")) return
        dao.saveSummaryRun(
            SummaryRunEntity(runKey, source.fingerprint, config.mode.name, identity(config), null, "QUEUED", "已排队，可取消", System.currentTimeMillis()),
        )
        work.enqueueUniqueWork(
            "sonfolio-correction:$key",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CorrectionWorker>()
                .setInputData(workDataOf("key" to key, "revision" to config.revision))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .addTag(TAG).addTag("correction").addTag("summary-key:$runKey").build(),
        )
        Unit
    }

    /** 逐批纠错并把结果落库；由 CorrectionWorker 调用。 */
    suspend fun correct(key: String) {
        val config = app.summarySettings.read()
        val runKey = correctionKey(key)
        val source = input(key)
        if (source.rows.isEmpty()) {
            dao.updateSummaryRun(runKey, "FAILED", "暂无可纠错的转写", System.currentTimeMillis())
            return
        }
        val parts = source.rows.chunked(CORRECTION_BATCH_LINES)
        val corrections = LinkedHashMap<String, String>()
        var skipped = 0
        withTimeout(CORRECTION_TIMEOUT_MILLIS) {
            parts.forEachIndexed { index, part ->
                ensureActive()
                dao.updateSummaryRun(runKey, "RUNNING", "正在纠错 ${index + 1}/${parts.size}", System.currentTimeMillis())
                val raw = withGenerator(config) { generate ->
                    generate(CorrectionPrompt.SYSTEM, CorrectionPrompt.user(part.map { it.text }))
                }
                val texts = runCatching { CorrectionPrompt.parse(raw, part.size) }.getOrNull()
                when {
                    texts != null -> part.forEachIndexed { i, row ->
                        val corrected = texts[i]
                        if (corrected.isNotEmpty() && corrected != row.text && !CorrectionPrompt.looksLikeLeak(corrected)) {
                            corrections[row.id] = corrected
                        }
                    }
                    // 小模型给不出稳定句数：对短批次逐句重试，仍失败就保留原文而不是整份失败。
                    part.size <= CORRECTION_FALLBACK_MAX_LINES -> {
                        for (row in part) {
                            ensureActive()
                            val single = runCatching {
                                val one = withGenerator(config) { generate ->
                                    generate(CorrectionPrompt.SYSTEM, CorrectionPrompt.user(listOf(row.text)))
                                }
                                CorrectionPrompt.parse(one, 1).single()
                            }.getOrNull()
                            if (single != null && single.isNotEmpty() && single != row.text && !CorrectionPrompt.looksLikeLeak(single)) {
                                corrections[row.id] = single
                            } else if (single == null) skipped++
                        }
                    }
                    else -> skipped += part.size
                }
            }
        }
        app.conversationRepository.applyTranscriptCorrections(corrections)
        val detail = buildString {
            append(if (corrections.isEmpty()) "没有发现需要纠正的内容" else "已纠正 ${corrections.size} 句")
            if (skipped > 0) append("，$skipped 句因模型输出不稳定已跳过")
        }
        dao.saveSummaryRun(
            SummaryRunEntity(
                runKey, source.fingerprint, config.mode.name, identity(config),
                """{"count":${corrections.size},"skipped":$skipped}""", "READY", detail, System.currentTimeMillis(),
            ),
        )
    }

    suspend fun cancelCorrection(key: String) {
        val runKey = correctionKey(key)
        work.cancelAllWorkByTag("summary-key:$runKey").result.get()
        dao.updateSummaryRun(runKey, "CANCELLED", "已取消，原文保留", System.currentTimeMillis())
    }

    suspend fun test(config: SummaryConfig): String {
        check(config.mode != SummaryMode.BASIC) { "基础整理不需要测试连接" }
        val input = SummaryInput("conversation:connection-test", listOf(SummaryText("test", 0, 1, "这是一段连接测试文字，不包含用户录音。我们决定明天上午检查录音按钮。", false)))
        val text = generate(config, SummaryPrompt.SYSTEM, SummaryPrompt.user(input, input.parts(500).single(), null, 0, 1))
        AiSummary.parse(text)
        return "测试通过：已返回结构化小结；未发送真实录音或转写"
    }

    internal suspend fun generate(config: SummaryConfig, system: String, user: String): String {
        return withGenerator(config) { generate -> generate(system, user) }
    }

    internal suspend fun <T> withGenerator(config: SummaryConfig, block: suspend (suspend (String, String) -> String) -> T): T {
        check(app.summarySettings.read().revision == config.revision) { "总结配置已改变，任务已取消" }
        return when (config.mode) {
            SummaryMode.REMOTE -> block { system, user -> RemoteSummaryTransport(app.summarySettings).generate(config, system, user) }
            SummaryMode.LOCAL -> localMutex.withLock {
                val file = app.summarySettings.modelFile(config)
                require(file?.isFile == true) { "请先在设置中下载总结模型" }
                LocalSummaryTransport(app).withSession(file, block)
            }
            SummaryMode.BASIC -> error("请先在设置中选择 AI 总结方式")
        }
    }

    companion object {
        const val TAG = "sonfolio-summary"
        private val enqueueMutex = Mutex()
        internal val localMutex = Mutex()
        /** 纠错每批句数：太小丢上下文，太大超出小模型上下文。 */
        private const val CORRECTION_BATCH_LINES = 30
        /** 整批句数不一致时，只有不超过这么多句才逐句重试（小模型对单句更稳）。 */
        private const val CORRECTION_FALLBACK_MAX_LINES = 10
        private const val CORRECTION_TIMEOUT_MILLIS = 9 * 60_000L
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
                coordinator.withGenerator(config) { generate ->
                for ((index, part) in parts.withIndex()) {
                    ensureActive()
                    dao.updateSummaryRun(key, "RUNNING", "正在整理 ${index + 1}/${parts.size} 部分", System.currentTimeMillis())
                    summary = AiSummary.parse(generate(SummaryPrompt.SYSTEM, SummaryPrompt.user(input, part, summary, index, parts.size)))
                }
                }
            }
            app.database.withTransaction {
                check(config.revision == app.summarySettings.read().revision) { "总结配置已改变，旧结果未应用" }
                if (coordinator.input(key).fingerprint != input.fingerprint) {
                    dao.updateSummaryRun(key, "STALE", "录音内容已更新，请重新生成", System.currentTimeMillis())
                } else {
                    dao.saveSummaryRun(SummaryRunEntity(key, input.fingerprint, config.mode.name, SummaryCoordinator.identity(config),
                        requireNotNull(summary).json(), "READY", null, System.currentTimeMillis()))
                }
            }
            input.rows.takeIf { it.isNotEmpty() }?.let { rows ->
                app.conversationRepository.rebuildFromTranscripts(rows.minOf { it.start }, rows.maxOf { it.end })
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

/** 纠错跑在总结的同一套模型上：本地走 :summary 绑定进程，在线走 HTTPS；默认不自动触发。 */
class CorrectionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = workerMutex.withLock {
        val app = applicationContext as SonfolioApplication
        val coordinator = app.summaryCoordinator
        val dao = app.database.conversationDao()
        val key = inputData.getString("key") ?: return@withLock Result.failure()
        val runKey = coordinator.correctionKey(key)
        val config = app.summarySettings.read()
        if (config.mode == SummaryMode.BASIC || config.revision != inputData.getString("revision")) return@withLock Result.success()
        return@withLock try {
            coordinator.correct(key)
            Result.success()
        } catch (_: TimeoutCancellationException) {
            withContext(NonCancellable) { dao.updateSummaryRun(runKey, "FAILED", "纠错耗时过长，原文保留，可重试", System.currentTimeMillis()) }
            Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable) { dao.updateSummaryRun(runKey, "CANCELLED", "已取消，原文保留", System.currentTimeMillis()) }
            throw error
        } catch (error: Throwable) {
            dao.updateSummaryRun(runKey, "FAILED", if (error is OutOfMemoryError) "纠错内存不足，原文保留" else error.message?.take(180) ?: "纠错失败，原文保留", System.currentTimeMillis())
            Result.success()
        }
    }

    companion object { private val workerMutex = Mutex() }
}
