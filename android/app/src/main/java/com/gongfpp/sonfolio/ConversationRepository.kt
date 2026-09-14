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
        val zone = ZoneId.systemDefault()
        var rangeStart = startedAt?.let { DayWindow.of(Instant.ofEpochMilli(it).atZone(zone).toLocalDate(), zone).start } ?: Long.MIN_VALUE
        var rangeEnd = endedAt?.let { DayWindow.of(Instant.ofEpochMilli(it).atZone(zone).toLocalDate(), zone).end } ?: Long.MAX_VALUE
        if (startedAt != null && endedAt != null) {
            while (true) {
                val neighbors = conversationDao.getReadyRowsInWindow(rangeStart - MERGE_GAP_MILLIS, rangeEnd + MERGE_GAP_MILLIS)
                val previous = conversationDao.getConversationsInWindow(rangeStart, rangeEnd)
                val start = minOf(rangeStart, neighbors.minOfOrNull { it.startedAtMillis } ?: rangeStart, previous.minOfOrNull { it.startedAtMillis } ?: rangeStart)
                val end = maxOf(rangeEnd, neighbors.maxOfOrNull { it.endedAtMillis } ?: rangeEnd, previous.maxOfOrNull { it.endedAtMillis } ?: rangeEnd)
                // Include complete days so daily summaries never lose unaffected conversations.
                val nextStart = DayWindow.of(Instant.ofEpochMilli(start).atZone(zone).toLocalDate(), zone).start
                val nextEnd = DayWindow.of(Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate(), zone).end
                if (rangeStart == nextStart && rangeEnd == nextEnd) break
                rangeStart = nextStart; rangeEnd = nextEnd
            }
        }
        val rows = conversationDao.getReadyRowsInWindow(rangeStart, rangeEnd)
        val gaps = database.recordingDao().getGapsInWindow(rangeStart, rangeEnd)
        val oldEntities = conversationDao.getConversationsInWindow(rangeStart, rangeEnd)
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
            ConversationEntity(
                id = id,
                kind = ConversationType.Unknown.name,
                startedAtMillis = start,
                endedAtMillis = end,
                zoneId = zone.id,
                title = ai?.title ?: summary.title,
                briefSummary = ai?.brief ?: summary.brief,
                summaryLevel = if (isDetailed(group)) "DETAILED" else "BRIEF",
                processingState = "READY",
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
            val dayWindow = DayWindow.of(date, zone)
            val daySummaries = dateGroups.associate { group -> group.first().transcriptId to LocalSummaryEngine.summarize(group.map { it.text }) }
            val linkedRows = visibleGroups.zip(entities).flatMap { (group, entity) -> group.map { it.copy(conversationId = entity.id) } }
            val ai = cached("day:$date", linkedRows)
            val run = aiRuns["day:$date"]
            (
                DailyJournalEntity(
                    id = "journal-$date",
                    localDate = date.toString(),
                    zoneId = zone.id,
                    narrative = ai?.brief ?: (if (gaps.any { dayWindow.overlaps(it.startedAtMillis, it.endedAtMillis ?: Long.MAX_VALUE) })
                        "本日存在录音缺口，以下仅根据已保存的内容整理，不代表完整经历。\n\n" else "") + dateGroups.joinToString("\n\n") { group ->
                        val time = Instant.ofEpochMilli(maxOf(group.first().startedAtMillis, dayWindow.start)).atZone(zone)
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
            val start = Instant.ofEpochMilli(entity.startedAtMillis).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli((entity.endedAtMillis - 1).coerceAtLeast(entity.startedAtMillis)).atZone(zone).toLocalDate()
            generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.map { it.toString() }.toList()
        }.toSet()
        return database.withTransaction {
            // New ASR/markers/gaps may arrive while the plan is computed. Never publish a stale plan.
            if (rows != conversationDao.getReadyRowsInWindow(rangeStart, rangeEnd) ||
                gaps != database.recordingDao().getGapsInWindow(rangeStart, rangeEnd) ||
                markers != database.recordingDao().getMarkersInWindow(rangeStart, rangeEnd) ||
                aiRuns != scopedSummaryRuns() ||
                filterSnapshot != (preferences.minimumSpeechSeconds to preferences.minimumTextCharacters)) return@withTransaction false
            val oldById = oldEntities.associateBy { it.id }
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
                )
            }
        }

    fun observeSearch(query: String, filter: String = "全部", visibleLimit: Int = SEARCH_BATCH_SIZE): Flow<SearchResults> {
        val request = SearchQuery.build(query, filter, visibleLimit)
        if (request.errorMessage != null || (query.isBlank() && filter == "全部")) {
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
        title = title,
        duration = durationLabel,
        summary = briefSummary,
        summaryLevel = summaryLevel,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        isMarked = isMarked,
    )
}
