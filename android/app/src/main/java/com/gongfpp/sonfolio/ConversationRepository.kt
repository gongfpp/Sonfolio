package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.data.local.ConversationDao
import com.gongfpp.sonfolio.data.local.ConversationEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
}

private fun ConversationEntity.toPreview(): ConversationPreview {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val start = Instant.ofEpochMilli(startedAtMillis).atZone(zone)
    val durationMinutes = Duration.ofMillis(endedAtMillis - startedAtMillis).toMinutes()
    val type = runCatching { ConversationType.valueOf(kind) }
        .getOrDefault(ConversationType.Unknown)

    return ConversationPreview(
        id = id,
        type = type,
        time = start.format(DateTimeFormatter.ofPattern("HH:mm")),
        title = title,
        duration = "${durationMinutes}分钟",
        summary = briefSummary,
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
