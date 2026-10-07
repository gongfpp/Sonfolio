package com.gongfpp.sonfolio

import android.app.Application
import com.gongfpp.sonfolio.data.local.SonfolioDatabase
import com.gongfpp.sonfolio.processing.ProcessingScheduler
import com.gongfpp.sonfolio.recording.RecordingRepository

open class SonfolioApplication : Application() {
    val transcriptionSettings by lazy { com.gongfpp.sonfolio.processing.TranscriptionSettingsStore(this) }
    val customAsrStore by lazy { com.gongfpp.sonfolio.processing.CustomAsrStore(this) }
    val summarySettings by lazy { com.gongfpp.sonfolio.summary.SummarySettingsStore(this) }
    val summaryCoordinator by lazy { com.gongfpp.sonfolio.summary.SummaryCoordinator(this) }
    open val database by lazy { SonfolioDatabase.getInstance(this) }
    val preferences by lazy { SonfolioPreferences(this) }
    val conversationRepository by lazy {
        ConversationRepository(database, preferences, vocabularyRepository)
    }
    val vocabularyRepository by lazy {
        PersonalVocabularyRepository(database.vocabularyDao())
    }
    val processingScheduler by lazy { ProcessingScheduler(this) }
    val usageStore by lazy { UsageStore(this) }
    val recordingRepository by lazy {
        RecordingRepository(database.recordingDao(), processingScheduler, preferences, java.io.File(filesDir, "capture-journal"))
    }
}
