package com.gongfpp.sonfolio

import java.io.File
import org.json.JSONObject

/** 固定总结质量集的评测器：换模型或提示词后各跑一次，输出可比指标。 */
internal object SummaryQualityEval {
    /** 仓库内唯一权威副本：docs/evaluation/summary-quality-set.json。 */
    val canonicalFile = File("../..", "docs/evaluation/summary-quality-set.json")
    val deviceCopy = File("src/androidTest/assets/summary-quality-set.json")

    data class QualityCase(
        val id: String,
        val category: String,
        val dialogue: List<String>,
        val expectKeyPhrases: List<String>,
        val forbidden: List<String>,
    )

    data class CaseResult(val id: String, val hits: Int, val total: Int, val forbiddenHits: List<String>) {
        val passed get() = forbiddenHits.isEmpty() && (total == 0 || hits * 2 >= total)
    }

    data class Report(val caseResults: List<CaseResult>) {
        val cases get() = caseResults.size
        val totalPhrases get() = caseResults.sumOf { it.total }
        val hitPhrases get() = caseResults.sumOf { it.hits }
        val factHitRate get() = if (totalPhrases == 0) 1.0 else hitPhrases.toDouble() / totalPhrases
        val forbiddenHits get() = caseResults.sumOf { it.forbiddenHits.size }
        val failedCases get() = caseResults.filterNot { it.passed }
        fun summarize(engine: String): String = buildString {
            appendLine("总结质量评测（$engine）：$cases 段对话，事实命中率 ${"%.0f".format(factHitRate * 100)}%")
            if (forbiddenHits > 0) appendLine("禁止内容出现 ${forbiddenHits} 次")
            failedCases.forEach { case ->
                val result = caseResults.first { it.id == case.id }
                appendLine("  ${case.id}：命中 ${result.hits}/${result.total}${if (result.forbiddenHits.isNotEmpty()) "，禁止内容 ${result.forbiddenHits}" else ""}")
            }
        }
    }

    fun load(file: File = canonicalFile): List<QualityCase> {
        val root = JSONObject(file.readText())
        require(root.getString("name") == "sonfolio-summary-quality-set") { "质量集文件不正确" }
        val cases = root.getJSONArray("cases")
        return (0 until cases.length()).map { index ->
            val case = cases.getJSONObject(index)
            QualityCase(
                id = case.getString("id"),
                category = case.getString("category"),
                dialogue = case.getJSONArray("dialogue").let { array -> (0 until array.length()).map { array.getString(it) } },
                expectKeyPhrases = case.optJSONArray("expectKeyPhrases")?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList(),
                forbidden = case.optJSONArray("forbidden")?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList(),
            )
        }
    }

    /** evaluate 面向任意“输入转写 → 输出小结”的实现；本地提取式引擎只是第一个被测者。 */
    fun evaluate(cases: List<QualityCase>, summarize: (List<String>) -> LocalSummaryEngine.Summary): Report {
        val results = cases.map { case ->
            val summary = summarize(case.dialogue)
            val output = listOf(summary.title, summary.brief, summary.keyPoints, summary.decisions, summary.followUps, summary.questions).joinToString(" ")
            val hits = case.expectKeyPhrases.count { it in output }
            val violations = case.forbidden.filter { it in output }
            CaseResult(case.id, hits, case.expectKeyPhrases.size, violations)
        }
        return Report(results)
    }
}
