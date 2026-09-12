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

class ConversationRepository(
    private val database: SonfolioDatabase,
    private val preferences: SonfolioPreferences,
) {
    private val conversationDao = database.conversationDao()
    fun observeTimeline(): Flow<List<ConversationPreview>> =
        combine(
            conversationDao.observeTimeline(),
            conversationDao.observeMarkedConversationIds(),
        ) { entities, markedIds ->
            entities.map { it.toPreview(markedIds.contains(it.id)) }
        }


    /**
     * Builds the first useful memory layer from completed ASR rows. This is intentionally
     * deterministic: V0.1 needs a readable, repeatable result even when no network model is
     * available. A later summarizer can replace the text without changing the timeline contract.
     */
    suspend fun rebuildFromTranscripts() = database.withTransaction {
        val rows = conversationDao.getReadyTranscriptRows()

        val groups = mutableListOf<MutableList<TranscriptAudioRow>>()
        var groupEnd = Long.MIN_VALUE
        rows.forEach { row ->
            val current = groups.lastOrNull()
            if (current == null || row.startedAtMillis - groupEnd > MERGE_GAP_MILLIS) {
                groups += mutableListOf(row)
                groupEnd = row.endedAtMillis
            } else {
                current += row
                groupEnd = maxOf(groupEnd, row.endedAtMillis)
            }
        }

        conversationDao.clearGeneratedConversationLinks()
        conversationDao.deleteGeneratedConversations()
        conversationDao.deleteDemoConversations()
        conversationDao.deleteGeneratedDailyJournals()

        val markers = conversationDao.getMarkers()
        val visibleGroups = groups.filter { group ->
            !isShort(group, preferences) || groupIntersectsMarker(group, markers)
        }

        val zone = ZoneId.systemDefault()
        val summaries = visibleGroups.associate { group -> group.first().transcriptId to LocalSummaryEngine.summarize(group.map { it.text }) }
        val entities = visibleGroups.map { group ->
            val start = group.first().startedAtMillis
            val end = group.maxOf { it.endedAtMillis }
            // 保留已有会话标识，边录边处理时不会让打开的详情失效。
            val id = group.firstNotNullOfOrNull { it.conversationId?.takeIf { id -> id.startsWith("auto-") } }
                ?: "auto-${group.first().transcriptId}"
            val summary = summaries.getValue(group.first().transcriptId)
            ConversationEntity(
                id = id,
                kind = ConversationType.Unknown.name,
                startedAtMillis = start,
                endedAtMillis = end,
                zoneId = zone.id,
                title = summary.title,
                briefSummary = summary.brief,
                summaryLevel = if (isDetailed(group)) "DETAILED" else "BRIEF",
                processingState = "READY",
            )
        }
        conversationDao.insertAll(entities)

        visibleGroups.zip(entities).forEach { (group, entity) ->
            group.map { it.transcriptId }.chunked(400).forEach { ids -> conversationDao.attachTranscripts(ids, entity.id) }
        }

        conversationDao.insertConversationSummaries(
            visibleGroups.zip(entities)
                .filter { (group, _) -> isDetailed(group) }
                .map { (group, entity) ->
                    val summary = summaries.getValue(group.first().transcriptId)
                    ConversationSummaryEntity(
                        id = "summary-${entity.id}",
                        conversationId = entity.id,
                        keyPointsJson = jsonArray(summary.keyPoints),
                        decisionsJson = jsonArray(summary.decisions),
                        followUpsJson = jsonArray(summary.followUps),
                        openQuestionsJson = jsonArray(summary.questions),
                        generatedLocally = true,
                        modelVersion = "extractive-v0.2",
                        generatedAtMillis = System.currentTimeMillis(),
                    )
                },
        )

        val groupsByDate = transcriptGroupsByDate(visibleGroups, zone)
        groupsByDate.forEach { (date, dateGroups) ->
            val dayWindow = DayWindow.of(date, zone)
            val daySummaries = dateGroups.associate { group -> group.first().transcriptId to LocalSummaryEngine.summarize(group.map { it.text }) }
            conversationDao.insertDailyJournal(
                DailyJournalEntity(
                    id = "journal-$date",
                    localDate = date.toString(),
                    zoneId = zone.id,
                    narrative = dateGroups.joinToString("\n\n") { group ->
                        val time = Instant.ofEpochMilli(maxOf(group.first().startedAtMillis, dayWindow.start)).atZone(zone)
                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                        val crossDay = if (group.any { it.startedAtMillis < dayWindow.start || it.endedAtMillis > dayWindow.end }) "跨日内容 · " else ""
                        "$time · $crossDay${daySummaries.getValue(group.first().transcriptId).brief}"
                    },
                    memorableJson = jsonArray(dateGroups.filter { groupIntersectsMarker(it, markers) }
                        .map { daySummaries.getValue(it.first().transcriptId).brief }),
                    possibleActionsJson = jsonArray(dateGroups.flatMap { daySummaries.getValue(it.first().transcriptId).followUps }.distinct().take(5)),
                    sourceConversationCount = dateGroups.size,
                    generatedAtMillis = System.currentTimeMillis(),
                    modelVersion = "extractive-v0.2",
                    processingState = "READY",
                ),
            )
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
