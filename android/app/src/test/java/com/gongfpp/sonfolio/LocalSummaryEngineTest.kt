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

    @Test fun negativeQuestionsAndConditionsNeverBecomeCommitments() {
        val examples = listOf("我们还没有决定是否上线。", "你同意明天上线吗？", "不需要安排明天的会议。",
            "如果测试通过，我们决定明天上线。", "我们尚未确定会议安排。", "我们不同意这个决定。", "可能需要安排明天的会议。",
            "是否需要安排明天的会议", "我们决定取消明天的会议。", "我们未决定上线日期。")
        examples.forEach { text ->
            val result = LocalSummaryEngine.summarize(listOf(text))
            assertTrue(text, result.decisions.isEmpty())
            assertTrue(text, result.followUps.isEmpty())
            assertTrue(text, result.keyPoints.contains(text))
        }
        assertEquals(listOf("你同意明天上线吗？"), LocalSummaryEngine.summarize(listOf("你同意明天上线吗？")).questions)
    }
}
