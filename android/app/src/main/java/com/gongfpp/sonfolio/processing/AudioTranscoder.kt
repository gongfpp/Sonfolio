package com.gongfpp.sonfolio.processing

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/** 录音事实已落盘、转写已完成之后，才把 WAV 异步压缩为 AAC。不参与录音主循环。 */
internal object AudioTranscoder {
    /**
     * PCM16 单声道 WAV → AAC-LC .m4a。写入临时文件并在成功后原子改名；
     * 失败时删除半成品，不返回部分结果。
     */
    fun compress(wav: File, target: File, sampleRateHz: Int, channelCount: Int): Long {
        require(sampleRateHz > 0) { "采样率无效" }
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.delete()
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, channelCount)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            RandomAccessFile(wav, "r").use { reader ->
                // data chunk 不一定从 44 字节开始（可能夹带 LIST 等 chunk），按真实布局定位。
                val (dataOffset, dataBytes) = WavPcmReader.readDataLayout(wav, sampleRateHz)
                reader.seek(dataOffset)
                var remaining = dataBytes
                val bufferInfo = MediaCodec.BufferInfo()
                var track = -1
                var inputEnded = false
                var outputEnded = false
                var samplesFed = 0L
                while (!outputEnded) {
                    if (!inputEnded) {
                        val index = encoder.dequeueInputBuffer(10_000L)
                        if (index >= 0) {
                            val input = encoder.getInputBuffer(index)!!
                            input.order(ByteOrder.LITTLE_ENDIAN)
                            val chunk = ByteArray(minOf(input.remaining().toLong(), maxOf(remaining, 0L)).toInt())
                            var filled = 0
                            while (filled < chunk.size) {
                                val read = reader.read(chunk, filled, chunk.size - filled)
                                if (read < 0) break
                                filled += read
                            }
                            remaining -= filled
                            val timestampUs = samplesFed * 1_000_000L / sampleRateHz
                            if (filled == 0) {
                                encoder.queueInputBuffer(index, 0, 0, timestampUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                samplesFed += filled / 2 / channelCount
                                encoder.queueInputBuffer(index, 0, filled, timestampUs, 0)
                            }
                        }
                    }
                    when (val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(encoder.outputFormat)
                            muxer.start(); muxerStarted = true
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                        else -> if (outIndex >= 0) {
                            val output = encoder.getOutputBuffer(outIndex)!!
                            val isCodecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (bufferInfo.size > 0 && muxerStarted && !isCodecConfig) {
                                muxer.writeSampleData(track, output, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                        }
                    }
                }
            }
            if (!muxerStarted) error("压缩器未产生音频轨")
            muxer.stop()
            val bytes = temporary.length()
            if (bytes <= 0 || !temporary.renameTo(target)) {
                temporary.delete()
                error("压缩文件写入失败")
            }
            return bytes
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            muxer.release()
        }
    }
}
