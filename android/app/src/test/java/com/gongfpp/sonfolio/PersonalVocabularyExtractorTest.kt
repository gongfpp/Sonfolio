package com.gongfpp.sonfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalVocabularyExtractorTest {
    @Test fun extractsReplacementProperNoun() {
        assertEquals(listOf("Codex"), PersonalVocabularyExtractor.candidates("我用 Cortex 写代码", "我用 Codex 写代码"))
    }

    @Test fun extractsChineseReplacement() {
        assertEquals(listOf("Sonfolio"), PersonalVocabularyExtractor.candidates("松佛里奥这个应用", "Sonfolio 这个应用"))
    }

    @Test fun unchangedTextYieldsNothing() {
        assertTrue(PersonalVocabularyExtractor.candidates("今天天气不错", "今天天气不错").isEmpty())
    }

    @Test fun casingOnlyChangeYieldsNothing() {
        assertTrue(PersonalVocabularyExtractor.candidates("cortex", "Cortex").isEmpty())
    }

    @Test fun ignoresEnglishStopwords() {
        assertTrue(PersonalVocabularyExtractor.candidates("hello", "hello the").isEmpty())
    }

    @Test fun excludesLongChineseRewrites() {
        assertTrue(PersonalVocabularyExtractor.candidates("嗯", "我想说明一下明天会议的具体安排").isEmpty())
    }

    @Test fun keepsShortChineseTerm() {
        assertEquals(listOf("宁波银行"), PersonalVocabularyExtractor.candidates("那个银行", "宁波银行"))
    }

    @Test fun deduplicatesAndCapsCandidates() {
        val result = PersonalVocabularyExtractor.candidates("无", "Alpha Alpha Beta")
        assertEquals(listOf("Alpha", "Beta"), result)
    }
}
