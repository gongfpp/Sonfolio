package com.gongfpp.sonfolio.processing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WavPcmReader {
    private data class WavMetadata(
        val dataOffset: Long,
        val dataBytes: Long,
        val sampleRateHz: Int,
        val channels: Int,
        val bitsPerSample: Int,
    )

    fun forEachFrame(
        file: File,
        expectedSampleRateHz: Int,
        frameSamples: Int,
        onFrame: (FloatArray) -> Unit,
    ) {
        require(frameSamples > 0)
        RandomAccessFile(file, "r").use { input ->
            val wav = readMetadata(input, expectedSampleRateHz)

            val frameBytes = frameSamples * 2
            val buffer = ByteArray(frameBytes)
            var remainingBytes = wav.dataBytes
            while (remainingBytes > 0) {
                val bytesToRead = minOf(frameBytes.toLong(), remainingBytes).toInt()
                input.readFully(buffer, 0, bytesToRead)
                val samples = FloatArray(frameSamples)
                val shortBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                for (index in 0 until bytesToRead / 2) {
                    samples[index] = shortBuffer.short.toInt() / 32768.0f
                }
                onFrame(samples)
                remainingBytes -= bytesToRead
            }
        }
    }

    /**
     * Reads a bounded PCM window without loading the complete day-long recording into memory.
     * The VAD windows are intentionally short (at most 15 seconds), so one segment is safe for
     * the on-device ASR model even on lower-memory phones.
     */
    fun readWindow(
        file: File,
        expectedSampleRateHz: Int,
        startOffsetMillis: Long,
        endOffsetMillis: Long,
    ): FloatArray {
        require(startOffsetMillis >= 0) { "音频起点不能为负数" }
        require(endOffsetMillis > startOffsetMillis) { "音频窗口必须有正时长" }
        RandomAccessFile(file, "r").use { input ->
            val wav = readMetadata(input, expectedSampleRateHz)
            val firstSample = (startOffsetMillis * expectedSampleRateHz / 1_000L)
            val requestedSamples = ((endOffsetMillis - startOffsetMillis) * expectedSampleRateHz / 1_000L)
                .coerceAtLeast(1L)
            val availableSamples = wav.dataBytes / 2L
            if (firstSample >= availableSamples) return FloatArray(0)
            val sampleCount = minOf(requestedSamples, availableSamples - firstSample).toInt()
            input.seek(wav.dataOffset + firstSample * 2L)
            val bytes = ByteArray(sampleCount * 2)
            input.readFully(bytes)
            val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(sampleCount) { shortBuffer.short.toInt() / 32768.0f }
        }
    }

    /**
     * 按 chunk 扫描解析，而不是假定固定 44 字节头。ffmpeg/afconvert 等工具会在 fmt 与 data
     * 之间插入 LIST/FLLR chunk；此时 data 并不在偏移 44，固定偏移会把文件误读成近乎空音频。
     * 本应用录音由 WavChunkWriter 生成标准布局，两种布局都必须正确处理。
     */
    /**
     * data chunk 的真实起点与长度。压缩等按字节顺序读取的调用方必须用它，
     * 不能假定数据从第 44 字节开始。
     */
    fun readDataLayout(file: File, expectedSampleRateHz: Int): Pair<Long, Long> {
        RandomAccessFile(file, "r").use { input ->
            val metadata = readMetadata(input, expectedSampleRateHz)
            return metadata.dataOffset to metadata.dataBytes
        }
    }

    private fun readMetadata(
        input: RandomAccessFile,
        expectedSampleRateHz: Int,
    ): WavMetadata {
        require(input.length() >= 12L) { "WAV 文件头不完整" }
        val riff = ByteArray(12)
        input.seek(0)
        input.readFully(riff)
        require(riff.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) { "不是 RIFF WAV 文件" }
        require(riff.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) { "不是 WAVE 音频" }

        var position = 12L
        var fmtBytes: ByteArray? = null
        var dataOffset = -1L
        var dataBytes = -1L
        val chunkHeader = ByteArray(8)
        while (position + 8L <= input.length()) {
            input.seek(position)
            input.readFully(chunkHeader)
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = (chunkHeader[4].toLong() and 0xFF) or
                ((chunkHeader[5].toLong() and 0xFF) shl 8) or
                ((chunkHeader[6].toLong() and 0xFF) shl 16) or
                ((chunkHeader[7].toLong() and 0xFF) shl 24)
            val payload = position + 8L
            when (id) {
                "fmt " -> {
                    val count = minOf(size, 16L).toInt()
                    fmtBytes = ByteArray(count).also { input.seek(payload); input.readFully(it) }
                }
                "data" -> {
                    dataOffset = payload
                    // 流式 WAV 可能写 0 或 0xFFFFFFFF，按实际文件长度兜底。
                    dataBytes = if (size > 0L && payload + size <= input.length()) size else input.length() - payload
                    break
                }
            }
            position = payload + size + (size and 1L)
        }

        val metadata = ByteBuffer.wrap(fmtBytes ?: error("WAV 缺少 fmt chunk")).order(ByteOrder.LITTLE_ENDIAN)
        require(metadata.capacity() >= 16) { "WAV fmt chunk 不完整" }
        require(metadata.getShort(0).toInt() == 1) { "只支持 PCM WAV" }
        val channels = metadata.getShort(2).toInt()
        val sampleRate = metadata.getInt(4)
        val bitsPerSample = metadata.getShort(14).toInt()
        require(channels == 1) { "只支持单声道 WAV" }
        require(sampleRate == expectedSampleRateHz) {
            "采样率 $sampleRate 与预期 $expectedSampleRateHz 不一致"
        }
        require(bitsPerSample == 16) { "只支持 PCM 16-bit WAV" }
        require(dataOffset >= 0L && dataBytes >= 0L) { "WAV 缺少 data chunk" }
        require(dataBytes <= input.length() - dataOffset) { "WAV data 区域超出文件长度" }
        return WavMetadata(
            dataOffset = dataOffset,
            dataBytes = dataBytes,
            sampleRateHz = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
        )
    }
}
