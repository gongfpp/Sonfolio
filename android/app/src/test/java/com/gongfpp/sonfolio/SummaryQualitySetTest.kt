package com.gongfpp.sonfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地提取式引擎的固定质量基线；数据集或引擎任一方变化都会在此暴露回归。 */
class SummaryQualitySetTest {
    @Test fun canonicalAndDeviceCopiesAreIdentical() {
        assertTrue("缺少仓库权威质量集：${SummaryQualityEval.canonicalFile.path}", SummaryQualityEval.canonicalFile.isFile)
        assertTrue("缺少真机评测副本：${SummaryQualityEval.deviceCopy.path}", SummaryQualityEval.deviceCopy.isFile)
        assertEquals(
            SummaryQualityEval.canonicalFile.readText().trim(),
            SummaryQualityEval.deviceCopy.readText().trim(),
        )
    }

    @Test fun extractiveBaselineMeetsQualityFloor() {
        val cases = SummaryQualityEval.load()
        assertEquals("质量集应包含 20 段对话", 20, cases.size)
        val report = SummaryQualityEval.evaluate(cases) { texts -> LocalSummaryEngine.summarize(texts) }
        assertTrue(
            "提取式基线未达标：\n${report.summarize("extractive-v0.2")}",
            report.factHitRate >= 0.9 && report.forbiddenHits == 0,
        )
    }
}
