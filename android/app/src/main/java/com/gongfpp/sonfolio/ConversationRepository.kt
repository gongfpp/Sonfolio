package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.data.local.ConversationEntity
import com.gongfpp.sonfolio.data.local.ConversationSummaryEntity
import com.gongfpp.sonfolio.data.local.DailyJournalEntity
import com.gongfpp.sonfolio.data.local.TranscriptAudioRow
import com.gongfpp.sonfolio.data.local.SonfolioDatabase
import androidx.room.withTransaction
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.gongfpp.sonfolio.summary.AiSummary
import com.gongfpp.sonfolio.summary.summaryInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConversationRepository(
    private val database: SonfolioDatabase,
    private val preferences: SonfolioPreferences,
) {
    private val conversationDao = database.conversationDao()
    private val rebuildLock = Mutex()
    fun observeTimeline(start: Long = Long.MIN_VALUE, end: Long = Long.MAX_VALUE): Flow<List<ConversationPreview>> =
        combine(
            conversationDao.observeTimeline(start, end),
            conversationDao.observeMarkedConversationIds(),
        ) { entities, markedIds ->
            entities.map { it.toPreview(markedIds.contains(it.id)) }
        }

    fun observeConversation(id: String): Flow<ConversationPreview?> = combine(conversationDao.observeConversation(id), conversationDao.observeMarkedConversationIds()) { row, marked -> row?.toPreview(row.id in marked) }


    /**
     * Builds the first useful memory layer from completed ASR rows. This is intentionally
     * deterministic: V0.1 needs a readable, repeatable result even when no network model is
     * available. A later summarizer can replace the text without changing the timeline contract.
     */
    suspend fun rebuildFromTranscripts(startedAt: Long? = null, endedAt: Long? = null) = rebuildLock.withLock {
        withContext(Dispatchers.Default) {
            repeat(4) { if (rebuildRegion(startedAt, endedAt)) return@withContext }
            error("整理期间内容持续更新，请稍后重试；原音和转写已保留")
        }
    }

    /** CPU work is outside the writer transaction. Normal chunk updates only inspect the
     * affected local days and continuous conversations crossing their boundaries. */
    private suspend fun rebuildRegion(startedAt: Long?, endedAt: Long?): Boolean {
        val fallbackZone = ZoneId.systemDefault()
        /** 日期归属属于事实数据：优先使用录音发生时的时区，历史空值才回退设备时区。 */
        fun rowZone(row: TranscriptAudioRow): ZoneId = runCatching { ZoneId.of(row.recordedZoneId) }.getOrNull() ?: fallbackZone
        suspend fun expandToCompleteDays(start: Long, end: Long, zone: ZoneId): Pair<Long, Long> {
            var rangeStart = start
            var rangeEnd = end
            while (true) {
                val neighbors = conversationDao.getReadyRowsInWindow(rangeStart - MERGE_GAP_MILLIS, rangeEnd + MERGE_GAP_MILLIS)
                val previous = conversationDao.getConversationsInWindow(rangeStart, rangeEnd)
                val first = minOf(rangeStart, neighbors.minOfOrNull { it.startedAtMillis } ?: rangeStart, previous.minOfOrNull { it.startedAtMillis } ?: rangeStart)
                val last = maxOf(rangeEnd, neighbors.maxOfOrNull { it.endedAtMillis } ?: rangeEnd, previous.maxOfOrNull { it.endedAtMillis } ?: rangeEnd)
                // Include complete days so daily summaries never lose unaffected conversations.
                val nextStart = DayWindow.of(Instant.ofEpochMilli(first).atZone(zone).toLocalDate(), zone).start
                val nextEnd = DayWindow.of(Instant.ofEpochMilli(last - 1).atZone(zone).toLocalDate(), zone).end
                if (rangeStart == nextStart && rangeEnd == nextEnd) break
                rangeStart = nextStart; rangeEnd = nextEnd
            }
            return rangeStart to rangeEnd
        }
        var rangeStart = startedAt?.let { DayWindow.of(Instant.ofEpochMilli(it).atZone(fallbackZone).toLocalDate(), fallbackZone).start } ?: Long.MIN_VALUE
        var rangeEnd = endedAt?.let { DayWindow.of(Instant.ofEpochMilli(it).atZone(fallbackZone).toLocalDate(), fallbackZone).end } ?: Long.MAX_VALUE
        var zone = fallbackZone
        if (startedAt != null && endedAt != null) {
            val bounds = expandToCompleteDays(rangeStart, rangeEnd, fallbackZone)
            rangeStart = bounds.first; rangeEnd = bounds.second
            // 找到受影响区间实际的录音时区，再用它重新对齐完整天窗口。
            zone = conversationDao.getReadyRowsInWindow(rangeStart, rangeEnd).asSequence().map(::rowZone).firstOrNull() ?: fallbackZone
            if (zone != fallbackZone) {
                val rebase = expandToCompleteDays(
                    DayWindow.of(Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate(), zone).start,
                    DayWindow.of(Instant.ofEpochMilli(endedAt).atZone(zone).toLocalDate(), zone).end,
                    zone,
                )
                rangeStart = rebase.first; rangeEnd = rebase.second
            }
        } else if (startedAt == null && endedAt == null) {
            zone = conversationDao.getReadyRowsInWindow(rangeStart, rangeEnd).asSequence().map(::rowZone).firstOrNull() ?: fallbackZone
        }
        val rows = conversationDao.getReadyRowsInWindow(rangeStart, rangeEnd)
        val gaps = database.recordingDao().getGapsInWindow(rangeStart, rangeEnd)
        val oldEntities = conversationDao.getConversationsInWindow(rangeStart, rangeEnd)
        // 重建只允许写自动生成字段；用户标题与备注从这里原样带回。
        val oldById = oldEntities.associateBy { it.id }
        val summaryKeys = if (startedAt == null || endedAt == null) null else {
            val firstDay = Instant.ofEpochMilli(rangeStart).atZone(zone).toLocalDate()
            val lastDay = Instant.ofEpochMilli(rangeEnd - 1).atZone(zone).toLocalDate()
            (oldEntities.map { "conversation:${it.id}" } + rows.mapNotNull { it.conversationId?.let { id -> "conversation:$id" } } +
                generateSequence(firstDay) { it.plusDays(1) }.takeWhile { it <= lastDay }.map { "day:$it" }.toList()).distinct()
        }
        suspend fun scopedSummaryRuns() = (summaryKeys?.chunked(400)?.flatMap { conversationDao.getSummaryRunsForKeys(it) }
            ?: conversationDao.getSummaryRuns()).associateBy { it.sourceKey }
        val aiRuns = scopedSummaryRuns()
        val markers = database.recordingDao().getMarkersInWindow(rangeStart, rangeEnd)
        val filterSnapshot = preferences.minimumSpeechSeconds to preferences.minimumTextCharacters

        val groups = mutableListOf<MutableList<TranscriptAudioRow>>()
        var groupEnd = Long.MIN_VALUE
        rows.forEach { row ->
            val current = groups.lastOrNull()
            if (current == null || row.startedAtMillis - groupEnd > MERGE_GAP_MILLIS || gaps.any {
                    it.startedAtMillis < row.startedAtMillis && (it.endedAtMillis ?: Long.MAX_VALUE) > groupEnd
                }) {
                groups += mutableListOf(row)
                groupEnd = row.endedAtMillis
            } else {
                current += row
                groupEnd = maxOf(groupEnd, row.endedAtMillis)
            }
        }

        fun cached(key: String, sourceRows: List<TranscriptAudioRow>): AiSummary? {
            val run = aiRuns[key] ?: return null
            if (run.outputJson == null || run.sourceHash != summaryInput(key, sourceRows, markers, gaps).fingerprint) return null
            return runCatching { AiSummary.parse(run.outputJson) }.getOrNull()
        }
        val visibleGroups = groups.filter { group ->
            !isShort(group, preferences) || groupIntersectsMarker(group, markers)
        }

        val summaries = visibleGroups.associate { group -> group.first().transcriptId to LocalSummaryEngine.summarize(group.map { it.text }) }
        val usedIds = mutableSetOf<String>()
        val entities = visibleGroups.map { group ->
            val start = group.first().startedAtMillis
            val end = group.maxOf { it.endedAtMillis }
            // 保留已有会话标识，边录边处理时不会让打开的详情失效。
            val id = group.firstNotNullOfOrNull { it.conversationId?.takeIf { id -> id.startsWith("auto-") && id !in usedIds } }
                ?: generateSequence("auto-${group.first().transcriptId}") { "$it-split" }.first { it !in usedIds }
            usedIds += id
            val summary = summaries.getValue(group.first().transcriptId)
            val ai = cached("conversation:$id", group.map { it.copy(conversationId = id) })
            // 用户数据所有权：本组原对话的用户标题/备注优先保留；合并时从并入的对话继承。
            val olds = group.mapNotNull { row -> row.conversationId?.let(oldById::get) }.distinctBy { it.id }
            val survivor = olds.firstOrNull { it.id == id }
            val inherited = olds.filter { it.id != id }
            val groupZone = rowZone(group.first())
            ConversationEntity(
                id = id,
                kind = ConversationType.Unknown.name,
                startedAtMillis = start,
                endedAtMillis = end,
                zoneId = groupZone.id,
                generatedTitle = ai?.title ?: summary.title,
                titleOverride = survivor?.titleOverride ?: inherited.firstNotNullOfOrNull { it.titleOverride },
                briefSummary = ai?.brief ?: summary.brief,
                summaryLevel = if (isDetailed(group)) "DETAILED" else "BRIEF",
                processingState = "READY",
                note = survivor?.note ?: inherited.firstNotNullOfOrNull { it.note },
                localStartDate = Instant.ofEpochMilli(start).atZone(groupZone).toLocalDate().toString(),
            )
        }
        val newSummaries = (
            visibleGroups.zip(entities)
                .filter { (group, entity) -> isDetailed(group) || cached("conversation:${entity.id}", group.map { it.copy(conversationId = entity.id) }) != null }
                .map { (group, entity) ->
                    val summary = summaries.getValue(group.first().transcriptId)
                    val ai = cached("conversation:${entity.id}", group.map { it.copy(conversationId = entity.id) })
                    val run = aiRuns["conversation:${entity.id}"]
                    ConversationSummaryEntity(
                        id = "summary-${entity.id}",
                        conversationId = entity.id,
                        keyPointsJson = jsonArray(ai?.keyPoints ?: summary.keyPoints),
                        decisionsJson = jsonArray(ai?.decisions ?: summary.decisions),
                        followUpsJson = jsonArray(ai?.followUps ?: summary.followUps),
                        openQuestionsJson = jsonArray(ai?.questions ?: summary.questions),
                        generatedLocally = ai == null || run?.provider != "REMOTE",
                        modelVersion = if (ai == null) "extractive-v0.2" else "${run?.provider}:${run?.model}",
                        generatedAtMillis = System.currentTimeMillis(),
                    )
                }
        )

        val groupsByDate = transcriptGroupsByDate(visibleGroups, zone)
        val newJournals = groupsByDate.map { (date, dateGroups) ->
            // 当天跨时区时，日期的时区取当天第一行所属录音的时区。
            val dayZone = dateGroups.asSequence().flatMap { it.asSequence() }.map(::rowZone).firstOrNull() ?: zone
            val dayWindow = DayWindow.of(date, dayZone)
            val daySummaries = dateGroups.associate { group -> group.first().transcriptId to LocalSummaryEngine.summarize(group.map { it.text }) }
            val linkedRows = visibleGroups.zip(entities).flatMap { (group, entity) -> group.map { it.copy(conversationId = entity.id) } }
            val ai = cached("day:$date", linkedRows)
            val run = aiRuns["day:$date"]
            (
                DailyJournalEntity(
                    id = "journal-$date",
                    localDate = date.toString(),
                    zoneId = dayZone.id,
                    narrative = ai?.brief ?: (if (gaps.any { dayWindow.overlaps(it.startedAtMillis, it.endedAtMillis ?: Long.MAX_VALUE) })
                        "本日存在录音缺口，以下仅根据已保存的内容整理，不代表完整经历。\n\n" else "") + dateGroups.joinToString("\n\n") { group ->
                        val time = Instant.ofEpochMilli(maxOf(group.first().startedAtMillis, dayWindow.start)).atZone(dayZone)
                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                        val crossDay = if (group.any { it.startedAtMillis < dayWindow.start || it.endedAtMillis > dayWindow.end }) "跨日内容 · " else ""
                        "$time · $crossDay${daySummaries.getValue(group.first().transcriptId).brief}"
                    },
                    memorableJson = jsonArray(ai?.keyPoints ?: dateGroups.filter { groupIntersectsMarker(it, markers) }
                        .map { daySummaries.getValue(it.first().transcriptId).brief }),
                    possibleActionsJson = jsonArray(ai?.followUps ?: dateGroups.flatMap { daySummaries.getValue(it.first().transcriptId).followUps }.distinct().take(5)),
                    sourceConversationCount = dateGroups.size,
                    generatedAtMillis = System.currentTimeMillis(),
                    modelVersion = if (ai == null) "extractive-v0.2" else "${run?.provider}:${run?.model}",
                    processingState = "READY",
                )
            )
        }
        val aliases = visibleGroups.zip(entities).flatMap { (group, entity) ->
            group.mapNotNull { it.conversationId }.distinct().filter { it != entity.id && it !in usedIds }
                .map { com.gongfpp.sonfolio.data.local.ConversationAliasEntity(it, entity.id) }
        }.distinctBy { it.oldId }
        val affectedDates = oldEntities.flatMap { entity ->
            val entityZone = runCatching { ZoneId.of(entity.zoneId) }.getOrDefault(zone)
            val start = Instant.ofEpochMilli(entity.startedAtMillis).atZone(entityZone).toLocalDate()
            val end = Instant.ofEpochMilli((entity.endedAtMillis - 1).coerceAtLeast(entity.startedAtMillis)).atZone(entityZone).toLocalDate()
            generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.map { it.toString() }.toList()
        }.toSet()
        return database.withTransaction {
            // New ASR/markers/gaps may arrive while the plan is computed. Never publish a stale plan.
            if (rows != conversationDao.getReadyRowsInWindow(rangeStart, rangeEnd) ||
                gaps != database.recordingDao().getGapsInWindow(rangeStart, rangeEnd) ||
                markers != database.recordingDao().getMarkersInWindow(rangeStart, rangeEnd) ||
                aiRuns != scopedSummaryRuns() ||
                filterSnapshot != (preferences.minimumSpeechSeconds to preferences.minimumTextCharacters)) return@withTransaction false
            // A previously merged ID can reappear after a split/filter change. A live ID must
            // never redirect to another conversation through an obsolete alias.
            entities.map { it.id }.chunked(400).forEach { conversationDao.removeAliasesForCanonicalIds(it) }
            conversationDao.insertAll(entities.filter { oldById[it.id] != it })
            val visibleIds = visibleGroups.flatten().map { it.transcriptId }.toSet()
            rows.filter { it.transcriptId !in visibleIds && it.conversationId != null }.map { it.transcriptId }.chunked(400)
                .forEach { conversationDao.detachTranscripts(it) }
            visibleGroups.zip(entities).forEach { (group, entity) ->
                group.filter { it.conversationId != entity.id }.map { it.transcriptId }.chunked(400)
                    .forEach { conversationDao.attachTranscripts(it, entity.id) }
            }
            oldEntities.map { it.id }.filter { it !in usedIds }.chunked(400).forEach { conversationDao.deleteConversations(it) }
            entities.map { it.id }.chunked(400).forEach { conversationDao.deleteSummaries(it) }
            conversationDao.insertConversationSummaries(newSummaries)
            (affectedDates - newJournals.map { it.localDate }.toSet()).forEach { conversationDao.deleteJournal(it) }
            newJournals.forEach { conversationDao.insertDailyJournal(it) }
            aliases.forEach { conversationDao.redirectAliases(it.oldId, it.canonicalId) }
            conversationDao.saveAliases(aliases)
            true
        }
    }

    fun observeTranscript(conversationId: String): Flow<List<TranscriptLine>> =
        conversationDao.observeTranscriptRows(conversationId).map { rows ->
            rows.map { row ->
                TranscriptLine(
                    id = row.transcriptId,
                    startedAtMillis = row.startedAtMillis,
                    endedAtMillis = row.endedAtMillis,
                    text = row.text,
                    localPath = row.localPath,
                    chunkStartedAtMillis = row.chunkStartedAtMillis,
                    isMarked = row.isMarked,
                    originalText = row.originalText,
                )
            }
        }

    fun observeSearch(
        query: String,
        dateRange: SearchDateRange = SearchDateRange.All,
        markedOnly: Boolean = false,
        visibleLimit: Int = SEARCH_BATCH_SIZE,
    ): Flow<SearchResults> {
        val request = SearchQuery.build(query, dateRange, markedOnly, visibleLimit)
        val noCriteria = query.isBlank() && dateRange == SearchDateRange.All && !markedOnly
        if (request.errorMessage != null || noCriteria) {
            return flowOf(SearchResults(emptyList(), requestedLimit = request.visibleLimit, errorMessage = request.errorMessage))
        }
        return conversationDao.observeSearch(androidx.sqlite.db.SimpleSQLiteQuery(request.sql, request.arguments.toTypedArray())).map { rows ->
            val hits = rows.take(request.visibleLimit).map { row ->
                SearchHit(
                    transcriptId = row.transcriptId,
                    conversationId = row.conversationId,
                    startedAtMillis = row.startedAtMillis,
                    endedAtMillis = row.endedAtMillis,
                    title = row.title,
                    text = row.text,
                    isMarked = row.isMarked,
                )
            }
            SearchResults(hits, hasMore = rows.size > request.visibleLimit, requestedLimit = request.visibleLimit)
        }
    }

    fun observeDailyJournal(localDate: String): Flow<DailyJournalEntity?> =
        conversationDao.observeDailyJournal(localDate)

    fun observeConversationSummary(conversationId: String): Flow<ConversationSummaryEntity?> =
        conversationDao.observeConversationSummary(conversationId)

    /** 用户修改对话标题（去掉首尾空白，限 30 字）；写入 titleOverride，自动标题不被触碰。 */
    suspend fun updateConversationTitle(conversationId: String, title: String) {
        val trimmed = title.trim().take(30)
        if (trimmed.isEmpty()) return
        conversationDao.updateConversationTitle(conversationId, trimmed)
    }

    /** 用户放弃手工标题，回到自动生成标题。 */
    suspend fun resetConversationTitle(conversationId: String) {
        conversationDao.resetConversationTitle(conversationId)
    }

    /** 用户写/清空简短备注（限 200 字）。 */
    suspend fun updateConversationNote(conversationId: String, note: String?) {
        val trimmed = note?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
        conversationDao.updateConversationNote(conversationId, trimmed)
    }

    /** 撤销这段对话涉及的标记。 */
    suspend fun removeMarkerForConversation(conversationId: String) {
        val conversation = conversationDao.getConversation(conversationId) ?: return
        conversationDao.deleteMarkersOverlapping(conversation.startedAtMillis, conversation.endedAtMillis)
    }

    /**
     * 修正单条转写文字；原始识别版本保留在 originalText。
     * 修正是对原始记录的编辑，派生数据必须同步失效：重建受影响区间的基础小结与每日回顾，
     * 并把关联的 AI 总结标记为 STALE，防止“原文改了，总结还是旧的”。
     */
    suspend fun updateTranscriptText(transcriptId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val row = conversationDao.getTranscriptWindow(transcriptId) ?: return
        conversationDao.updateTranscriptText(transcriptId, trimmed)
        val zone = ZoneId.systemDefault()
        val keys = buildSet {
            row.conversationId?.let { raw -> add("conversation:${conversationDao.resolveAlias(raw) ?: raw}") }
            val first = Instant.ofEpochMilli(row.startedAtMillis).atZone(zone).toLocalDate()
            val last = Instant.ofEpochMilli(maxOf(row.startedAtMillis, row.endedAtMillis - 1)).atZone(zone).toLocalDate()
            generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.forEach { add("day:$it") }
        }
        keys.forEach { key ->
            val run = conversationDao.getSummaryRun(key) ?: return@forEach
            if (run.state !in listOf("QUEUED", "RUNNING")) {
                conversationDao.updateSummaryRun(key, "STALE", "转写已修正，请重新生成 AI 总结", System.currentTimeMillis())
            }
        }
        try {
            rebuildFromTranscripts(row.startedAtMillis, row.endedAtMillis)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            // 文字已保存；整理遇到并发更新失败时留给下一次整理合并，不吞掉已确认的用户输入。
            android.util.Log.e("ConversationRepository", "转写修正后的整理未完成，待下次合并", error)
        }
    }
}

