package com.gongfpp.sonfolio

import java.time.Instant
import java.time.ZoneId

/**
 * 在线调用单价表，只用于估算，不是账单。
 *
 * 单价取自各服务商官方价格页，基准 [BASIS]；服务商调价后这些数字会失真，所以界面必须
 * 同时标出估算口径。无法确认单价的调用（自定义模型、订阅制服务、未收录模型）一律返回
 * null —— 宁可不给数字，也不给错数字。
 */
internal object UsagePricing {
    const val BASIS = "2026 年 11 月官方挂牌价"

    /** 估算结果：金额（元）与计价依据，用于在明细里解释这个数字怎么来的。 */
    data class Quote(val yuan: Double, val note: String)

    /** 大模型按输入、输出分别计价，单位：元/百万 token。 */
    private data class TokenPrice(val input: Double, val output: Double, val note: String)

    private val beijing = ZoneId.of("Asia/Shanghai")

    /**
     * DeepSeek 峰谷定价：高峰为北京时间 9–12 点、14–18 点，其余为空闲时段，
     * 空闲价为高峰价的一半。按调用发生的时刻判定，和账单口径一致。
     */
    private fun deepseekPeak(atMillis: Long): Boolean {
        val hour = Instant.ofEpochMilli(atMillis).atZone(beijing).hour
        return hour in 9..11 || hour in 14..17
    }

    fun llm(model: String, promptTokens: Long, completionTokens: Long, atMillis: Long): Quote? {
        val price = tokenPrice(model, promptTokens, atMillis) ?: return null
        val yuan = promptTokens.coerceAtLeast(0) * price.input / 1_000_000.0 +
            completionTokens.coerceAtLeast(0) * price.output / 1_000_000.0
        return Quote(yuan, price.note)
    }

    private fun tokenPrice(model: String, promptTokens: Long, atMillis: Long): TokenPrice? {
        val name = model.lowercase()
        return when {
            name.startsWith("deepseek-v4-pro") -> if (deepseekPeak(atMillis)) {
                TokenPrice(9.0, 27.0, "DeepSeek V4 Pro · 高峰价")
            } else {
                TokenPrice(4.5, 13.5, "DeepSeek V4 Pro · 空闲价")
            }
            name.startsWith("deepseek-v4-flash") -> if (deepseekPeak(atMillis)) {
                TokenPrice(3.0, 9.0, "DeepSeek V4 Flash · 高峰价")
            } else {
                TokenPrice(1.5, 4.5, "DeepSeek V4 Flash · 空闲价")
            }
            // 通义按单次请求的输入长度分档计费，整条请求都按所在档位结算。
            name.startsWith("qwen3-max") -> when {
                promptTokens <= 32_000 -> TokenPrice(2.5, 10.0, "qwen3-max · 32K 内档")
                promptTokens <= 128_000 -> TokenPrice(4.0, 16.0, "qwen3-max · 32K–128K 档")
                promptTokens <= 256_000 -> TokenPrice(7.0, 28.0, "qwen3-max · 128K–256K 档")
                else -> null
            }
            name.startsWith("qwen-plus") -> when {
                promptTokens <= 128_000 -> TokenPrice(0.8, 2.0, "qwen-plus · 128K 内档")
                promptTokens <= 256_000 -> TokenPrice(2.88, 24.0, "qwen-plus · 128K–256K 档")
                else -> null
            }
            else -> null
        }
    }

    /** 识别按音频时长计费，与文本长度无关。 */
    fun asr(provider: String, model: String, seconds: Long): Quote? {
        val duration = seconds.coerceAtLeast(0)
        return when {
            provider == "QWEN" && model.startsWith("qwen3-asr-flash") ->
                Quote(duration * 0.00022, "qwen3-asr-flash · 0.00022 元/秒")
            provider == "DOUBAO" ->
                Quote(duration * 0.8 / 3600, "豆包录音文件识别 2.0 · 0.8 元/小时")
            provider == "SILICONFLOW" && model.contains("SenseVoiceSmall") ->
                Quote(0.0, "SenseVoiceSmall · 官方标注免费")
            else -> null
        }
    }
}
