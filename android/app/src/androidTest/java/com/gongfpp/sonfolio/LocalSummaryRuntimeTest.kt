package com.gongfpp.sonfolio

import android.app.ActivityManager
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.models.ModelCatalog
import com.gongfpp.sonfolio.summary.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalSummaryRuntimeTest {
    @Test fun twoPartsUseOneBoundProcessAndReturnStructuredChinese() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = ModelCatalog.file(context.filesDir, ModelCatalog.summary)
        assumeTrue("需提前下载验收模型，不在测试中自动下载", file.isFile)
        assertTrue(ModelCatalog.verify(file, ModelCatalog.summary.files.first()))
        val source = SummaryInput("conversation:runtime-test", listOf(SummaryText("test", 0, 1, "我们讨论了周末去公园散步，决定周六上午九点见面。", false)))
        LocalSummaryTransport(context).withSession(file) { send ->
            val first = AiSummary.parse(send(SummaryPrompt.SYSTEM, SummaryPrompt.user(source, source.parts(500).single(), null, 0, 2)))
            assertTrue(first.brief.any { it in '\u4E00'..'\u9FFF' })
            val manager = context.getSystemService(ActivityManager::class.java)
            val pid = manager.runningAppProcesses.single { it.processName == "${context.packageName}:summary" }.pid
            val second = AiSummary.parse(send(SummaryPrompt.SYSTEM, SummaryPrompt.user(source, "新增资料：如果周六下雨，就把散步改到周日上午。", first, 1, 2)))
            assertTrue(second.brief.isNotBlank())
            assertEquals(pid, manager.runningAppProcesses.single { it.processName == "${context.packageName}:summary" }.pid)
        }
    }
}
