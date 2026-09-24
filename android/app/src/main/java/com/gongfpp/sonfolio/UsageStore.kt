package com.gongfpp.sonfolio

import android.content.Context
import java.time.YearMonth

/**
 * 本月在线调用量统计（次数 / 音频秒数 / Token），只在本机累计，用于费用提醒。
 * 不记录任何音频或文字内容；跨月自动归零（按月打包 key）。
 */
class UsageStore(context: Context, name: String = "usage-stats") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun month() = YearMonth.now().toString()
    private fun key(kind: String, id: String) = "${month()}|$kind|$id"

    @Synchronized fun recordAsr(provider: String, seconds: Long) {
        prefs.edit()
            .putLong(key("asr-calls", provider), prefs.getLong(key("asr-calls", provider), 0) + 1)
            .putLong(key("asr-seconds", provider), prefs.getLong(key("asr-seconds", provider), 0) + seconds.coerceAtLeast(0))
            .apply()
    }

    @Synchronized fun recordLlm(model: String, tokens: Long) {
        prefs.edit()
            .putLong(key("llm-calls", model), prefs.getLong(key("llm-calls", model), 0) + 1)
            .putLong(key("llm-tokens", model), prefs.getLong(key("llm-tokens", model), 0) + tokens.coerceAtLeast(0))
            .apply()
    }

    data class AsrUsage(val provider: String, val calls: Long, val seconds: Long)
    data class LlmUsage(val model: String, val calls: Long, val tokens: Long)
    data class Snapshot(val month: String, val asr: List<AsrUsage>, val llm: List<LlmUsage>)

    fun snapshot(): Snapshot {
        val prefix = "${month()}|"
        val calls = mutableMapOf<String, MutableMap<String, Long>>()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(prefix) || value !is Long) return@forEach
            val parts = key.removePrefix(prefix).split('|')
            if (parts.size != 2) return@forEach
            calls.getOrPut(parts[0]) { mutableMapOf() }[parts[1]] = value
        }
        val asr = calls["asr-calls"].orEmpty().map { (provider, count) ->
            AsrUsage(provider, count, calls["asr-seconds"]?.get(provider) ?: 0L)
        }.sortedByDescending { it.seconds }
        val llm = calls["llm-calls"].orEmpty().map { (model, count) ->
            LlmUsage(model, count, calls["llm-tokens"]?.get(model) ?: 0L)
        }.sortedByDescending { it.calls }
        return Snapshot(month(), asr, llm)
    }

    /** 预估费用（元）。单价是粗估值，实际以各服务商账单为准。 */
    fun estimatedCost(snapshot: Snapshot): Double {
        val asr = snapshot.asr.sumOf { row -> row.seconds / 3600.0 * (ASR_CNY_PER_HOUR[row.provider] ?: 0.0) }
        val llm = snapshot.llm.sumOf { row -> row.tokens / 1_000_000.0 * LLM_CNY_PER_MILLION_TOKENS }
        return asr + llm
    }

    companion object {
        /** 仅用于提醒的粗估单价；豆包按音频小时、LLM 按百万 Token。 */
        val ASR_CNY_PER_HOUR = mapOf("DOUBAO" to 1.2)
        const val LLM_CNY_PER_MILLION_TOKENS = 2.0
    }
}
