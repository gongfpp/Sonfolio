package com.gongfpp.sonfolio.summary

import org.json.JSONArray
import org.json.JSONObject

/**
 * LLM 转写纠错：把同一段对话的逐句转写交给已配置的总结模型（本地 GGUF 或外部 API），
 * 纠正同音字/错别字、补标点、统一术语，返回与输入等长的句子列表。默认不启用，逐份手动触发。
 */
internal object CorrectionPrompt {
    val SYSTEM = "你是语音转写校对助手。输入是同一段对话的逐句转写（每行形如 [序号] 文本），" +
        "可能存在同音字、错别字、缺失标点。请在不改变说话内容、顺序与说话人的前提下：" +
        "纠正明显错别字与同音误认；补全标点；保留人名、产品名、专有名词与数字。" +
        "只输出一个 JSON 数组，元素是每行纠正后的文本，顺序和行数与输入完全一致；不要输出任何解释或代码块标记。"

    fun user(lines: List<String>): String =
        lines.mapIndexed { index, text -> "[${index + 1}] ${text.replace('\n', ' ')}" }.joinToString("\n") +
            "\n\n只输出 JSON 数组，共 ${lines.size} 个字符串，顺序不变；不要输出解释、不要复述输入、不要添加字段。"

    /**
     * 判断模型输出是否像「提示词/示例泄漏」或结构化摘要，而不是一句纠正后的转写。
     * 小模型常把示例或 JSON 结构原样吐回来；这类结果一律丢弃，保留原文。
     */
    fun looksLikeLeak(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return true
        return LEAK_KEYS.any { trimmed.contains(it) }
    }

    private val LEAK_KEYS = listOf("\"title\"", "\"brief\"", "\"keyPoints\"", "\"decisions\"", "示例", "JSON 数组")

    /**
     * 解析模型输出并校验句数。优先 JSON；小模型常常给不出合法 JSON，退化为「每行一句」解析。
     * 句数与输入不一致时判定失败，避免错位写回。
     */
    fun parse(raw: String, expected: Int): List<String> {
        require(expected > 0)
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        jsonArray(cleaned)?.let { values ->
            if (values.size == expected) return values
        }
        val lines = cleaned.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace(FIRST_PREFIX, "") }
            .filter { it.isNotEmpty() }
        require(lines.size == expected) { "模型返回 ${lines.size} 句，与输入 $expected 句不一致，已保留原文" }
        return lines
    }

    private fun jsonArray(cleaned: String): List<String>? {
        val array = runCatching { JSONArray(cleaned) }.getOrElse {
            val obj = runCatching { JSONObject(cleaned) }.getOrNull() ?: return null
            obj.optJSONArray("lines") ?: obj.optJSONArray("texts") ?: return null
        }
        return (0 until array.length()).map { index ->
            (array.opt(index) as? String)?.trim() ?: return null
        }
    }

    private val FIRST_PREFIX = Regex("^\\s*(?:\\[?\\d+\\]?[.、:：)）]?|[-*•·])\\s*")
}
