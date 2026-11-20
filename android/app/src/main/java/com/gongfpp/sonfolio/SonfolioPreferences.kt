package com.gongfpp.sonfolio

import android.content.Context

/** Small, local-only preferences used by the recording and transcription pipeline. */
class SonfolioPreferences(context: Context, fileName: String = FILE_NAME) {
    private val preferences = context.applicationContext.getSharedPreferences(
        fileName,
        Context.MODE_PRIVATE,
    )

    val preferredLanguage: String
        get() = preferences.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE

    val minimumSpeechSeconds: Int
        get() = preferences.getInt(KEY_MIN_SPEECH_SECONDS, DEFAULT_MIN_SPEECH_SECONDS)

    val minimumTextCharacters: Int
        get() = preferences.getInt(KEY_MIN_TEXT_CHARACTERS, DEFAULT_MIN_TEXT_CHARACTERS)

    val recordingSessionStartedAtMillis: Long?
        get() = preferences.getLong(KEY_RECORDING_SESSION_START, 0L).takeIf { it > 0L }

    val chargeOnly: Boolean get() = preferences.getBoolean("charge-only", false)
    val assemblyVersion: Int get() = preferences.getInt("assembly-version", 0)
    fun completeAssemblyMigration() { check(preferences.edit().putInt("assembly-version", 4).commit()) }
    fun setChargeOnly(value: Boolean) { preferences.edit().putBoolean("charge-only", value).apply() }

    val markerWindows: List<Int> get() = listOf(3, 10, 20).mapIndexed { index, default ->
        preferences.getInt("marker-minutes-$index", default).coerceIn(1, 60)
    }
    fun setMarkerWindow(index: Int, minutes: Int) {
        require(index in 0..2 && minutes in MARKER_OPTIONS)
        preferences.edit().putInt("marker-minutes-$index", minutes).apply()
    }

    val retentionDays: Int get() = preferences.getInt("retention-days", 0).coerceIn(0, MAX_RETENTION_DAYS)
    fun setRetentionDays(value: Int) {
        require(value in 0..MAX_RETENTION_DAYS) { "保留天数需在 0–$MAX_RETENTION_DAYS 之间" }
        preferences.edit().putInt("retention-days", value).apply()
    }

    /** 相邻语音间隔不超过它才合并为同一场对话。 */
    val conversationGapMinutes: Int get() = preferences.getInt(KEY_CONVERSATION_GAP_MINUTES, DEFAULT_CONVERSATION_GAP_MINUTES)
        .coerceIn(MIN_CONVERSATION_GAP_MINUTES, MAX_CONVERSATION_GAP_MINUTES)
    fun setConversationGapMinutes(value: Int) {
        require(value in MIN_CONVERSATION_GAP_MINUTES..MAX_CONVERSATION_GAP_MINUTES)
        preferences.edit().putInt(KEY_CONVERSATION_GAP_MINUTES, value).apply()
    }

    val titleMode: TitleMode get() = runCatching { TitleMode.valueOf(preferences.getString(KEY_TITLE_MODE, null) ?: "") }.getOrDefault(TitleMode.AI_OR_FIRST)
    fun setTitleMode(value: TitleMode) { preferences.edit().putString(KEY_TITLE_MODE, value.name).apply() }

    /** 第 5 步纠错用在线模型时默认关闭，需用户单独开启（会持续发送转写文字并计费）。 */
    val correctionOnlineEnabled: Boolean get() = preferences.getBoolean(KEY_CORRECTION_ONLINE, false)
    fun setCorrectionOnlineEnabled(value: Boolean) { preferences.edit().putBoolean(KEY_CORRECTION_ONLINE, value).apply() }

    /**
     * 首次启动引导：完成后不再整页拦截；选择“稍后”只记时间戳，7 天内不再拦截，
     * 但设置页仍可主动重新进入。两个标记互斥，完成时清掉延后标记。
     */
    val onboardingCompleted: Boolean get() = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    val onboardingDeferredAt: Long get() = preferences.getLong(KEY_ONBOARDING_DEFERRED_AT, 0L)

