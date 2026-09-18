package com.gongfpp.sonfolio.models

import java.io.File
import java.security.MessageDigest

/** 模型仓库中的一个文件；Qwen3-ASR 这类模型由多个文件（含 tokenizer 目录）组成。
 * urls 按优先级排列：默认第一个是大陆可访问的镜像，最后回退到上游 Hugging Face。
 * 无论从哪个源下载，都必须通过 sha256 校验后才会启用。 */
data class ModelFile(val relativePath: String, val bytes: Long, val sha256: String, val urls: List<String>)

enum class ModelKind(val label: String) { SPEECH("语音识别"), SUMMARY("文字总结") }

/** 模型权重附带的独立许可；非空时下载前需要用户显式接受。 */
data class ModelLicense(val notice: String, val url: String)

data class ModelArtifact(
    val id: String,
    val label: String,
    val kind: ModelKind,
    val files: List<ModelFile>,
    val license: ModelLicense? = null,
) {
    val bytes: Long get() = files.sumOf { it.bytes }
}

object ModelCatalog {
    // Qwen3-ASR ONNX 导出的固定版本，避免上游改动导致校验失败。
    private const val QWEN3_REVISION = "68818b2313fe77bd06f6a7c5068ff3ef59d02b8a"
    private const val QWEN3_PREFIX = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"

    /**
     * 同一份文件的可访问源，镜像优先。默认走 hf-mirror（大陆可访问），上游只作回退；
     * 每个文件都会做 SHA-256 校验，所以从镜像下载同样可靠。
     */
    private fun sources(path: String): List<String> = listOf(
        "https://hf-mirror.com/$path",
        "https://huggingface.co/$path",
    )
    private fun qwen3(path: String) = sources("csukuangfj2/$QWEN3_PREFIX/resolve/$QWEN3_REVISION/$path")

    // FireRedASR2 的 CTC 分支导出：单个 int8 模型，比 AED 小且非自回归解码更快。
    private const val FIRE_RED_PREFIX = "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25"
    private const val FIRE_RED_REVISION = "423be1acfdc5d8aaa5a485fb6263fe4ea8570b06"
    private fun fireRed(path: String) = sources("csukuangfj2/$FIRE_RED_PREFIX/resolve/$FIRE_RED_REVISION/$path")

    val senseVoice = ModelArtifact(
        id = "sensevoice",
        label = "SenseVoice Small（中英日韩粤 · 239 MB）",
        kind = ModelKind.SPEECH,
        files = listOf(
            ModelFile("speech/sense-voice-model.int8.onnx", 239233841,
                "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
                sources("csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/model.int8.onnx")),
        ),
        license = ModelLicense(
            "SenseVoice 权重受独立的 FunASR 模型许可约束，含使用限制，不属于客户端的 GPL-3.0 许可。",
            "https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE",
        ),
    )

    val qwen3Asr = ModelArtifact(
        id = "qwen3-asr",
        label = "Qwen3-ASR 0.6B（中英混说与方言 · 987 MB）",
        kind = ModelKind.SPEECH,
        files = listOf(
            ModelFile("speech/qwen3-asr/conv_frontend.onnx", 44148281,
                "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e",
                qwen3("conv_frontend.onnx")),
            ModelFile("speech/qwen3-asr/encoder.int8.onnx", 182491662,
                "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9",
                qwen3("encoder.int8.onnx")),
            ModelFile("speech/qwen3-asr/decoder.int8.onnx", 755914231,
                "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771",
                qwen3("decoder.int8.onnx")),
            ModelFile("speech/qwen3-asr/tokenizer/merges.txt", 1671853,
                "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5",
                qwen3("tokenizer/merges.txt")),
            ModelFile("speech/qwen3-asr/tokenizer/vocab.json", 2776833,
                "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910",
                qwen3("tokenizer/vocab.json")),
            ModelFile("speech/qwen3-asr/tokenizer/tokenizer_config.json", 12487,
                "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c",
                qwen3("tokenizer/tokenizer_config.json")),
        ),
        license = ModelLicense(
            "Qwen3-ASR 权重来自 Qwen/Qwen3-ASR-0.6B，采用 Apache-2.0 许可；ONNX 导出与托管来自 sherpa-onnx 社区。",
            "https://huggingface.co/Qwen/Qwen3-ASR-0.6B",
        ),
    )

    val fireRedAsrCtc = ModelArtifact(
        id = "fire-red-asr-ctc",
        label = "FireRedASR2-CTC（中英与方言 · 776 MB）",
        kind = ModelKind.SPEECH,
        files = listOf(
            ModelFile("speech/fire-red-asr2-ctc/model.int8.onnx", 775861420,
                "ca3dbabd82170110cc0b343c2890866d449984bc9cd92b9a18371ff80a81bb99",
                fireRed("model.int8.onnx")),
            ModelFile("speech/fire-red-asr2-ctc/tokens.txt", 79172,
                "1bc613de2112d257e61a349c3e72d1b1a9cf19c33d3ca954197ad2171e5ea07b",
                fireRed("tokens.txt")),
        ),
    )

    val summary = ModelArtifact(
        id = "qwen-summary",
        label = "手机总结模型（离线）",
        kind = ModelKind.SUMMARY,
        files = listOf(
            ModelFile("summary/qwen2.5-0.5b-instruct-q4_k_m.gguf", 491400032,
                "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
                sources("Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf")),
        ),
    )

    /** 本地语音识别可选引擎，默认第一个；顺序即设置页展示顺序。 */
    val speechModels = listOf(senseVoice, qwen3Asr, fireRedAsrCtc)
    val all = speechModels + summary

    fun byId(id: String): ModelArtifact? = all.firstOrNull { it.id == id }

    fun file(filesDir: File, file: ModelFile) = File(filesDir, "models/${file.relativePath}")

    /** 单文件模型的路径；多文件模型返回主文件（files 第一个）。 */
    fun file(filesDir: File, model: ModelArtifact) = file(filesDir, model.files.first())

    /** 模型完整可用所需的第一个缺失或损坏文件；全部就绪时返回 null。 */
    fun missingFile(filesDir: File, model: ModelArtifact): ModelFile? =
        model.files.firstOrNull { !verify(file(filesDir, it), it) }

    fun installed(filesDir: File, model: ModelArtifact): Boolean = missingFile(filesDir, model) == null

    /**
     * 轻量可用性检查：只比对文件是否存在且大小完全一致，不计算 SHA-256。
     * 供 UI 展示使用——在组合期对 987 MB 模型做哈希会直接把主线程卡住。
     * 真正启用前仍由 Worker / 推理前用 [installed] 完整校验。
     */
    fun available(filesDir: File, model: ModelArtifact): Boolean =
        model.files.all { file(filesDir, it).let { target -> target.isFile && target.length() == it.bytes } }

    fun verify(file: File, expected: ModelFile): Boolean {
        if (!file.isFile || file.length() != expected.bytes) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) } == expected.sha256
    }
}
