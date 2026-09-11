package com.gongfpp.sonfolio

import android.app.ActivityManager
import android.os.Process
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.processing.DetectedSpeechWindow
import com.gongfpp.sonfolio.processing.InferenceClient
import com.gongfpp.sonfolio.recording.RecordingController
import com.gongfpp.sonfolio.recording.WavChunkWriter
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真机验收会新增一段实际录音，保留它供人工回听，不触碰已有录音。 */
@RunWith(AndroidJUnit4::class)
class RecordingReliabilityTest {
    @Test fun recordingSurvivesModelDeathAndRotatesAfterFiveMinutes(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SonfolioApplication
        val dao = app.database.recordingDao()
        check(dao.getDanglingChunks().isEmpty()) { "已有用户录音正在进行，跳过破坏性验收" }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val fixture = File(app.filesDir, "qa-crash-${System.nanoTime()}.wav")
        try {
            WavChunkWriter(fixture, 16_000, 1).use { it.write(ByteArray(960_000), 960_000) }
            // 先完成一次真实调用，避免在服务尚未建立连接时杀死冷启动进程。
            // Android 不保证未建立连接时触发 onServiceDisconnected，不能把这一竞态当作推理崩溃。
            val client = InferenceClient(app)
            client.detect(fixture)
            Log.i("RecordingReliabilityTest", "模型服务已完成首次调用，开始实际录音")
            scenario.onActivity { RecordingController.start(it) }
            val first = withTimeout(15_000) { dao.observeActiveChunk().first { it != null }!! }
            delay(6_000)
            assertTrue(File(first.localPath).length() > 44)
            listOf(3, 10, 20).forEach { minutes ->
                RecordingController.mark(app, minutes)
                delay(400)
            }
            val markers = app.database.conversationDao().getMarkers().takeLast(3)
            assertEquals(listOf(180_000L, 600_000L, 1_200_000L), markers.map { it.windowBeforeMillis })
            assertTrue(markers.all { it.windowAfterMillis == 0L })
            val inference = async {
                runCatching { client.transcribe(fixture, listOf(DetectedSpeechWindow(0, 30_000)), "zh") }
            }
            val manager = app.getSystemService(ActivityManager::class.java)
            val modelProcess = withTimeout(15_000) {
                var match: ActivityManager.RunningAppProcessInfo? = null
                while (match == null) {
                    match = manager.runningAppProcesses.firstOrNull { it.processName == "${app.packageName}:inference" }
                    if (match == null) delay(100)
                }
                match
            }
            assertNotEquals(Process.myPid(), modelProcess.pid)
            delay(1_000)
            assertFalse("故障注入前推理应仍在进行", inference.isCompleted)
            Process.killProcess(modelProcess.pid)
            assertTrue(withTimeout(15_000) { inference.await() }.isFailure)
            Log.i("RecordingReliabilityTest", "模型退出已返回失败，检查录音继续写入")
            val sizeAfterCrash = File(first.localPath).length()
            delay(6_000)
            assertTrue(File(first.localPath).length() > sizeAfterCrash)
            assertEquals(first.id, dao.observeActiveChunk().first()!!.id)
            Log.i("RecordingReliabilityTest", "模型退出后录音文件继续增长，等待默认五分钟切片")
            // 真实等待默认的五分钟轮换，不能用缩短配置冒充生产参数验证。
            val second = withTimeout(320_000) { dao.observeActiveChunk().first { it != null && it.id != first.id }!! }
            assertTrue(dao.getChunk(first.id)!!.endedAtMillis != null)
            assertTrue(File(first.localPath).length() >= 9_500_000L)
            delay(7_000)
            assertTrue(File(second.localPath).length() > 44)
            scenario.onActivity { RecordingController.stop(it) }
            withTimeout(15_000) { dao.observeActiveChunk().first { it == null } }
            val finished = dao.getChunk(second.id)!!
            assertTrue(finished.endedAtMillis != null)
            assertEquals(finished.byteSize, File(second.localPath).length())
            Log.i("RecordingReliabilityTest", "默认五分钟切片及停止落盘验收通过")
        } finally {
            RecordingController.stop(app)
            fixture.delete()
            scenario.close()
        }
    }
}
