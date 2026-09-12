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
        execute("CREATE TABLE conversations(id TEXT PRIMARY KEY, title TEXT)")
        execute("CREATE TABLE transcripts(id TEXT PRIMARY KEY, conversationId TEXT, startedAtMillis INTEGER, endedAtMillis INTEGER, text TEXT, processingState TEXT)")
        execute("CREATE TABLE markers(markedAtMillis INTEGER, windowBeforeMillis INTEGER, windowAfterMillis INTEGER)")
        execute("INSERT INTO conversations VALUES ('c', '测试主题'), ('other', '其他主题')")
    }

    @After fun tearDown() { database.close() }

    private fun execute(sql: String) { database.createStatement().use { it.execute(sql) } }

    private fun insert(id: String, text: String, start: Long = midnight, conversation: String = "c", state: String = "ASR_READY") {
        database.prepareStatement("INSERT INTO transcripts VALUES (?, ?, ?, ?, ?, ?)").use { statement ->
            listOf(id, conversation, start, start + 1_000, text, state).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun request(query: String, filter: String = "全部", limit: Int = 100) = SearchQuery.build(query, filter, limit, date, zone)

    private fun ids(query: SearchQuery): List<String> = database.prepareStatement(query.sql).use { statement ->
        query.arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("transcriptId")) } }
    }

    @Test fun fifthAndLaterKeywordsAreAllRequired() {
        insert("all", "甲乙 丙丁 戊己 庚辛 壬癸")
        insert("first-four", "甲乙 丙丁 戊己 庚辛")
        assertEquals(listOf("all"), ids(request("甲乙 丙丁 戊己 庚辛 壬癸")))
        assertTrue(ids(request("甲乙 丙丁 戊己 庚辛 不存在")).isEmpty())
    }

    @Test fun wildcardBackslashAndQuoteAreLiteralBoundParameters() {
        insert("literal", "进度100% 字段a_b 路径C:\\audio 单引号O'Reilly")
        insert("plain", "进度100 字段axb 路径audio")
        listOf("%", "_", "\\", "O'Reilly").forEach { assertEquals(listOf("literal"), ids(request(it))) }
        val injection = request("' OR 1=1 --")
        assertFalse(injection.sql.contains("OR 1=1"))
        assertTrue(ids(injection).isEmpty())
    }

    @Test fun fullWidthSpaceSeparatesKeywordsAndDuplicateTermsDoNotChangeResults() {
        insert("hit", "甲乙和丙丁")
        insert("partial", "甲乙")
        assertEquals(listOf("hit"), ids(request("甲乙　丙丁\u00a0甲乙")))
    }

    @Test fun firstBatchDetectsMoreAndExpandedBatchReachesAllRowsInStableOrder() {
        repeat(125) { insert(it.toString().padStart(3, '0'), "共同关键词") }
        val first = ids(request("共同关键词"))
        val expanded = ids(request("共同关键词", limit = 200))
        assertEquals(101, first.size)
        assertEquals(125, expanded.size)
        assertEquals(first, expanded.take(101))
        assertEquals("124", first.first())
        assertEquals("000", expanded.last())
        assertEquals(expanded.size, expanded.distinct().size)
    }

    @Test fun emptySearchDoesNotListEverythingAndDateFilterHasExclusiveEnd() {
        insert("previous", "内容", midnight - 1)
        insert("start", "内容", midnight)
        insert("end", "内容", midnight + 86_400_000L - 1)
        insert("next", "内容", midnight + 86_400_000L)
        assertTrue(ids(request(" ")).isEmpty())
        assertEquals(listOf("end", "start"), ids(request("", "今天")))
    }

    @Test fun weekMeansMondayThroughNextMondayNotRollingSevenDays() {
        val monday = date.minusDays(5).atStartOfDay(zone).toInstant().toEpochMilli()
        insert("before", "内容", monday - 1)
        insert("monday", "内容", monday)
        insert("sunday", "内容", monday + 7 * 86_400_000L - 1)
        insert("next-monday", "内容", monday + 7 * 86_400_000L)
        assertEquals(listOf("sunday", "monday"), ids(request("", "本周")))
    }

    @Test fun markerFindsEntireConversationButDoesNotMultiplyHits() {
        insert("early", "内容", midnight)
        insert("seed", "内容", midnight + 60_000)
        insert("other", "内容", midnight + 300_000, conversation = "other")
        execute("INSERT INTO markers VALUES (${midnight + 60_500}, 1000, 0), (${midnight + 60_800}, 1000, 0)")
        assertEquals(listOf("seed", "early"), ids(request("", "仅标记")))
    }

    @Test fun titleMatchesAndEmptyOrUnprocessedTranscriptsAreExcluded() {
        insert("ready", "内容")
        insert("empty", "")
        insert("pending", "内容", state = "ASR_RUNNING")
        assertEquals(listOf("ready"), ids(request("测试主题")))
    }

    @Test fun oversizedInputIsRejectedExplicitlyInsteadOfSilentlyTruncated() {
        insert("hit", "关键词")
        val tooMany = request((1..33).joinToString(" ") { "词$it" })
        assertNotNull(tooMany.errorMessage)
        assertTrue(ids(tooMany).isEmpty())
        val tooLong = request("字".repeat(513))
        assertNotNull(tooLong.errorMessage)
        assertTrue(ids(tooLong).isEmpty())
        assertNull(request((1..32).joinToString(" ") { "词$it" }).errorMessage)
    }
}
