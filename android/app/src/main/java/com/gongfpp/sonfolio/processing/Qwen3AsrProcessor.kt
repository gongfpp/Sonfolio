package com.gongfpp.sonfolio.processing

import android.content.Context
import com.gongfpp.sonfolio.models.ModelCatalog
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * Qwen3-ASR 0.6B int8（conv-frontend + encoder + decoder + tokenizer）。相比 SenseVoice，
 * 中英混说与方言更强，但模型约 987 MB，CPU 自回归解码更慢；因此和 SenseVoice 一样按需下载、
 * 由用户选择，默认不启用。hotwords 由用户确认过的个人词汇拼接，仅在本地 Qwen3 引擎生效。
 */
class Qwen3AsrProcessor(
    context: Context,
    hotwords: String = "",
    paths: AsrModelPaths? = null,
) : AsrProcessor {
    private val catalogRoot by lazy {
        requireNotNull(ModelCatalog.byId(LocalAsrEngine.QWEN3_ASR.artifactId)) { "未登记 Qwen3-ASR 模型" }
            .let { artifact ->
                require(ModelCatalog.installed(context.filesDir, artifact)) { "请在设置的模型下载中下载 Qwen3-ASR 模型" }
                ModelCatalog.file(context.filesDir, artifact).parentFile!!
            }
    }
    private val convFrontend = paths?.convFrontend?.also { require(it.isFile) { "自定义 Qwen3-ASR conv_frontend.onnx 缺失" } }
        ?: File(catalogRoot, CONV_FRONTEND)
    private val encoder = paths?.encoder?.also { require(it.isFile) { "自定义 Qwen3-ASR encoder 缺失" } }
        ?: File(catalogRoot, ENCODER)
    private val decoder = paths?.decoder?.also { require(it.isFile) { "自定义 Qwen3-ASR decoder 缺失" } }
        ?: File(catalogRoot, DECODER)
    private val tokenizer = paths?.tokenizer?.also { require(it.isDirectory) { "自定义 Qwen3-ASR tokenizer 目录缺失" } }
        ?: File(catalogRoot, TOKENIZER_DIR)

    private val recognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SileroVadProcessor.SAMPLE_RATE_HZ,
                featureDim = 80,
            ),
            modelConfig = OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = convFrontend.absolutePath,
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    tokenizer = tokenizer.absolutePath,
                    // 官方示例用 512；默认 128 对较长窗口可能截断。
                    maxTotalLen = 512,
                    maxNewTokens = 512,
                    hotwords = hotwords.trim().take(MAX_HOTWORDS_CHARS),
                ),
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
        const val MODEL_VERSION = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
        private const val CONV_FRONTEND = "conv_frontend.onnx"
        private const val ENCODER = "encoder.int8.onnx"
        private const val DECODER = "decoder.int8.onnx"
        private const val TOKENIZER_DIR = "tokenizer"
        private const val MAX_HOTWORDS_CHARS = 4_000
        private const val MIN_SAMPLES = 1_600 // 100 ms at 16 kHz
    }
}
