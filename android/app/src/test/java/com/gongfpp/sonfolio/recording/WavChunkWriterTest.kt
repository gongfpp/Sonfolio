package com.gongfpp.sonfolio.recording

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavChunkWriterTest {
    @Test
    fun checkpointProducesPlayableHeaderWithoutClosingRecording() {
        val file = File.createTempFile("sonfolio-checkpoint-", ".wav")
        try {
            WavChunkWriter(file, 16_000, 1).use { writer ->
                writer.write(ByteArray(32_000), 32_000)
                assertEquals(32_044L, writer.checkpoint())
                assertEquals(32_000, littleEndianInt(file.readBytes(), 40))
                writer.write(ByteArray(32_000), 32_000)
            }
            assertEquals(2_000L, WavChunkWriter.durationMillis(file.length(), 16_000, 1))
        } finally { file.delete() }
    }

    @Test
    fun writesPcmDataAndFinalizesWavHeader() {
        val file = File.createTempFile("sonfolio-wav-", ".wav")
        try {
            WavChunkWriter(file, sampleRateHz = 16_000, channelCount = 1).use { writer ->
                writer.write(byteArrayOf(1, 2, 3, 4), 4)
            }

            val bytes = file.readBytes()
            assertEquals(48, bytes.size)
            assertArrayEquals("RIFF".toByteArray(), bytes.copyOfRange(0, 4))
            assertArrayEquals("WAVE".toByteArray(), bytes.copyOfRange(8, 12))
            assertArrayEquals("data".toByteArray(), bytes.copyOfRange(36, 40))
            assertEquals(40, littleEndianInt(bytes, 4))
            assertEquals(16_000, littleEndianInt(bytes, 24))
            assertEquals(32_000, littleEndianInt(bytes, 28))
            assertEquals(4, littleEndianInt(bytes, 40))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes.copyOfRange(44, 48))
        } finally {
            file.delete()
        }
    }

    @Test
    fun repairsAnInterruptedChunkHeaderFromFileLength() {
        val file = File.createTempFile("sonfolio-repair-", ".wav")
        try {
            file.writeBytes(ByteArray(50) { index -> index.toByte() })
            WavChunkWriter.repairHeader(file, sampleRateHz = 16_000, channelCount = 1)

            val bytes = file.readBytes()
            assertArrayEquals("RIFF".toByteArray(), bytes.copyOfRange(0, 4))
            assertEquals(42, littleEndianInt(bytes, 4))
            assertEquals(6, littleEndianInt(bytes, 40))
        } finally {
            file.delete()
        }
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
}
