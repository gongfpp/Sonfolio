package com.gongfpp.sonfolio

import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.models.ModelCatalog
import com.gongfpp.sonfolio.summary.AiSummary
import com.gongfpp.sonfolio.summary.LocalSummaryTransport
import com.gongfpp.sonfolio.summary.SummaryInput
import com.gongfpp.sonfolio.summary.SummaryPrompt
import com.gongfpp.sonfolio.summary.SummaryText
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 固定质量集 + 当前已安装的本地 GGUF 模型，输出可对比的评测报告。
 * 模型不存在时跳过；换模型后重跑一次即可得到 0.5B 与新模型的对照数据。
 */
class LocalSummaryQualityTest {
    @Test fun qualityReportForInstalledModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = ModelCatalog.file(context.filesDir, ModelCatalog.summary)
        assumeTrue("需提前下载验收模型，不在测试中自动下载", file.isFile)
        // 质量集随测试 APK 打包，必须从 instrumentation 上下文读取。
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val cases = loadQualitySet(assets.open("summary-quality-set.json").readBytes().decodeToString())
        var parsed = 0
        val outputs = mutableMapOf<String, String>()
        LocalSummaryTransport(context).withSession(file) { send ->
            cases.forEach { case ->
                val input = SummaryInput(
                    "conversation:quality-${case.id}",
                    case.dialogue.mapIndexed { index, text -> SummaryText("q-${case.id}-$index", index.toLong(), (index + 1).toLong(), text, false) },
                )
                val output = send(SummaryPrompt.SYSTEM, SummaryPrompt.user(input, input.parts(500).single(), null, 0, 1))
                outputs[case.id] = output
                if (runCatching { AiSummary.parse(output) }.isSuccess) parsed++
            }
        }
        val totalPhrases = cases.sumOf { it.expectKeyPhrases.size }
        val factHits = cases.sumOf { case -> case.expectKeyPhrases.count { phrase -> phrase in (outputs[case.id] ?: "") } }
        val violations = cases.sumOf { case -> case.forbidden.count { phrase -> phrase in (outputs[case.id] ?: "") } }
        println("本地模型质量报告：$parsed/${cases.size} 段输出可解析；事实命中 $factHits/$totalPhrases；禁止内容出现 $violations 次")
        cases.forEach { case ->
            println("  ${case.id} 输出：${outputs[case.id]?.take(160)?.replace('\n', ' ')}")
        }
        assertTrue("评测应至少解析出一段结构化小结", parsed >= 1)
    }
}

/** 与 JVM 侧 SummaryQualityEval 保持一致的数据结构；androidTest 不依赖 test 源集。 */
private data class QualityCase(
    val id: String,
    val dialogue: List<String>,
    val expectKeyPhrases: List<String>,
    val forbidden: List<String>,
)

private fun loadQualitySet(json: String): List<QualityCase> {
    val root = JSONObject(json)
    require(root.getString("name") == "sonfolio-summary-quality-set") { "质量集文件不正确" }
    val cases = root.getJSONArray("cases")
    return (0 until cases.length()).map { index ->
        val case = cases.getJSONObject(index)
        QualityCase(
            id = case.getString("id"),
            dialogue = case.getJSONArray("dialogue").let { array -> (0 until array.length()).map { array.getString(it) } },
            expectKeyPhrases = case.optJSONArray("expectKeyPhrases")?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList(),
            forbidden = case.optJSONArray("forbidden")?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList(),
        )
    }
}
