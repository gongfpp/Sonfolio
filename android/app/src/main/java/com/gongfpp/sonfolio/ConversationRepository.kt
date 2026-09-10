package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.data.local.ConversationDao
import com.gongfpp.sonfolio.data.local.ConversationEntity
import com.gongfpp.sonfolio.data.local.ConversationSummaryEntity
import com.gongfpp.sonfolio.data.local.DailyJournalEntity
import com.gongfpp.sonfolio.data.local.TranscriptAudioRow
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConversationRepository(
    private val conversationDao: ConversationDao,
) {
    fun observeTimeline(): Flow<List<ConversationPreview>> =
        conversationDao.observeTimeline().map { entities ->
            entities.map(ConversationEntity::toPreview)
        }

    suspend fun seedDemoDataIfEmpty() {
        if (conversationDao.count() == 0) {
            conversationDao.insertAll(DemoConversations.entities)
        }
    }

    /**
     * Builds the first useful memory layer from completed ASR rows. This is intentionally
     * deterministic: V0.1 needs a readable, repeatable result even when no network model is
     * available. A later summarizer can replace the text without changing the timeline contract.
     */
    suspend fun rebuildFromTranscripts() {
        val rows = conversationDao.getReadyTranscriptRows()
        if (rows.isEmpty()) return

        val groups = mutableListOf<MutableList<TranscriptAudioRow>>()
        rows.forEach { row ->
            val current = groups.lastOrNull()
            if (current == null || row.startedAtMillis - current.last().endedAtMillis > MERGE_GAP_MILLIS) {
                groups += mutableListOf(row)
            } else {
                current += row
            }
        }

        conversationDao.clearGeneratedConversationLinks()
        conversationDao.deleteGeneratedConversations()
        conversationDao.deleteDemoConversations()

        val zone = ZoneId.systemDefault()
        val entities = groups.map { group ->
            val start = group.first().startedAtMillis
            val end = group.last().endedAtMillis
            val id = "auto-${UUID.randomUUID()}"
            ConversationEntity(
                id = id,
                kind = ConversationType.Unknown.name,
                startedAtMillis = start,
                endedAtMillis = end,
                zoneId = zone.id,
                title = "语音对话 · ${formatTime(start, zone)}",
                briefSummary = summarize(group),
                summaryLevel = if (isDetailed(group)) "DETAILED" else "BRIEF",
                processingState = "READY",
            )
        }
        conversationDao.insertAll(entities)

        groups.zip(entities).forEach { (group, entity) ->
            group.forEach { row -> conversationDao.attachTranscript(row.transcriptId, entity.id) }
        }

        conversationDao.insertConversationSummaries(
            groups.zip(entities)
                .filter { (group, _) -> isDetailed(group) }
                .map { (group, entity) ->
                    ConversationSummaryEntity(
                        id = "summary-${entity.id}",
                        conversationId = entity.id,
                        keyPointsJson = group.take(MAX_KEY_POINTS).joinToString(",") { jsonQuote(it.text) }
                            .let { "[$it]" },
                        decisionsJson = "[]",
                        followUpsJson = "[]",
                        openQuestionsJson = "[]",
                        generatedLocally = true,
                        modelVersion = "extractive-v0.1",
                        generatedAtMillis = System.currentTimeMillis(),
                    )
                },
        )

        val groupsByDate = groups.groupBy { group ->
            Instant.ofEpochMilli(group.first().startedAtMillis).atZone(zone).toLocalDate()
        }
        groupsByDate.forEach { (date, dateGroups) ->
            conversationDao.insertDailyJournal(
                DailyJournalEntity(
                    id = "journal-$date",
                    localDate = date.toString(),
                    zoneId = zone.id,
                    narrative = dailyNarrative(dateGroups),
                    memorableJson = "[]",
                    possibleActionsJson = "[]",
                    sourceConversationCount = dateGroups.size,
                    generatedAtMillis = System.currentTimeMillis(),
                    modelVersion = "extractive-v0.1",
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
                )
            }
        }

    fun observeSearch(query: String): Flow<List<SearchHit>> {
        val terms = query.trim().split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .take(4)
            .let { it + List(4 - it.size) { "" } }
        return conversationDao.observeSearch(terms[0], terms[1], terms[2], terms[3]).map { rows ->
            rows.map { row ->
                SearchHit(
                    transcriptId = row.transcriptId,
                    conversationId = row.conversationId,
                    startedAtMillis = row.startedAtMillis,
                    endedAtMillis = row.endedAtMillis,
                    title = row.title,
                    text = row.text,
                )
            }
        }
    }

    fun observeDailyJournal(localDate: String): Flow<DailyJournalEntity?> =
        conversationDao.observeDailyJournal(localDate)

    fun observeConversationSummary(conversationId: String): Flow<ConversationSummaryEntity?> =
        conversationDao.observeConversationSummary(conversationId)
}

private const val MERGE_GAP_MILLIS = 2 * 60 * 1_000L
private const val MAX_KEY_POINTS = 5

private fun formatTime(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun summarize(group: List<TranscriptAudioRow>): String {
    val text = group.joinToString(" ") { it.text.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
    if (text.isEmpty()) return "已识别 ${group.size} 段语音，暂时没有可读文本"
    return if (text.length <= 150) text else "${text.take(150)}…"
}

private fun isDetailed(group: List<TranscriptAudioRow>): Boolean {
    val duration = group.last().endedAtMillis - group.first().startedAtMillis
    val characters = group.sumOf { it.text.length }
    return group.size >= 8 || duration >= Duration.ofMinutes(10).toMillis() || characters >= 360
}

private fun dailyNarrative(groups: List<List<TranscriptAudioRow>>): String {
    val lead = groups.take(3).joinToString("；") { summarize(it).trimEnd('…') }
    return if (lead.isBlank()) {
        "今天暂时没有识别出可阅读的语音内容。"
    } else {
        "今天共整理 ${groups.size} 场对话。$lead。"
    }
}

private fun jsonQuote(text: String): String =
    "\"${text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

private fun ConversationEntity.toPreview(): ConversationPreview {
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
        time = start.format(DateTimeFormatter.ofPattern("HH:mm")),
        title = title,
        duration = durationLabel,
        summary = briefSummary,
        summaryLevel = summaryLevel,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
    )
}

private object DemoConversations {
    private const val ZONE_ID = "Asia/Shanghai"
    private val zone = ZoneId.of(ZONE_ID)

    val entities = listOf(
        conversation(
            id = "demo-release",
            type = ConversationType.Release,
            hour = 9,
            minute = 32,
            durationMinutes = 12,
            title = "与同事讨论系统投产",
            summary = "确认十点投产窗口，先备份数据库并复核回滚方案",
        ),
        conversation(
            id = "demo-lunch",
            type = ConversationType.Lunch,
            hour = 12,
            minute = 11,
            durationMinutes = 28,
            title = "午饭多人聊天",
            summary = "聊到最近的工作节奏和周末安排",
        ),
        conversation(
            id = "demo-game",
            type = ConversationType.Game,
            hour = 14,
            minute = 40,
            durationMinutes = 3,
            title = "记录一个游戏想法",
            summary = "构思电梯断电时的声音提示和玩家反馈",
        ),
        conversation(
            id = "demo-unknown",
            type = ConversationType.Unknown,
            hour = 18,
            minute = 20,
            durationMinutes = 7,
            title = "与未知人物对话",
            summary = "围绕晚餐和回家时间的简短交流",
        ),
    )

    private fun conversation(
        id: String,
        type: ConversationType,
        hour: Int,
        minute: Int,
        durationMinutes: Long,
        title: String,
        summary: String,
    ): ConversationEntity {
        val start = LocalDateTime.of(2026, 9, 8, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        return ConversationEntity(
            id = id,
            kind = type.name,
            startedAtMillis = start,
            endedAtMillis = start + Duration.ofMinutes(durationMinutes).toMillis(),
            zoneId = ZONE_ID,
            title = title,
            briefSummary = summary,
            summaryLevel = if (type == ConversationType.Game) "DETAILED" else "BRIEF",
            processingState = "READY",
        )
    }
}
