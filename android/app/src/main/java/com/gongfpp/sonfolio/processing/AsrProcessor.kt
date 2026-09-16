package com.gongfpp.sonfolio.processing

import android.content.Context
import java.io.File

/**
 * 本地语音识别引擎开关。每个引擎对应 ModelCatalog 中的一个语音模型；新增引擎时在此登记，
 * 下载与推理都按 artifactId 查找，不改动画板逻辑。
 */
enum class LocalAsrEngine(
    val artifactId: String,
    val displayName: String,
    /** 是否支持 sherpa-onnx 热词（当前 Qwen3-ASR 的 LLM 提示支持，SenseVoice 不支持）。 */
    val supportsHotwords: Boolean,
) {
    SENSE_VOICE("sensevoice", "SenseVoice", false),
    QWEN3_ASR("qwen3-asr", "Qwen3-ASR", true);

    companion object {
        val DEFAULT = SENSE_VOICE
        fun fromName(value: String?): LocalAsrEngine =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/** 本地 ASR 处理器；每个 worker 进程内只创建一次，处理该切片的全部语音窗口。 */
interface AsrProcessor : AutoCloseable {
    fun transcribe(file: File, startOffsetMillis: Long, endOffsetMillis: Long): String
}

internal fun createAsrProcessor(
    context: Context,
    engine: LocalAsrEngine,
    language: String,
    hotwords: String,
): AsrProcessor = when (engine) {
    LocalAsrEngine.SENSE_VOICE -> SenseVoiceAsrProcessor(context, language)
    LocalAsrEngine.QWEN3_ASR -> Qwen3AsrProcessor(context, hotwords)
}
