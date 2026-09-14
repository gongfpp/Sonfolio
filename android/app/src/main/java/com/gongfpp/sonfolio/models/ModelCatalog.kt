package com.gongfpp.sonfolio.models

import java.io.File
import java.security.MessageDigest

data class ModelArtifact(val id: String, val label: String, val relativePath: String, val bytes: Long, val sha256: String, val url: String)

object ModelCatalog {
    val speech = ModelArtifact("sensevoice", "SenseVoice 中文语音识别", "speech/sense-voice-model.int8.onnx", 239233841,
        "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/model.int8.onnx")
    val summary = ModelArtifact("qwen-summary", "Qwen2.5 0.5B · GGUF 本地总结", "summary/qwen2.5-0.5b-instruct-q4_k_m.gguf", 491400032,
        "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf")
    val all = listOf(speech, summary)
    fun file(filesDir: File, model: ModelArtifact) = File(filesDir, "models/${model.relativePath}")
    fun verify(file: File, model: ModelArtifact): Boolean {
        if (!file.isFile || file.length() != model.bytes) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) } == model.sha256
    }
}
