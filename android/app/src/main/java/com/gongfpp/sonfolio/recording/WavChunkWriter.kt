package com.gongfpp.sonfolio.recording

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class WavChunkWriter(
    val file: File,
    private val sampleRateHz: Int,
    private val channelCount: Int,
    private val bitsPerSample: Int = 16,
) : Closeable {
    private val output: RandomAccessFile
    private var dataByteCount = 0L
    private var finished = false

    init {
        file.parentFile?.mkdirs()
        output = RandomAccessFile(file, "rw")
        output.setLength(0)
        output.write(createHeader(0, sampleRateHz, channelCount, bitsPerSample))
    }

    fun write(buffer: ByteArray, byteCount: Int) {
        check(!finished) { "Cannot write to a finished WAV chunk" }
        require(byteCount in 0..buffer.size)
        output.write(buffer, 0, byteCount)
        dataByteCount += byteCount
    }

    fun checkpoint(): Long {
        val position = output.filePointer
        output.seek(0)
        output.write(createHeader(dataByteCount, sampleRateHz, channelCount, bitsPerSample))
        output.seek(position)
        output.fd.sync()
        return WAV_HEADER_BYTES + dataByteCount
    }

    fun finish(): Long {
        if (!finished) {
            output.seek(0)
            output.write(createHeader(dataByteCount, sampleRateHz, channelCount, bitsPerSample))
            output.fd.sync()
            output.close()
            finished = true
        }
        return WAV_HEADER_BYTES + dataByteCount
    }

    override fun close() {
        finish()
    }

    companion object {
        const val WAV_HEADER_BYTES = 44L

        fun repairHeader(
            file: File,
            sampleRateHz: Int,
            channelCount: Int,
            bitsPerSample: Int = 16,
        ): Long {
            if (!file.exists() || file.length() < WAV_HEADER_BYTES) return 0

            val blockAlign = channelCount * bitsPerSample / 8
            val dataLength = (file.length() - WAV_HEADER_BYTES) / blockAlign * blockAlign
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(WAV_HEADER_BYTES + dataLength)
                output.seek(0)
                output.write(createHeader(dataLength, sampleRateHz, channelCount, bitsPerSample))
                output.fd.sync()
            }
            return file.length()
        }

        fun durationMillis(byteSize: Long, sampleRateHz: Int, channelCount: Int): Long =
            (byteSize - WAV_HEADER_BYTES).coerceAtLeast(0L) * 1_000L / (sampleRateHz * channelCount * 2L)

        private fun createHeader(
            dataLength: Long,
            sampleRateHz: Int,
            channelCount: Int,
            bitsPerSample: Int,
        ): ByteArray {
            require(dataLength <= UInt.MAX_VALUE.toLong())
            val byteRate = sampleRateHz * channelCount * bitsPerSample / 8
            val blockAlign = channelCount * bitsPerSample / 8

            return ByteBuffer.allocate(WAV_HEADER_BYTES.toInt())
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray(Charsets.US_ASCII))
                .putInt((36L + dataLength).toInt())
                .put("WAVE".toByteArray(Charsets.US_ASCII))
                .put("fmt ".toByteArray(Charsets.US_ASCII))
                .putInt(16)
                .putShort(1.toShort())
                .putShort(channelCount.toShort())
                .putInt(sampleRateHz)
                .putInt(byteRate)
                .putShort(blockAlign.toShort())
                .putShort(bitsPerSample.toShort())
                .put("data".toByteArray(Charsets.US_ASCII))
                .putInt(dataLength.toInt())
                .array()
        }
    }
}
