package com.gongfpp.sonfolio

import android.app.Application
import com.gongfpp.sonfolio.data.local.SonfolioDatabase
import com.gongfpp.sonfolio.processing.ProcessingScheduler
import com.gongfpp.sonfolio.recording.RecordingRepository

class SonfolioApplication : Application() {
    val transcriptionSettings by lazy { com.gongfpp.sonfolio.processing.TranscriptionSettingsStore(this) }
    val summarySettings by lazy { com.gongfpp.sonfolio.summary.SummarySettingsStore(this) }
    val summaryCoordinator by lazy { com.gongfpp.sonfolio.summary.SummaryCoordinator(this) }
    val database by lazy { SonfolioDatabase.getInstance(this) }
    val preferences by lazy { SonfolioPreferences(this) }
    val conversationRepository by lazy {
        ConversationRepository(database, preferences)
    }
    val processingScheduler by lazy { ProcessingScheduler(this) }
    val recordingRepository by lazy {
        RecordingRepository(database.recordingDao(), processingScheduler, preferences, java.io.File(filesDir, "capture-journal"))
    }
}
