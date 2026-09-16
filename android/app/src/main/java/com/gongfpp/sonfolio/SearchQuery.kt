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

/** 关键词拆分规则由查询构造与高亮共用，避免「能搜到但不高亮」。 */
internal object SearchTerms {
    private val SEPARATOR = Regex("[\\s\\p{Z}]+")
    fun split(query: String): List<String> = query.trim().split(SEPARATOR).filter(String::isNotBlank).distinct()
}

/**
 * 搜索 SQL 唯一构造点。结果按「每场对话」聚合：返回该场对话的标题、正文命中句数、最匹配的一句
 * 片段和标记状态，而不是逐句平铺。空关键词时按最新对话列出（LIMIT 仍为 100）。
 *
 * 相关性排序：标题命中 > 正文命中句数 > 最近命中时间。用户输入始终通过参数绑定，并按字面匹配
 * LIKE 通配字符。
 */
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
            val terms = SearchTerms.split(query)
            val error = when {
                query.length > 512 -> "搜索内容最多 512 个字符，请缩短后重试。"
                terms.size > 32 -> "一次最多搜索 32 个关键词，请减少后重试。"
                else -> null
            }
            val args = mutableListOf<Any>()
            val titleExpr = "COALESCE(c.titleOverride, c.generatedTitle)"

            // 每个关键词在正文/标题各一个 0/1 命中列；参数顺序必须与 SQL 文本一致。
            val namedExpressions = mutableListOf("CASE WHEN $MARKED_SQL THEN 1 ELSE 0 END AS isMarked")
            terms.forEachIndexed { index, term ->
                val literal = "%${term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
                namedExpressions += "(t.text LIKE ? ESCAPE '\\') AS textHit$index"
                args += literal
                namedExpressions += "($titleExpr LIKE ? ESCAPE '\\') AS titleHit$index"
                args += literal
            }

            val sentenceHit = if (terms.isEmpty()) "0" else terms.indices.joinToString(" AND ") { "textHit$it" }
            val titleHit = if (terms.isEmpty()) "0" else terms.indices.joinToString(" AND ") { "titleHit$it" }
            val matchedFilter = if (terms.isEmpty()) "" else
                "WHERE " + terms.indices.joinToString(" AND ") { "(textHit$it OR titleHit$it)" }

            val conditions = mutableListOf("t.processingState = 'ASR_READY'", "t.text <> ''")
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
            // 多读一条只用于确认后面还有内容，不把首批对话数冒充总数。
            args += limit + 1

            val sql = """
                WITH raw AS (
                    SELECT t.id AS transcriptId, t.conversationId AS conversationId,
                        t.startedAtMillis AS startedAtMillis, t.endedAtMillis AS endedAtMillis, t.text AS text,
                        $titleExpr AS title,
                        ${namedExpressions.joinToString(",\n                        ")}
                    FROM transcripts t JOIN conversations c ON c.id = t.conversationId
                    WHERE ${conditions.joinToString(" AND ")}
                ),
                matched AS (
                    SELECT *, ($sentenceHit) AS sentenceHit, ($titleHit) AS titleHit
                    FROM raw
                    $matchedFilter
                ),
                best AS (
                    SELECT g.conversationId AS conversationId,
                        (SELECT m.transcriptId FROM matched m WHERE m.conversationId = g.conversationId
                            ORDER BY m.sentenceHit DESC, m.startedAtMillis ASC, m.transcriptId ASC LIMIT 1) AS transcriptId
                    FROM (SELECT DISTINCT conversationId FROM matched) g
                ),
                agg AS (
                    SELECT conversationId, SUM(sentenceHit) AS hitCount, MAX(titleHit) AS titleHit,
                        MAX(startedAtMillis) AS latestHitMillis
                    FROM matched GROUP BY conversationId
                )
                SELECT a.conversationId AS conversationId, r.title AS title,
                    a.hitCount AS hitCount, a.titleHit AS titleHit, a.latestHitMillis AS latestHitMillis,
                    r.transcriptId AS snippetTranscriptId, r.text AS snippetText,
                    r.startedAtMillis AS snippetStartedAtMillis, r.isMarked AS isMarked
                FROM agg a
                JOIN best b ON b.conversationId = a.conversationId
                JOIN matched r ON r.transcriptId = b.transcriptId
                ORDER BY a.titleHit DESC, a.hitCount DESC, a.latestHitMillis DESC, a.conversationId ASC
                LIMIT ?
            """.trimIndent()

            return SearchQuery(sql, args, limit, error)
        }
    }
}

private const val MARKED_SQL = """EXISTS (
    SELECT 1 FROM transcripts seed, markers m
    WHERE seed.conversationId = t.conversationId
      AND seed.startedAtMillis <= m.markedAtMillis + m.windowAfterMillis
      AND seed.endedAtMillis >= m.markedAtMillis - m.windowBeforeMillis
)"""
