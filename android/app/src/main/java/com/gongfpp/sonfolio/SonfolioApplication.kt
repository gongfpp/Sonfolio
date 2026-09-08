package com.gongfpp.sonfolio

import android.app.Application
import com.gongfpp.sonfolio.data.local.SonfolioDatabase

class SonfolioApplication : Application() {
    val database by lazy { SonfolioDatabase.getInstance(this) }
    val conversationRepository by lazy {
        ConversationRepository(database.conversationDao())
    }
}
