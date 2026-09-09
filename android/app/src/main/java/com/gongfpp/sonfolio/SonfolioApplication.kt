package com.gongfpp.sonfolio

import android.app.Application
import com.gongfpp.sonfolio.data.local.SonfolioDatabase
import com.gongfpp.sonfolio.recording.RecordingRepository

class SonfolioApplication : Application() {
    val database by lazy { SonfolioDatabase.getInstance(this) }
    val conversationRepository by lazy {
        ConversationRepository(database.conversationDao())
    }
    val recordingRepository by lazy {
        RecordingRepository(database.recordingDao())
    }
}
