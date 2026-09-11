package com.gongfpp.sonfolio.processing

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

data class DetectedSpeechWindow(
    val startOffsetMillis: Long,
    val endOffsetMillis: Long,
) {
    val durationMillis: Long
        get() = endOffsetMillis - startOffsetMillis
}

class SileroVadProcessor(
    private val context: Context,
) {
    fun detect(file: File): List<DetectedSpeechWindow> {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = MODEL_ASSET,
                threshold = 0.5F,
                minSilenceDuration = 0.35F,
                minSpeechDuration = 0.25F,
                windowSize = FRAME_SAMPLES,
                maxSpeechDuration = 15.0F,
            ),
            sampleRate = SAMPLE_RATE_HZ,
            numThreads = 1,
            provider = "cpu",
        )
        val vad = Vad(context.assets, config)
        return try {
            WavPcmReader.forEachFrame(
                file = file,
                expectedSampleRateHz = SAMPLE_RATE_HZ,
                frameSamples = FRAME_SAMPLES,
            ) { samples -> vad.acceptWaveform(samples) }
            vad.flush()
            mergeSegments(drainSegments(vad))
        } finally {
            vad.release()
        }
    }

    private fun drainSegments(vad: Vad): List<DetectedSpeechWindow> {
        val segments = mutableListOf<DetectedSpeechWindow>()
        while (!vad.empty()) {
            val segment = vad.front()
            segments += segment.toWindow()
            vad.pop()
        }
        return segments
    }

    private fun SpeechSegment.toWindow(): DetectedSpeechWindow {
        val startMillis = start * 1_000L / SAMPLE_RATE_HZ
        val durationMillis = samples.size * 1_000L / SAMPLE_RATE_HZ
        return DetectedSpeechWindow(startMillis, startMillis + durationMillis)
    }

    private fun mergeSegments(segments: List<DetectedSpeechWindow>): List<DetectedSpeechWindow> {
        if (segments.isEmpty()) return emptyList()
        val merged = mutableListOf<DetectedSpeechWindow>()
        segments.sortedBy { it.startOffsetMillis }.forEach { current ->
            val previous = merged.lastOrNull()
            if (previous == null || current.startOffsetMillis > previous.endOffsetMillis + MERGE_GAP_MILLIS || current.endOffsetMillis - previous.startOffsetMillis > 30_000L) {
                merged += current
            } else {
                merged[merged.lastIndex] = previous.copy(
                    endOffsetMillis = maxOf(previous.endOffsetMillis, current.endOffsetMillis),
                )
            }
        }
        return merged
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SAMPLES = 512
        const val MODEL_ASSET = "silero_vad.onnx"
        const val MERGE_GAP_MILLIS = 800L
    }
}
