package com.gongfpp.sonfolio

import android.content.Context
import java.time.YearMonth

/**
 * 本月在线调用量统计（次数 / 音频秒数 / Token / 估算费用），只在本机累计，用于费用提醒。
 * 不记录任何音频或文字内容；跨月自动归零（按月打包 key）。
 *
 * 费用在记录当时按 [UsagePricing] 计价后累加（微元，即 1e-6 元），不保存每次调用的明细，
 * 因此历史金额不会因为后来调整单价表而变化；无法定价的调用只累计次数，不猜金额。
 */
class UsageStore(context: Context, name: String = "usage-stats") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun month() = YearMonth.now().toString()
    private fun key(kind: String, id: String) = "${month()}|$kind|$id"

    @Synchronized fun recordAsr(provider: String, seconds: Long, model: String = "未记录模型", feature: String = "转文字") {
        val duration = seconds.coerceAtLeast(0)
        prefs.edit()
            .putLong(key("asr-calls", provider), prefs.getLong(key("asr-calls", provider), 0) + 1)
            .putLong(key("asr-seconds", provider), prefs.getLong(key("asr-seconds", provider), 0) + duration)
            .detail("asr", provider, model, feature, duration, 0, UsagePricing.asr(provider, model, duration))
            .apply()
    }

    /** [promptTokens]/[completionTokens] 来自响应体的 usage 字段；输入输出分开才能按供应商单价计价。 */
    @Synchronized fun recordLlm(
        model: String,
        promptTokens: Long,
        completionTokens: Long,
        provider: String = "未记录提供商",
        feature: String = "总结",
        atMillis: Long = System.currentTimeMillis(),
    ) {
        val prompt = promptTokens.coerceAtLeast(0)
        val completion = completionTokens.coerceAtLeast(0)
        val tokens = prompt + completion
        prefs.edit()
            .putLong(key("llm-calls", model), prefs.getLong(key("llm-calls", model), 0) + 1)
            .putLong(key("llm-tokens", model), prefs.getLong(key("llm-tokens", model), 0) + tokens)
            .detail("llm", provider, model, feature, 0, tokens, UsagePricing.llm(model, prompt, completion, atMillis))
            .apply()
    }

    private fun android.content.SharedPreferences.Editor.detail(
        kind: String,
        provider: String,
        model: String,
        feature: String,
        seconds: Long,
        tokens: Long,
        quote: UsagePricing.Quote?,
    ): android.content.SharedPreferences.Editor {
        val id = listOf(kind, provider, model, feature).joinToString("|") { android.net.Uri.encode(it) }
        listOf("calls" to 1L, "seconds" to seconds, "tokens" to tokens).forEach { (metric, amount) ->
            val key = key("detail-$metric", id)
            putLong(key, prefs.getLong(key, 0) + amount)
        }
        // 微元累计避免浮点漂移；无法定价的调用只记次数。
        if (quote != null) {
            val key = key("detail-cost", id)
            putLong(key, prefs.getLong(key, 0) + Math.round(quote.yuan * 1_000_000))
        } else {
            val key = key("detail-unpriced", id)
            putLong(key, prefs.getLong(key, 0) + 1)
        }
        return this
    }

    data class Detail(
        val kind: String,
        val provider: String,
        val model: String,
        val feature: String,
        val calls: Long,
        val seconds: Long,
        val tokens: Long,
        /** 估算金额（元）。 */
        val cost: Double = 0.0,
        /** 未计价的调用次数：单价未知、订阅制或升级前的旧记录。 */
        val unpricedCalls: Long = 0,
    )

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
                values["${month()}|detail-tokens|$id"] as? Long ?: 0,
                (values["${month()}|detail-cost|$id"] as? Long ?: 0L) / 1_000_000.0,
                values["${month()}|detail-unpriced|$id"] as? Long ?: 0)
        }.toMutableList()
        val snapshot = snapshot()
        // 旧记录只有月总量，没有输入输出拆分，因此无法计价，单独标为未计价。
        snapshot.asr.forEach { old ->
            val known = rows.filter { it.kind == "asr" && it.provider == old.provider }
            val remaining = old.calls - known.sumOf { it.calls }
            if (remaining > 0) rows += Detail("asr", old.provider, "旧记录（未记录模型）", "转文字（旧记录）", remaining, (old.seconds - known.sumOf { it.seconds }).coerceAtLeast(0), 0, unpricedCalls = remaining)
        }
        snapshot.llm.forEach { old ->
            val known = rows.filter { it.kind == "llm" && it.model == old.model }
            val remaining = old.calls - known.sumOf { it.calls }
            if (remaining > 0) rows += Detail("llm", "旧记录", old.model, "总结/纠错（旧记录未区分）", remaining, 0, (old.tokens - known.sumOf { it.tokens }).coerceAtLeast(0), unpricedCalls = remaining)
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

    data class MonthUsage(
        val month: String,
        val calls: Long,
        val seconds: Long,
        val tokens: Long,
        val cost: Double = 0.0,
        val unpricedCalls: Long = 0,
    )

    /** 最近 months 个月的用量（含当月），用于图形化展示。 */
    fun monthlyHistory(months: Int = 6): List<MonthUsage> {
        val base = YearMonth.now()
        return (months - 1 downTo 0).map { offset ->
            val ym = base.minusMonths(offset.toLong()).toString()
            totals(prefix = "$ym|").copy(month = ym)
        }
    }

    /** 自安装以来的累计用量，month 为 "全部"。 */
    fun lifetime(): MonthUsage = totals(prefix = "").copy(month = "全部")

    private fun totals(prefix: String): MonthUsage {
        var calls = 0L; var seconds = 0L; var tokens = 0L; var cost = 0L; var unpriced = 0L
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith(prefix) || value !is Long) return@forEach
            // key 形如 "2026-11|detail-cost|<id>"，第二段固定是统计项。
            when (key.split('|').getOrNull(1)) {
                "asr-calls", "llm-calls" -> calls += value
                "asr-seconds" -> seconds += value
                "llm-tokens" -> tokens += value
                "detail-cost" -> cost += value
                "detail-unpriced" -> unpriced += value
            }
        }
        return MonthUsage("", calls, seconds, tokens, cost / 1_000_000.0, unpriced)
    }
}
