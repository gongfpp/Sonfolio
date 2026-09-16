package com.gongfpp.sonfolio.processing

/**
 * 切片处理状态的唯一来源。数据库里仍然存字符串，但阶段编号、用户文案、是否可重试都从这里取，
 * UI、仓库和调度器不再各自维护一套映射（历史上三处文案和 ①–④ 编号互相矛盾）。
 *
 * 阶段：① 保存原音 → ② 找人声 → ③ 转写 → ④ 整理对话。
 */
object ChunkProcessing {
    const val RECORDING = "RECORDING"
    const val RECORDED = "RECORDED"
    const val RECOVERED = "RECOVERED"
    const val VAD_RUNNING = "VAD_RUNNING"
    const val VAD_READY = "VAD_READY"
    const val VAD_FAILED = "VAD_FAILED"
    const val ASR_RUNNING = "ASR_RUNNING"
    const val ASR_READY = "ASR_READY"
    const val ASR_FAILED = "ASR_FAILED"
    const val ASSEMBLY_PENDING = "ASSEMBLY_PENDING"
    const val ASSEMBLY_FAILED = "ASSEMBLY_FAILED"
    const val AUDIO_DELETED = "AUDIO_DELETED"
    const val FAILED = "FAILED"

    const val TOTAL_STAGES = 4
    const val STAGE_SAVE = 1
    const val STAGE_VOICE = 2
    const val STAGE_TRANSCRIBE = 3
    const val STAGE_ASSEMBLE = 4

    val stageTitles = listOf("保存原音", "找人声", "转写", "整理对话")

    /** completed = 已完成阶段数；active = 正在进行的阶段（1–4，无则 null）；failed = 当前阶段失败。 */
    data class StageProgress(val completed: Int, val active: Int?, val failed: Boolean) {
        val isDone: Boolean get() = completed >= TOTAL_STAGES
    }

    fun progressOf(state: String): StageProgress = when (state) {
        RECORDING -> StageProgress(0, STAGE_SAVE, false)
        FAILED -> StageProgress(0, STAGE_SAVE, true)
        RECORDED, RECOVERED -> StageProgress(STAGE_SAVE, null, false)
        VAD_RUNNING -> StageProgress(STAGE_SAVE, STAGE_VOICE, false)
        VAD_FAILED -> StageProgress(STAGE_SAVE, STAGE_VOICE, true)
        VAD_READY -> StageProgress(STAGE_VOICE, null, false)
        ASR_RUNNING -> StageProgress(STAGE_VOICE, STAGE_TRANSCRIBE, false)
        ASR_FAILED -> StageProgress(STAGE_VOICE, STAGE_TRANSCRIBE, true)
        ASSEMBLY_PENDING -> StageProgress(STAGE_TRANSCRIBE, STAGE_ASSEMBLE, false)
        ASSEMBLY_FAILED -> StageProgress(STAGE_TRANSCRIBE, STAGE_ASSEMBLE, true)
        ASR_READY, AUDIO_DELETED -> StageProgress(TOTAL_STAGES, null, false)
        else -> StageProgress(0, STAGE_SAVE, false)
    }

    /** 用户可见的一句话状态；全应用只此一份。 */
    fun labelOf(state: String): String = when (state) {
        RECORDING -> "正在录音"
        RECORDED, RECOVERED -> "原音已保存，等待找人声"
        VAD_RUNNING -> "正在找人声"
        VAD_FAILED -> "找人声失败，原音已保留"
        VAD_READY -> "人声已找到，等待转写"
        ASR_RUNNING -> "正在转写"
        ASR_FAILED -> "转写失败，原音已保留"
        ASSEMBLY_PENDING -> "文字已保存，等待整理对话"
        ASSEMBLY_FAILED -> "整理对话失败，文字已保存"
        ASR_READY -> "已整理完成"
        AUDIO_DELETED -> "原音已清理，文字保留"
        FAILED -> "录音文件异常，原音保留"
        else -> "处理中"
    }

    /** 带阶段编号的短文案，例如「③ 转写」，用于列表行。 */
    fun stagedLabelOf(state: String): String {
        val progress = progressOf(state)
        return if (progress.isDone) "已完成" else "①②③④"[progress.active!!.coerceIn(1, TOTAL_STAGES) - 1] + " " + labelOf(state)
    }

    val runningStates = setOf(RECORDING, VAD_RUNNING, ASR_RUNNING)

    /** 需要用户点「继续处理」才可能推进的状态；运行中的任务交给 WorkManager 自恢复，不显示按钮。 */
    val actionableStates = setOf(
        RECORDED, RECOVERED, VAD_READY, VAD_FAILED, ASR_FAILED, FAILED, ASSEMBLY_PENDING, ASSEMBLY_FAILED,
    )

    fun isActionable(state: String): Boolean = state in actionableStates
}
