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

    @Synchronized fun recordAsr(provider: String, seconds: Long, model: String = "未记录模型", feature: String = "转文字") {
        prefs.edit()
            .putLong(key("asr-calls", provider), prefs.getLong(key("asr-calls", provider), 0) + 1)
            .putLong(key("asr-seconds", provider), prefs.getLong(key("asr-seconds", provider), 0) + seconds.coerceAtLeast(0))
            .detail("asr", provider, model, feature, seconds.coerceAtLeast(0), 0)
            .apply()
    }

    @Synchronized fun recordLlm(model: String, tokens: Long, provider: String = "未记录提供商", feature: String = "总结") {
        prefs.edit()
            .putLong(key("llm-calls", model), prefs.getLong(key("llm-calls", model), 0) + 1)
            .putLong(key("llm-tokens", model), prefs.getLong(key("llm-tokens", model), 0) + tokens.coerceAtLeast(0))
            .detail("llm", provider, model, feature, 0, tokens.coerceAtLeast(0))
            .apply()
    }

    private fun android.content.SharedPreferences.Editor.detail(kind: String, provider: String, model: String, feature: String, seconds: Long, tokens: Long): android.content.SharedPreferences.Editor {
        val id = listOf(kind, provider, model, feature).joinToString("|") { android.net.Uri.encode(it) }
        listOf("calls" to 1L, "seconds" to seconds, "tokens" to tokens).forEach { (metric, amount) ->
            val key = key("detail-$metric", id)
            putLong(key, prefs.getLong(key, 0) + amount)
        }
        return this
    }

    data class Detail(val kind: String, val provider: String, val model: String, val feature: String, val calls: Long, val seconds: Long, val tokens: Long)

    /** 老版本没有功能维度，只显示未区分部分，不猜测，也不重复累计。 */
    @Synchronized fun details(): List<Detail> {
        val values = prefs.all
        val prefix = "${month()}|detail-calls|"
        val rows = values.mapNotNull { (key, value) ->
            if (!key.startsWith(prefix) || value !is Long) return@mapNotNull null
            val id = key.removePrefix(prefix)
            val parts = id.split('|').map { android.net.Uri.decode(it) }
            if (parts.size != 4) return@mapNotNull null
            Detail(parts[0], parts[1], parts[2], parts[3], value,
                values["${month()}|detail-seconds|$id"] as? Long ?: 0,
                values["${month()}|detail-tokens|$id"] as? Long ?: 0)
        }.toMutableList()
        val snapshot = snapshot()
        snapshot.asr.forEach { old ->
            val known = rows.filter { it.kind == "asr" && it.provider == old.provider }
            val remaining = old.calls - known.sumOf { it.calls }
            if (remaining > 0) rows += Detail("asr", old.provider, "旧记录（未记录模型）", "转文字（旧记录）", remaining, (old.seconds - known.sumOf { it.seconds }).coerceAtLeast(0), 0)
        }
        snapshot.llm.forEach { old ->
            val known = rows.filter { it.kind == "llm" && it.model == old.model }
            val remaining = old.calls - known.sumOf { it.calls }
            if (remaining > 0) rows += Detail("llm", "旧记录", old.model, "总结/纠错（旧记录未区分）", remaining, 0, (old.tokens - known.sumOf { it.tokens }).coerceAtLeast(0))
        }
        return rows.sortedByDescending { it.calls }
    }

    fun observeChanges(onChange: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
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

    data class MonthUsage(val month: String, val calls: Long, val seconds: Long, val tokens: Long)

    /** 最近 months 个月的用量（含当月），用于图形化展示。 */
    fun monthlyHistory(months: Int = 6): List<MonthUsage> {
        val base = YearMonth.now()
        return (months - 1 downTo 0).map { offset ->
            val ym = base.minusMonths(offset.toLong()).toString()
            val prefix = "$ym|"
            var calls = 0L; var seconds = 0L; var tokens = 0L
            prefs.all.forEach { (key, value) ->
                if (!key.startsWith(prefix) || value !is Long) return@forEach
                when (key.removePrefix(prefix).substringBefore('|')) {
                    "asr-calls", "llm-calls" -> calls += value
                    "asr-seconds" -> seconds += value
                    "llm-tokens" -> tokens += value
                }
            }
            MonthUsage(ym, calls, seconds, tokens)
        }
    }
}
