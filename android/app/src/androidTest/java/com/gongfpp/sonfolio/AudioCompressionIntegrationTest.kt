package com.gongfpp.sonfolio

import android.media.MediaPlayer
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.SonfolioDatabase
import com.gongfpp.sonfolio.processing.AudioTranscoder
import com.gongfpp.sonfolio.recording.WavChunkWriter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** P1 存储模型：转写完成后异步压缩原音；压缩音可播放，清理与保留不影响文字。 */
@RunWith(AndroidJUnit4::class)
class AudioCompressionIntegrationTest {
    @Test fun compressAndRetireWavKeepsPlayableCompressedAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val recordings = File(context.filesDir, "recordings").apply { mkdirs() }
        try {
            val wav = File(recordings, "compress-test.wav")
            WavChunkWriter(wav, 16_000, 1).use { writer ->
                // 约 1 秒的 440Hz 正弦波，保证编码器有真实内容可压缩。
                val samples = ShortArray(16_000) { index -> (Math.sin(2.0 * Math.PI * 440 * index / 16_000) * 8_000).toInt().toShort() }
                writer.write(samples.toByteArray(), samples.size * 2)
            }
            assertTrue(wav.length() > 20_000)
            val compressed = File(recordings, "compress-test.m4a")
            val bytes = AudioTranscoder.compress(wav, compressed, 16_000, 1)
            assertTrue("压缩产物应为有效 m4a：$bytes", bytes in 1..wav.length())

            // MediaPlayer 能打开压缩音并取得真实时长。
            val player = MediaPlayer()
            player.setDataSource(compressed.absolutePath)
            player.prepare()
            assertTrue(player.duration in 500..2_000)
            player.release()
        } finally {
            database.close()
            recordings.deleteRecursively()
        }
    }
}

private fun ShortArray.toByteArray(): ByteArray = ByteArray(size * 2).also { out ->
    forEachIndexed { index, value ->
        out[index * 2] = (value.toInt() and 0xFF).toByte()
        out[index * 2 + 1] = (value.toInt() shr 8).toByte()
    }
}
