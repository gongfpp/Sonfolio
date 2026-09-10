package com.gongfpp.sonfolio.processing

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WavPcmReader {
    private const val HEADER_BYTES = 44L

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

    private fun readMetadata(
        input: RandomAccessFile,
        expectedSampleRateHz: Int,
    ): WavMetadata {
        require(input.length() >= HEADER_BYTES) { "WAV 文件头不完整" }
        val header = ByteArray(HEADER_BYTES.toInt())
        input.seek(0)
        input.readFully(header)
        val metadata = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) {
            "不是 RIFF WAV 文件"
        }
        require(header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) {
            "不是 WAVE 音频"
        }
        metadata.position(20)
        require(metadata.short.toInt() == 1) { "只支持 PCM WAV" }
        val channels = metadata.short.toInt()
        val sampleRate = metadata.int
        require(channels == 1) { "只支持单声道 WAV" }
        require(sampleRate == expectedSampleRateHz) {
            "采样率 $sampleRate 与预期 $expectedSampleRateHz 不一致"
        }
        val bitsPerSample = metadata.getShort(34).toInt()
        require(bitsPerSample == 16) { "只支持 PCM 16-bit WAV" }
        val dataBytes = metadata.getInt(40).toLong() and 0xFFFF_FFFFL
        require(dataBytes <= input.length() - HEADER_BYTES) { "WAV data 区域超出文件长度" }
        return WavMetadata(
            dataOffset = HEADER_BYTES,
            dataBytes = dataBytes,
            sampleRateHz = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
        )
    }
}