private const val MERGE_GAP_MILLIS = 2 * 60 * 1_000L


private fun isDetailed(group: List<TranscriptAudioRow>): Boolean {
    val duration = group.last().endedAtMillis - group.first().startedAtMillis
    val characters = group.sumOf { it.text.length }
    return group.size >= 8 || duration >= Duration.ofMinutes(10).toMillis() || characters >= 360
}

private fun isShort(group: List<TranscriptAudioRow>, preferences: SonfolioPreferences): Boolean {
    val speechMillis = group.sumOf { (it.endedAtMillis - it.startedAtMillis).coerceAtLeast(0L) }
    val characters = group.sumOf { row ->
        row.text.count { it.isLetterOrDigit() || it in '\u4E00'..'\u9FFF' }
    }
    return speechMillis < preferences.minimumSpeechSeconds * 1_000L &&
        characters < preferences.minimumTextCharacters
}

private fun groupIntersectsMarker(
    group: List<TranscriptAudioRow>,
    markers: List<com.gongfpp.sonfolio.data.local.MarkerEntity>,
): Boolean = markers.any { marker ->
    val start = marker.markedAtMillis - marker.windowBeforeMillis
    val end = marker.markedAtMillis + marker.windowAfterMillis
    group.any { row -> row.startedAtMillis <= end && row.endedAtMillis >= start }
}


private fun ConversationEntity.toPreview(isMarked: Boolean): ConversationPreview {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val start = Instant.ofEpochMilli(startedAtMillis).atZone(zone)
    val durationMillis = endedAtMillis - startedAtMillis
    val durationMinutes = Duration.ofMillis(durationMillis).toMinutes()
    val durationLabel = if (durationMinutes == 0L) "不足1分钟" else "${durationMinutes}分钟"
    val type = runCatching { ConversationType.valueOf(kind) }
        .getOrDefault(ConversationType.Unknown)

    return ConversationPreview(
        id = id,
        type = type,
        time = start.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")),
        title = displayTitle,
        duration = durationLabel,
        summary = briefSummary,
        summaryLevel = summaryLevel,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        isMarked = isMarked,
        note = note,
        titleOverride = titleOverride,
    )
}