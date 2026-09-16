package com.gongfpp.sonfolio

import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

/** 在独立 SQLite 内存库执行生产查询；没有 Android 数据库或录音文件访问。 */
class SearchQueryTest {
    private lateinit var database: Connection
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 9, 12)
    private val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Before fun setUp() {
        database = DriverManager.getConnection("jdbc:sqlite::memory:")
        execute("CREATE TABLE conversations(id TEXT PRIMARY KEY, generatedTitle TEXT, titleOverride TEXT, briefSummary TEXT, summaryLevel TEXT, processingState TEXT)")
        execute("CREATE TABLE transcripts(id TEXT PRIMARY KEY, conversationId TEXT, startedAtMillis INTEGER, endedAtMillis INTEGER, text TEXT, processingState TEXT)")
        execute("CREATE TABLE markers(markedAtMillis INTEGER, windowBeforeMillis INTEGER, windowAfterMillis INTEGER)")
    }

    @After fun tearDown() { database.close() }

    private fun execute(sql: String) { database.createStatement().use { it.execute(sql) } }

    private fun conversation(id: String, title: String = "标题$id") {
        database.prepareStatement("INSERT INTO conversations VALUES (?, ?, NULL, '', 'BRIEF', 'READY')").use { statement ->
            statement.setObject(1, id); statement.setObject(2, title); statement.executeUpdate()
        }
    }

    private fun insert(id: String, text: String, start: Long = midnight, conversation: String, state: String = "ASR_READY") {
        database.prepareStatement("INSERT INTO transcripts VALUES (?, ?, ?, ?, ?, ?)").use { statement ->
            listOf(id, conversation, start, start + 1_000, text, state).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun request(
        query: String,
        dateRange: SearchDateRange = SearchDateRange.All,
        marked: Boolean = false,
        limit: Int = 100,
    ) = SearchQuery.build(query, dateRange, marked, limit, date, zone)

    private fun conversations(query: SearchQuery): List<String> = database.prepareStatement(query.sql).use { statement ->
        query.arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("conversationId")) } }
    }

    private fun hitCounts(query: SearchQuery): Map<String, Int> = database.prepareStatement(query.sql).use { statement ->
        query.arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString("conversationId"), rows.getInt("hitCount")) } }
    }

    @Test fun fifthAndLaterKeywordsAreAllRequired() {
        conversation("full"); conversation("partial")
        insert("t-full", "甲乙 丙丁 戊己 庚辛 壬癸", conversation = "full")
        insert("t-partial", "甲乙 丙丁 戊己 庚辛", conversation = "partial")
        assertEquals(listOf("full"), conversations(request("甲乙 丙丁 戊己 庚辛 壬癸")))
        assertTrue(conversations(request("甲乙 丙丁 戊己 庚辛 不存在")).isEmpty())
    }

    @Test fun wildcardBackslashAndQuoteAreLiteralBoundParameters() {
        conversation("literal"); conversation("plain")
        insert("l", "进度100% 字段a_b 路径C:\\audio 单引号O'Reilly", conversation = "literal")
        insert("p", "进度100 字段axb 路径audio", conversation = "plain")
        listOf("%", "_", "\\", "O'Reilly").forEach { assertEquals(listOf("literal"), conversations(request(it))) }
        val injection = request("' OR 1=1 --")
        assertFalse(injection.sql.contains("OR 1=1"))
        assertTrue(conversations(injection).isEmpty())
    }

    @Test fun fullWidthSpaceSeparatesKeywordsAndDuplicateTermsDoNotChangeResults() {
        conversation("hit"); conversation("partial")
        insert("h", "甲乙和丙丁", conversation = "hit")
        insert("p", "甲乙", conversation = "partial")
        assertEquals(listOf("hit"), conversations(request("甲乙　丙丁\u00a0甲乙")))
    }

    @Test fun firstBatchDetectsMoreAndExpandedBatchReachesAllConversationsInStableOrder() {
        repeat(125) { index ->
            val id = "c-" + index.toString().padStart(3, '0')
            conversation(id)
            insert("t-$index", "共同关键词", start = midnight + index * 1_000L, conversation = id)
        }
        val first = conversations(request("共同关键词"))
        val expanded = conversations(request("共同关键词", limit = 200))
        assertEquals(101, first.size)
        assertEquals(125, expanded.size)
        assertEquals(first, expanded.take(101))
        assertEquals("c-124", first.first())
        assertEquals("c-000", expanded.last())
        assertEquals(expanded.size, expanded.distinct().size)
    }

    @Test fun blankQueryListsLatestConversationsAndDateFilterHasExclusiveEnd() {
        listOf("previous" to midnight - 1, "start" to midnight, "end" to midnight + 86_400_000L - 1, "next" to midnight + 86_400_000L).forEach { (id, start) ->
            conversation(id); insert("t-$id", "内容", start = start, conversation = id)
        }
        // 空关键词不再拦截为 0 条，而是按最新对话列出。
        assertEquals(listOf("next", "end", "start", "previous"), conversations(request(" ")))
        assertEquals(listOf("end", "start"), conversations(request("", SearchDateRange.Today)))
    }

    @Test fun weekMeansMondayThroughNextMondayNotRollingSevenDays() {
        val monday = date.minusDays(5).atStartOfDay(zone).toInstant().toEpochMilli()
        listOf("before" to monday - 1, "monday" to monday, "sunday" to monday + 7 * 86_400_000L - 1, "next-monday" to monday + 7 * 86_400_000L).forEach { (id, start) ->
            conversation(id); insert("t-$id", "内容", start = start, conversation = id)
        }
        assertEquals(listOf("sunday", "monday"), conversations(request("", SearchDateRange.Week)))
    }

    @Test fun conversationAggregatesHitsWithoutMultiplication() {
        conversation("one"); conversation("other")
        insert("early", "内容", conversation = "one")
        insert("seed", "内容", start = midnight + 60_000, conversation = "one")
        insert("other-transcript", "内容", start = midnight + 300_000, conversation = "other")
        execute("INSERT INTO markers VALUES (${midnight + 60_500}, 1000, 0), (${midnight + 60_800}, 1000, 0)")
        assertEquals(listOf("one"), conversations(request("", marked = true)))
        assertEquals(mapOf("one" to 2), hitCounts(request("内容", marked = true)))
    }

    @Test fun titleHitRanksBeforeBodyHit() {
        conversation("title-only", title = "测试主题")
        conversation("body", title = "别的标题")
        conversation("none", title = "无关")
        insert("t1", "内容", conversation = "title-only")
        insert("t2", "这里提到测试主题", conversation = "body")
        insert("t3", "内容", conversation = "none")
        assertEquals(listOf("title-only", "body"), conversations(request("测试主题")))
        assertEquals(mapOf("title-only" to 0, "body" to 1), hitCounts(request("测试主题")))
    }

    @Test fun emptyAndUnprocessedTranscriptsAreExcluded() {
        conversation("c")
        insert("ready", "内容", conversation = "c")
        insert("empty", "", conversation = "c")
        insert("pending", "内容", conversation = "c", state = "ASR_RUNNING")
        // 同一场对话里有可用转写即可命中，空/未处理的行不参与。
        assertEquals(listOf("c"), conversations(request("内容")))
        assertEquals(mapOf("c" to 1), hitCounts(request("内容")))
    }

    @Test fun dateRangeAndMarkedAreIndependentAndCombine() {
        val yesterday = midnight - 86_400_000L
        conversation("marked-yesterday"); conversation("today")
        insert("my", "内容", start = yesterday, conversation = "marked-yesterday")
        execute("INSERT INTO markers VALUES (${yesterday + 500}, 1000, 0)")
        insert("td", "内容", start = midnight, conversation = "today")
        assertEquals(listOf("marked-yesterday"), conversations(request("", marked = true)))
        assertTrue(conversations(request("", SearchDateRange.Today, marked = true)).isEmpty())
        assertEquals(listOf("today"), conversations(request("", SearchDateRange.Today)))
    }

    @Test fun markedOnlyStillRequiresQueryTermsWhenGiven() {
        conversation("hit"); conversation("miss")
        insert("h", "会议 纪要", conversation = "hit")
        insert("m", "其他内容", conversation = "miss")
        execute("INSERT INTO markers VALUES (${midnight + 500}, 1000, 0)")
        assertEquals(listOf("hit"), conversations(request("会议", marked = true)))
        assertTrue(conversations(request("不存在", marked = true)).isEmpty())
    }

    @Test fun oversizedInputIsRejectedExplicitlyInsteadOfSilentlyTruncated() {
        conversation("hit"); insert("h", "关键词", conversation = "hit")
        val tooMany = request((1..33).joinToString(" ") { "词$it" })
        assertNotNull(tooMany.errorMessage)
        assertTrue(conversations(tooMany).isEmpty())
        val tooLong = request("字".repeat(513))
        assertNotNull(tooLong.errorMessage)
        assertTrue(conversations(tooLong).isEmpty())
        assertNull(request((1..32).joinToString(" ") { "词$it" }).errorMessage)
    }
}
