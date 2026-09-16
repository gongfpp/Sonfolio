package com.gongfpp.sonfolio

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定 ASR 质量集的副本一致性；数据集变化必须在 docs 与真机资产间同步。 */
class AsrZhEnSetTest {
    private val canonical = File("../..", "docs/evaluation/asr-zh-en-set.json")
    private val deviceCopy = File("src/androidTest/assets/asr-zh-en-set.json")

    @Test fun canonicalAndDeviceCopiesAreIdentical() {
        assertTrue("缺少仓库权威素材集：${canonical.path}", canonical.isFile)
        assertTrue("缺少真机评测副本：${deviceCopy.path}", deviceCopy.isFile)
        assertEquals(canonical.readText().trim(), deviceCopy.readText().trim())
    }

    @Test fun setCoversChineseEnglishAndCantoneseWithHttpsSources() {
        val root = JSONObject(canonical.readText())
        assertEquals("sonfolio-asr-zh-en-set", root.getString("name"))
        val cases = root.getJSONArray("cases")
        assertTrue(cases.length() >= 6)
        val languages = (0 until cases.length()).map { cases.getJSONObject(it).getString("language") }.toSet()
        assertTrue("需要中文素材", "zh" in languages)
        assertTrue("需要英文素材", "en" in languages)
        assertTrue("需要粤语素材", "yue" in languages)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertTrue("${case.getString("id")} 参考文本为空", case.getString("reference").isNotBlank())
            assertTrue("${case.getString("id")} 素材必须来自 HTTPS", case.getString("url").startsWith("https://"))
            assertTrue("${case.getString("id")} 缺少 CER 上限", case.getDouble("maxCer") > 0.0)
        }
    }
}
