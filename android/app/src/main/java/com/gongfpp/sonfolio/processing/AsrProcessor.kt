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
    /** 面向普通用户的粗略评分（1–5 星），来自 docs/evaluation 的真机 CER 与耗时对比。 */
    val speedStars: Int,
    val accuracyStars: Int,
    val recommended: Boolean,
    val blurb: String,
) {
    SENSE_VOICE("sensevoice", "SenseVoice", false, 5, 3, true, "体积最小、速度最快；中英日韩粤，日常够用"),
    FIRE_RED_ASR_CTC("fire-red-asr-ctc", "FireRedASR2-CTC", false, 4, 4, false, "中文（含噪声、歌曲）更稳，解码较快；英文偏弱"),
    QWEN3_ASR("qwen3-asr", "Qwen3-ASR 0.6B", true, 2, 4, false, "中英混说与方言更强，支持个人词汇热词；更慢、更占内存");

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
    LocalAsrEngine.FIRE_RED_ASR_CTC -> FireRedAsrCtcProcessor(context)
}
