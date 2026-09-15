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
    version = 8,
    exportSchema = true,
)
abstract class SonfolioDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        // 0.2.1 存储模型：整理完成后异步生成 AAC 压缩音；原始 WAV 按保留策略清理。
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_chunks ADD COLUMN compressedPath TEXT")
                db.execSQL("ALTER TABLE audio_chunks ADD COLUMN compressedBytes INTEGER")
            }
        }
        // 0.2.1 时间模型：保存录音发生时的时区/偏移/当地日期，日期归属不再随设备时区漂移。
        // 历史行留空（''），消费端按设备时区回退。
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_chunks ADD COLUMN recordedZoneId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE audio_chunks ADD COLUMN recordedOffsetSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE audio_chunks ADD COLUMN localStartDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN localStartDate TEXT NOT NULL DEFAULT ''")
            }
        }
        // 0.2.1 数据一致性：把用户手工输入与自动生成字段分开。
        // 存量 title 一律视为 AI 生成（迁移语义 B），titleOverride 为空表示尚未手工编辑。
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("PRAGMA defer_foreign_keys = TRUE")
                db.execSQL(
                    "CREATE TABLE conversations_new (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, " +
                        "startedAtMillis INTEGER NOT NULL, endedAtMillis INTEGER NOT NULL, zoneId TEXT NOT NULL, " +
                        "generatedTitle TEXT NOT NULL, titleOverride TEXT, briefSummary TEXT NOT NULL, " +
                        "summaryLevel TEXT NOT NULL, processingState TEXT NOT NULL, note TEXT)",
                )
                db.execSQL(
                    "INSERT INTO conversations_new (id, kind, startedAtMillis, endedAtMillis, zoneId, generatedTitle, briefSummary, summaryLevel, processingState, note) " +
                        "SELECT id, kind, startedAtMillis, endedAtMillis, zoneId, title, briefSummary, summaryLevel, processingState, note FROM conversations",
                )
                db.execSQL("DROP TABLE conversations")
                db.execSQL("ALTER TABLE conversations_new RENAME TO conversations")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_startedAtMillis ON conversations (startedAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_processingState ON conversations (processingState)")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN note TEXT")
                db.execSQL("ALTER TABLE transcripts ADD COLUMN originalText TEXT")
            }
        }
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { database -> instance = database }
            }
    }
}
