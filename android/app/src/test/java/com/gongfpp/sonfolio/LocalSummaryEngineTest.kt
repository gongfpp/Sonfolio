package com.gongfpp.sonfolio

import org.junit.Assert.*
import org.junit.Test

class LocalSummaryEngineTest {
    @Test fun insufficientTextIsUnrecognized() {
        assertEquals("未识别", LocalSummaryEngine.summarize(listOf("嗯。", "啊" )).title)
    }

    @Test fun derivesTopicAndSeparatesStatementsWithoutInventingDecisions() {
        val result = LocalSummaryEngine.summarize(listOf("今晚安排系统投产。我们决定先部署测试环境。明天需要检查回滚方案。监控是谁负责？"))
        assertEquals("投产安排", result.title)
        assertEquals(listOf("我们决定先部署测试环境。"), result.decisions)
        assertTrue(result.followUps.contains("明天需要检查回滚方案。"))
        assertEquals(listOf("监控是谁负责？"), result.questions)
        assertTrue(LocalSummaryEngine.summarize(listOf("午饭一起去餐厅吃饭。" )).decisions.isEmpty())
    }

    @Test fun summaryJsonEscapesControlCharacters() {
        assertEquals("[\"a\\tb\\n\\\"c\\\"\"]", jsonArray(listOf("a\tb\n\"c\"")))
    }
}
