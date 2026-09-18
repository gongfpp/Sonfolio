package com.gongfpp.sonfolio.processing

import android.content.Context
import com.gongfpp.sonfolio.models.ModelCatalog
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * FireRedASR2-CTC int8 单模型（约 776 MB + tokens）。相比 SenseVoice，中文公开基准 CER 更低、
 * 也覆盖部分方言；CTC 非自回归解码比 Qwen3-ASR 的 LLM 解码快，适合 CPU。作为并列可选引擎，
 * 默认不启用。
 */
class FireRedAsrCtcProcessor(
    context: Context,
) : AsrProcessor {
    private val root = requireNotNull(ModelCatalog.byId(LocalAsrEngine.FIRE_RED_ASR_CTC.artifactId)) { "未登记 FireRedASR2-CTC 模型" }
        .let { artifact ->
            require(ModelCatalog.installed(context.filesDir, artifact)) { "请在设置的模型下载中下载 FireRedASR2-CTC 模型" }
            ModelCatalog.file(context.filesDir, artifact).parentFile!!
        }

    private val recognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SileroVadProcessor.SAMPLE_RATE_HZ,
                featureDim = 80,
            ),
            modelConfig = OfflineModelConfig(
                fireRedAsrCtc = OfflineFireRedAsrCtcModelConfig(
                    model = File(root, MODEL).absolutePath,
                ),
                tokens = File(root, TOKENS).absolutePath,
                numThreads = minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1),
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun transcribe(
        file: File,
        startOffsetMillis: Long,
        endOffsetMillis: Long,
    ): String {
        val samples = WavPcmReader.readWindow(
            file = file,
            expectedSampleRateHz = SileroVadProcessor.SAMPLE_RATE_HZ,
            startOffsetMillis = startOffsetMillis,
            endOffsetMillis = endOffsetMillis,
        )
        if (samples.size < MIN_SAMPLES) return ""

        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SileroVadProcessor.SAMPLE_RATE_HZ)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer.release()
    }

    companion object {
        const val MODEL_VERSION = "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25"
        private const val MODEL = "model.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private const val MIN_SAMPLES = 1_600 // 100 ms at 16 kHz
    }
}
