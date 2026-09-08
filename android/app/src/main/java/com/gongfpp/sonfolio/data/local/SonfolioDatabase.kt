package com.gongfpp.sonfolio.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AudioChunkEntity::class,
        SpeechSegmentEntity::class,
        TranscriptEntity::class,
        ConversationEntity::class,
        ConversationSummaryEntity::class,
        MarkerEntity::class,
        RecordingGapEntity::class,
        DailyJournalEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SonfolioDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var instance: SonfolioDatabase? = null

        fun getInstance(context: Context): SonfolioDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SonfolioDatabase::class.java,
                    "sonfolio.db",
                ).build().also { database -> instance = database }
            }
    }
}