    fun completeOnboarding() {
        check(preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).remove(KEY_ONBOARDING_DEFERRED_AT).commit())
    }

    fun deferOnboarding(now: Long = System.currentTimeMillis()) {
        preferences.edit().putLong(KEY_ONBOARDING_DEFERRED_AT, now).apply()
    }

    /** 用户主动从设置重看引导时清掉完成标记，让路由重新判定。 */
    fun reopenOnboarding() {
        preferences.edit().remove(KEY_ONBOARDING_COMPLETED).remove(KEY_ONBOARDING_DEFERRED_AT).apply()
    }

    /**
     * 引导里选择“仍然继续（未验证）”时置位：密钥已保存但没通过探测。
     * 就绪页据此改口径，不能把未验证的在线密钥写成“已生效”。
     */
    val onboardingUnverifiedRecognition: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING_UNVERIFIED, false)

    fun setOnboardingUnverifiedRecognition(value: Boolean) {
        preferences.edit().putBoolean(KEY_ONBOARDING_UNVERIFIED, value).apply()
    }

    fun setPreferredLanguage(value: String) {
        preferences.edit().putString(KEY_LANGUAGE, value).apply()
    }

    fun setMinimumSpeechSeconds(value: Int) {
        preferences.edit().putInt(KEY_MIN_SPEECH_SECONDS, value.coerceIn(1, 60)).apply()
    }

    fun setMinimumTextCharacters(value: Int) {
        preferences.edit().putInt(KEY_MIN_TEXT_CHARACTERS, value.coerceIn(0, 40)).apply()
    }

    fun beginRecordingSession(startedAtMillis: Long) {
        preferences.edit().putLong(KEY_RECORDING_SESSION_START, startedAtMillis).apply()
    }

    fun clearRecordingSession() {
        preferences.edit().remove(KEY_RECORDING_SESSION_START).apply()
    }

    companion object {
        val MARKER_OPTIONS = listOf(1, 3, 5, 10, 15, 20, 30, 45, 60)
        const val DEFAULT_LANGUAGE = "zh"
        const val DEFAULT_MIN_SPEECH_SECONDS = 5
        const val DEFAULT_MIN_TEXT_CHARACTERS = 4
        const val MAX_RETENTION_DAYS = 365
        val RETENTION_OPTIONS = listOf(0, 7, 30, 90)
        const val DEFAULT_CONVERSATION_GAP_MINUTES = 2
        const val MIN_CONVERSATION_GAP_MINUTES = 1
        const val MAX_CONVERSATION_GAP_MINUTES = 120
        val CONVERSATION_GAP_OPTIONS = listOf(1, 2, 3, 5, 10, 15, 30, 60, 120)

        private const val FILE_NAME = "sonfolio-preferences"
        private const val KEY_LANGUAGE = "preferred-language"
        private const val KEY_MIN_SPEECH_SECONDS = "minimum-speech-seconds"
        private const val KEY_MIN_TEXT_CHARACTERS = "minimum-text-characters"
        private const val KEY_RECORDING_SESSION_START = "recording-session-start"
        private const val KEY_CONVERSATION_GAP_MINUTES = "conversation-gap-minutes"
        private const val KEY_TITLE_MODE = "title-mode"
        private const val KEY_CORRECTION_ONLINE = "correction-online-enabled"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding-completed"
        private const val KEY_ONBOARDING_DEFERRED_AT = "onboarding-deferred-at"
        private const val KEY_ONBOARDING_UNVERIFIED = "onboarding-unverified-recognition"
    }
}

/** 自动标题来源：离线第一句 / AI 总结 / 两者结合。 */
enum class TitleMode(val label: String, val description: String) {
    FIRST_SENTENCE("第一句有信息量的话", "离线可用，直接取转写里第一句有内容的短句"),
    AI("AI 总结标题", "需要启用本地或在线 AI；尚未生成时回退到第一句"),
    AI_OR_FIRST("有 AI 用 AI，否则第一句", "默认；生成 AI 总结后用模型标题，否则用第一句"),
}
