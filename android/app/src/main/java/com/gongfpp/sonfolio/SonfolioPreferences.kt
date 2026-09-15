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

    val retentionDays: Int get() = preferences.getInt("retention-days", 0)
    fun setRetentionDays(value: Int) {
        require(value in listOf(0, 7, 30, 90))
        preferences.edit().putInt("retention-days", value).apply()
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

        private const val FILE_NAME = "sonfolio-preferences"
        private const val KEY_LANGUAGE = "preferred-language"
        private const val KEY_MIN_SPEECH_SECONDS = "minimum-speech-seconds"
        private const val KEY_MIN_TEXT_CHARACTERS = "minimum-text-characters"
        private const val KEY_RECORDING_SESSION_START = "recording-session-start"
    }
}
