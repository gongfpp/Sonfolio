package com.gongfpp.sonfolio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.models.ModelCatalog
import com.gongfpp.sonfolio.processing.FireRedAsrCtcProcessor
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 中英文离线识别质量验收：FireRedASR2-CTC int8 对固定素材集的字错率（CER）。
 * 需要先下载 FireRedASR2-CTC 模型，并用 scripts/prepare-asr-eval.mjs 推送素材；缺任一项时跳过。
 */
@RunWith(AndroidJUnit4::class)
class FireRedAsrZhEnQualityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Case(val id: String, val language: String, val file: String, val reference: String, val maxCer: Double)

    @Test fun fireRedAsrMeetsCerBudgetOnZhEnSet() {
        val artifact = ModelCatalog.byId("fire-red-asr-ctc")!!
        assumeTrue("需先下载 FireRedASR2-CTC 模型", ModelCatalog.installed(context.filesDir, artifact))
        val directory = context.getExternalFilesDir("asr-eval")
        val cases = loadCases().filter { directory != null && File(directory, it.file).isFile }
        assumeTrue("需先用 scripts/prepare-asr-eval.mjs 推送素材", cases.isNotEmpty())

        val report = StringBuilder()
        report.append("FireRedASR2-CTC 中英文质量集（${cases.size} 例）\n")
        val results = mutableListOf<Pair<Case, String>>()
        val started = System.currentTimeMillis()
        FireRedAsrCtcProcessor(context).use { processor ->
            for (case in cases) {
                val text = processor.transcribe(File(directory, case.file), 0, 10 * 60_000L)
                results += case to text
                val cer = charErrorRate(case.reference, text)
                report.append("[${case.language}] ${case.id} CER=${"%.3f".format(cer)}（上限 ${case.maxCer}）\n")
                report.append("  实际：$text\n")
            }
        }
        val elapsed = (System.currentTimeMillis() - started) / 1000.0
        val overall = results.sumOf { (case, text) -> charErrorRate(case.reference, text) } / results.size
        report.append("平均 CER=${"%.3f".format(overall)}，耗时 ${"%.1f".format(elapsed)}s\n")
        println(report)

        results.forEach { (case, text) ->
            assertTrue("${case.id} 转写为空", text.isNotBlank())
            val cer = charErrorRate(case.reference, text)
            assertTrue("${case.id} CER $cer 超过上限 ${case.maxCer}\n$report", cer <= case.maxCer)
        }
    }

    private fun loadCases(): List<Case> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val root = JSONObject(assets.open("asr-zh-en-set.json").bufferedReader().use { it.readText() })
        val array = root.getJSONArray("cases")
        return (0 until array.length()).map { index ->
            val case = array.getJSONObject(index)
            Case(
                id = case.getString("id"),
                language = case.getString("language"),
                file = case.getString("file"),
                reference = case.getString("reference"),
                maxCer = case.getDouble("maxCer"),
            )
        }
    }

    private fun charErrorRate(reference: String, hypothesis: String): Double {
        val expected = normalize(reference)
        if (expected.isEmpty()) return if (normalize(hypothesis).isEmpty()) 0.0 else 1.0
        val actual = normalize(hypothesis)
        val previous = IntArray(actual.length + 1) { it }
        val current = IntArray(actual.length + 1)
        for (i in 1..expected.length) {
            current[0] = i
            for (j in 1..actual.length) {
                val substitution = previous[j - 1] + if (expected[i - 1] == actual[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            previous.indices.forEach { previous[it] = current[it] }
        }
        return previous[actual.length].toDouble() / expected.length
    }

    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}
