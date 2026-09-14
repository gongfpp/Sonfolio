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
        SummaryRunEntity::class,
        ConversationAliasEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class SonfolioDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS conversation_aliases (oldId TEXT NOT NULL PRIMARY KEY, canonicalId TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_aliases_canonicalId ON conversation_aliases (canonicalId)")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE recording_gaps_new (id TEXT NOT NULL PRIMARY KEY, startedAtMillis INTEGER NOT NULL, endedAtMillis INTEGER, reason TEXT NOT NULL, recoveredAutomatically INTEGER NOT NULL, kind TEXT NOT NULL DEFAULT 'INTERRUPTION')")
                db.execSQL("INSERT INTO recording_gaps_new (id, startedAtMillis, endedAtMillis, reason, recoveredAutomatically) SELECT id, startedAtMillis, endedAtMillis, reason, recoveredAutomatically FROM recording_gaps")
                db.execSQL("DROP TABLE recording_gaps")
                db.execSQL("ALTER TABLE recording_gaps_new RENAME TO recording_gaps")
                db.execSQL("CREATE INDEX index_recording_gaps_startedAtMillis ON recording_gaps (startedAtMillis)")
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS summary_runs (sourceKey TEXT NOT NULL PRIMARY KEY, sourceHash TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, outputJson TEXT, state TEXT NOT NULL, message TEXT, updatedAtMillis INTEGER NOT NULL)")
            }
        }
        @Volatile
        private var instance: SonfolioDatabase? = null

        fun getInstance(context: Context): SonfolioDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SonfolioDatabase::class.java,
                    "sonfolio.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { database -> instance = database }
            }
    }
}
