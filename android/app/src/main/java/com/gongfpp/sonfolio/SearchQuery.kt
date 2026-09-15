package com.gongfpp.sonfolio

import java.time.LocalDate
import java.time.ZoneId

internal const val SEARCH_BATCH_SIZE = 100

/** 搜索的时间范围，与「仅标记」相互独立，两类条件可同时生效。 */
enum class SearchDateRange(val label: String) {
    All("全部"),
    Today("今天"),
    Week("本周"),
}

/** SQL 结构只来自代码，用户输入始终通过参数绑定，并按字面匹配 LIKE 通配字符。 */
internal data class SearchQuery(
    val sql: String,
    val arguments: List<Any>,
    val visibleLimit: Int,
    val errorMessage: String? = null,
) {
    companion object {
        fun build(
            query: String,
            dateRange: SearchDateRange = SearchDateRange.All,
            markedOnly: Boolean = false,
            visibleLimit: Int = SEARCH_BATCH_SIZE,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): SearchQuery {
            val limit = visibleLimit.coerceIn(1, Int.MAX_VALUE - 1)
            val terms = query.trim().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotBlank).distinct()
            val error = when {
                query.length > 512 -> "搜索内容最多 512 个字符，请缩短后重试。"
                terms.size > 32 -> "一次最多搜索 32 个关键词，请减少后重试。"
                else -> null
            }
            val args = mutableListOf<Any>()
            val conditions = mutableListOf("t.processingState = 'ASR_READY'", "t.text <> ''")
            // 没有任何条件（无关键词、不限时间、不筛标记）时不应列出全部记录。
            val noCriteria = terms.isEmpty() && dateRange == SearchDateRange.All && !markedOnly
            if (error != null || noCriteria) conditions += "0 = 1"
            else terms.forEach { term ->
                val literal = "%${term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
                conditions += "(t.text LIKE ? ESCAPE '\\' OR COALESCE(c.titleOverride, c.generatedTitle) LIKE ? ESCAPE '\\')"
                args += literal
                args += literal
            }
            val startDate = when (dateRange) {
                SearchDateRange.Today -> today
                SearchDateRange.Week -> today.minusDays(today.dayOfWeek.value.toLong() - 1)
                SearchDateRange.All -> null
            }
            if (startDate != null) {
                conditions += "t.startedAtMillis >= ? AND t.startedAtMillis < ?"
                args += startDate.atStartOfDay(zone).toInstant().toEpochMilli()
                args += startDate.plusDays(if (dateRange == SearchDateRange.Week) 7 else 1).atStartOfDay(zone).toInstant().toEpochMilli()
            }
            if (markedOnly) conditions += MARKED_SQL
            // 多读一条只用于确认后面还有内容，不把首批数量冒充总数。
            args += limit + 1
            return SearchQuery(
                """
                SELECT t.id AS transcriptId, c.id AS conversationId,
                    t.startedAtMillis, t.endedAtMillis,
                    COALESCE(c.titleOverride, c.generatedTitle) AS title, t.text,
                    CASE WHEN $MARKED_SQL THEN 1 ELSE 0 END AS isMarked
                FROM transcripts t JOIN conversations c ON c.id = t.conversationId
                WHERE ${conditions.joinToString(" AND ")}
                ORDER BY t.startedAtMillis DESC, t.id DESC
                LIMIT ?
                """.trimIndent(),
                args, limit, error,
            )
        }
    }
}

private const val MARKED_SQL = """EXISTS (
    SELECT 1 FROM transcripts seed, markers m
    WHERE seed.conversationId = t.conversationId
      AND seed.startedAtMillis <= m.markedAtMillis + m.windowAfterMillis
      AND seed.endedAtMillis >= m.markedAtMillis - m.windowBeforeMillis
)"""
