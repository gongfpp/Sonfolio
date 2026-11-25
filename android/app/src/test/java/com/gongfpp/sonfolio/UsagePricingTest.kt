package com.gongfpp.sonfolio

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 单价表是界面数字的唯一来源，用固定时刻锁定峰谷与分档口径。 */
class UsagePricingTest {
    private val beijing = ZoneId.of("Asia/Shanghai")
    private fun at(hour: Int): Long = ZonedDateTime.of(2026, 11, 25, hour, 0, 0, 0, beijing).toInstant().toEpochMilli()

    @Test fun `deepseek splits peak and idle price by beijing hour`() {
        // 空闲：1.5 元/百万输入 + 4.5 元/百万输出。
        assertEquals(6.0, UsagePricing.llm("deepseek-v4-flash", 1_000_000, 1_000_000, at(3))!!.yuan, 1e-9)
        // 高峰（9–12、14–18）翻倍：3.0 + 9.0。
        assertEquals(12.0, UsagePricing.llm("deepseek-v4-flash", 1_000_000, 1_000_000, at(10))!!.yuan, 1e-9)
        assertEquals(12.0, UsagePricing.llm("deepseek-v4-flash", 1_000_000, 1_000_000, at(15))!!.yuan, 1e-9)
        // 18 点后回到空闲价。
        assertEquals(6.0, UsagePricing.llm("deepseek-v4-flash", 1_000_000, 1_000_000, at(18))!!.yuan, 1e-9)
        // Pro 档：4.5 + 13.5。
        assertEquals(18.0, UsagePricing.llm("deepseek-v4-pro", 1_000_000, 1_000_000, at(3))!!.yuan, 1e-9)
    }

    @Test fun `qwen max prices by prompt length tier`() {
        // 32K 内：输入 2.5 元/百万，输出 10 元/百万。
        assertEquals(10.025, UsagePricing.llm("qwen3-max", 10_000, 1_000_000, at(3))!!.yuan, 1e-9)
        // 32K–128K：输入 4 元，输出 16 元。
        assertEquals(16.16, UsagePricing.llm("qwen3-max", 40_000, 1_000_000, at(3))!!.yuan, 1e-9)
        // 128K–256K：输入 7 元，输出 28 元。
        assertEquals(29.4, UsagePricing.llm("qwen3-max", 200_000, 1_000_000, at(3))!!.yuan, 1e-9)
        // 超出已收录档位不给数字。
        assertNull(UsagePricing.llm("qwen3-max", 300_000, 1_000, at(3)))
    }

    @Test fun `subscription and unknown models stay unpriced`() {
        assertNull(UsagePricing.llm("deepseek-v4.1-flash", 1_000, 1_000, at(3)))
        assertNull(UsagePricing.llm("glm-5.3-flash", 1_000, 1_000, at(3)))
        assertNull(UsagePricing.llm("my-local-model", 1_000, 1_000, at(3)))
    }

    @Test fun `asr prices by duration and leaves unlisted providers unpriced`() {
        assertEquals(0.00022 * 3600, UsagePricing.asr("QWEN", "qwen3-asr-flash", 3600)!!.yuan, 1e-9)
        assertEquals(0.8, UsagePricing.asr("DOUBAO", "volc.seedasr.auc", 3600)!!.yuan, 1e-9)
        assertEquals(0.0, UsagePricing.asr("SILICONFLOW", "FunAudioLLM/SenseVoiceSmall", 3600)!!.yuan, 1e-9)
        assertNull(UsagePricing.asr("SILICONFLOW", "TeleAI/TeleSpeechASR", 3600))
        assertNull(UsagePricing.asr("CUSTOM", "whatever", 3600))
    }

    @Test fun `zero token call still counts a call without cost`() {
        assertEquals(0.0, UsagePricing.llm("qwen-plus", 0, 0, at(3))!!.yuan, 1e-9)
    }
}
