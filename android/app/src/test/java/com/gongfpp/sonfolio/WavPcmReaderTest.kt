package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.processing.WavPcmReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** WavPcmReader 必须同时支持应用录音的标准 44 字节头，以及带 LIST/FLLR 等额外 chunk 的 WAV。 */
class WavPcmReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun writeWav(name: String, samples: ShortArray, listChunk: Boolean): File {
        val file = temporary.newFile(name)
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
        }.array()
        val list = if (listChunk) ByteArray(26) { (it + 1).toByte() } else ByteArray(0)
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it) }
        }.array()
        val total = 12 + 8 + fmt.size + (if (listChunk) 8 + list.size else 0) + 8 + data.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()); out.putInt(total - 8); out.put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()); out.putInt(fmt.size); out.put(fmt)
        if (listChunk) { out.put("LIST".toByteArray()); out.putInt(list.size); out.put(list) }
        out.put("data".toByteArray()); out.putInt(data.size); out.put(data)
        file.writeBytes(out.array())
        return file
    }

    private val samples = ShortArray(1_600) { (it % 100).toShort() }

    @Test fun readsCanonicalHeader() {
        val file = writeWav("canonical.wav", samples, listChunk = false)
        val window = WavPcmReader.readWindow(file, 16_000, 0, 100)
        assertEquals(samples.size, window.size)
    }

    @Test fun readsWavWithListChunkBeforeData() {
        val file = writeWav("with-list.wav", samples, listChunk = true)
        val window = WavPcmReader.readWindow(file, 16_000, 0, 100)
        // 固定 44 字节头的旧实现会把 data 前的 LIST 长度当成数据长度，只剩十来个采样。
        assertEquals(samples.size, window.size)
    }

    @Test fun readsFramesFromWavWithListChunk() {
        val file = writeWav("frames.wav", samples, listChunk = true)
        var frames = 0
        var received = 0
        WavPcmReader.forEachFrame(file, 16_000, 512) { frame -> frames++; received += frame.size }
        assertEquals(4, frames)
        assertEquals(2_048, received)
    }
}
