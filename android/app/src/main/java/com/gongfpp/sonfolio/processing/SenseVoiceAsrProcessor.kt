package com.gongfpp.sonfolio.processing

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import com.gongfpp.sonfolio.models.ModelCatalog

/**
 * A process-local SenseVoice recognizer. The model is deliberately created once per worker and
 * reused for all speech windows in that chunk; creating one native model per segment would waste
 * hundreds of megabytes and makes OOM much more likely on a phone.
 */
class SenseVoiceAsrProcessor(
    context: Context,
    preferredLanguage: String = "zh",
) : AutoCloseable {
    private val model = ModelCatalog.file(context.filesDir, ModelCatalog.speech).also {
        require(it.isFile && it.length() == ModelCatalog.speech.bytes) { "请在设置的模型下载中下载语音识别模型" }
    }
    private val tokens = File(model.parentFile, TOKENS_ASSET).also { file ->
        // Small bundled vocabulary is replaced atomically; a killed copy cannot poison later runs.
        val temporary = File(file.parentFile, "$TOKENS_ASSET.tmp")
        context.assets.open(TOKENS_ASSET).use { input -> java.io.FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() } }
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    private val recognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = SileroVadProcessor.SAMPLE_RATE_HZ,
                featureDim = 80,
            ),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.absolutePath,
                    language = preferredLanguage.takeUnless { it == "auto" }.orEmpty(),
                    useInverseTextNormalization = true,
                ),
                tokens = tokens.absolutePath,
                // int8 SenseVoice 对线程数近乎线性加速；留 2 个核给录音、VAD 与系统。
                numThreads = minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1),
                debug = false,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    fun transcribe(
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
        const val MODEL_ASSET = "sense-voice-model.int8.onnx"
        const val TOKENS_ASSET = "sense-voice-tokens.txt"
        const val MODEL_VERSION = "sherpa-onnx-v1.13.7-sensevoice-int8"
        private const val MIN_SAMPLES = 1_600 // 100 ms at 16 kHz
    }
}
