package com.gongfpp.sonfolio

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.SonfolioDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 用导出的 Schema JSON 重建版本 4–7 的旧库，迁移到 8 后核对原音与对话字段不丢失。 */
    @Test fun upgradeFromVersion4Through7PreservesAudioAndConversationFields() = runBlocking {
        for (oldVersion in listOf(4, 5, 6, 7)) {
        val name = "migration-qa-${UUID.randomUUID()}.db"
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.gongfpp.sonfolio.data.local.SonfolioDatabase/$oldVersion.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                val tables = schema.getJSONArray("entities")
                for (i in 0 until tables.length()) {
                    val table = tables.getJSONObject(i); val tableName = table.getString("tableName")
                    old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", tableName))
                    val indices = table.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL(
                    "INSERT INTO audio_chunks (id, startedAtMillis, endedAtMillis, localPath, byteSize, sampleRateHz, channelCount, processingState, errorMessage) " +
                        "VALUES ('original', 10, 20, '/qa/original.wav', 364, 16000, 1, 'ASR_READY', NULL)",
                )
                // 版本 5 起对话才有 note；版本 6 起标题列改名为 generatedTitle 并新增 titleOverride；版本 7 起才有 localStartDate。
                val noteColumn = if (oldVersion >= 5) ", note" else ""
                val noteValue = if (oldVersion >= 5) ", '用户备注'" else ""
                val localStartColumn = if (oldVersion >= 7) ", localStartDate" else ""
                val localStartValue = if (oldVersion >= 7) ", '2026-09-15'" else ""
                if (oldVersion < 6) {
                    old.execSQL(
                        "INSERT INTO conversations (id, kind, startedAtMillis, endedAtMillis, zoneId, title, briefSummary, summaryLevel, processingState$noteColumn) " +
                            "VALUES ('conv', 'single', 10, 20, 'Asia/Shanghai', '旧标题', '旧小结', 'READY', 'READY'$noteValue)",
                    )
                } else {
                    old.execSQL(
                        "INSERT INTO conversations (id, kind, startedAtMillis, endedAtMillis, zoneId, generatedTitle, titleOverride, briefSummary, summaryLevel, processingState, note$localStartColumn) " +
                            "VALUES ('conv', 'single', 10, 20, 'Asia/Shanghai', '旧标题', '手工标题', '旧小结', 'READY', 'READY', '用户备注'$localStartValue)",
                    )
                }
                old.version = oldVersion
            }
            val migrated = Room.databaseBuilder(context, SonfolioDatabase::class.java, name)
                .addMigrations(SonfolioDatabase.MIGRATION_4_5, SonfolioDatabase.MIGRATION_5_6, SonfolioDatabase.MIGRATION_6_7, SonfolioDatabase.MIGRATION_7_8)
                .build()
            try {
                assertEquals(8, migrated.openHelper.writableDatabase.version)
                val chunk = migrated.recordingDao().getChunk("original")!!
                assertEquals("/qa/original.wav", chunk.localPath)
                assertEquals(364L, chunk.byteSize)
                assertNull(chunk.compressedPath)
                assertNull(chunk.compressedBytes)
                assertEquals("", chunk.recordedZoneId)
                val cursor = migrated.openHelper.writableDatabase
                    .query("SELECT generatedTitle, titleOverride, note, localStartDate FROM conversations WHERE id = 'conv'")
                cursor.moveToFirst()
                assertTrue(!cursor.isAfterLast)
                val conversation = listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
                cursor.close()
                // 标题迁入生成列；手工标题是 0.2.1 才出现的新能力，版本 6 之前没有可比对的历史值。
                assertEquals("旧标题", conversation[0])
                if (oldVersion >= 6) assertEquals("手工标题", conversation[1]) else assertNull(conversation[1])
                if (oldVersion >= 5) assertEquals("用户备注", conversation[2]) else assertNull(conversation[2])
                if (oldVersion >= 7) assertEquals("2026-09-15", conversation[3]) else assertEquals("", conversation[3])
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
        }
    }
}
