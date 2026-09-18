package com.gongfpp.sonfolio.recording

import kotlin.math.sqrt

data class CaptureHealth(
    val serviceActive: Boolean = false,
    val lastBufferAtMillis: Long? = null,
    val clientSilenced: Boolean? = null,
    val inputDevice: String? = null,
    val deviceChanged: Boolean = false,
    val levels: List<Float> = List(12) { 0f },
    val failure: String? = null,
    val storageLow: Boolean = false,
) {
    fun message(now: Long): String = when {
        failure != null -> failure
        !serviceActive -> "未启动采集"
        clientSilenced == true -> "系统已将麦克风输入静音 · 这段声音可能缺失"
        lastBufferAtMillis == null -> "服务已启动 · 等待音频写入"
        now - lastBufferAtMillis > 2_000 -> "音频写入没有更新 · 正在检查采集状态"
        clientSilenced == null -> "音频正在写入 · 系统静音状态尚未确认"
        else -> "音频持续写入 · ${inputDevice ?: "麦克风"}${if (deviceChanged) "（输入设备已切换）" else ""}${if (storageLow) " · 空间不足 512 MB，请及时导出" else ""}"
    }
}

internal const val RECORDING_SPACE_RESERVE = 64L * 1024 * 1024
internal const val RECORDING_SPACE_WARNING = 512L * 1024 * 1024
internal fun requireRecordingSpace(available: Long) {
    check(available >= RECORDING_SPACE_RESERVE) { "可用空间不足 64 MB，已停止录音以保留已录内容；不会自动删除录音" }
}

internal fun pcmLevel(bytes: ByteArray, count: Int): Float {
    if (count < 2) return 0f
    var energy = 0.0
    for (i in 0 until minOf(count, bytes.size) - 1 step 2) {
        val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
        energy += sample * sample
    }
    return (sqrt(energy / (minOf(count, bytes.size) / 2)) * 4).toFloat().coerceIn(0f, 1f)
}
