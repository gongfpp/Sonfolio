package com.gongfpp.sonfolio

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gongfpp.sonfolio.data.local.SonfolioDatabase
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM 迁移门禁：把生产迁移对象实际执行的 SQL 重放到内存 SQLite（sqlite-jdbc），
 * 断言迁移到最新版本后的表、列（含 notNull）与索引（含唯一性）和导出的
 * `schemas/<版本>.json` 完全一致。
 *
 * 这里只验证结构；数据保留与 `integrity_check` 仍由真机 `MigrationIntegrationTest` 覆盖，
 * 两者不能互相替代。
 */
class MigrationSchemaTest {
    private val migrations: List<Migration> = listOf(
        SonfolioDatabase.MIGRATION_1_2,
        SonfolioDatabase.MIGRATION_2_3,
        SonfolioDatabase.MIGRATION_3_4,
        SonfolioDatabase.MIGRATION_4_5,
        SonfolioDatabase.MIGRATION_5_6,
        SonfolioDatabase.MIGRATION_6_7,
        SonfolioDatabase.MIGRATION_7_8,
        SonfolioDatabase.MIGRATION_8_9,
        SonfolioDatabase.MIGRATION_9_10,
        SonfolioDatabase.MIGRATION_10_11,
        SonfolioDatabase.MIGRATION_11_12,
        SonfolioDatabase.MIGRATION_12_13,
    )

    @Test
    fun migrationChainIsContiguousUpToExportedVersion() {
        val version = schema(13).getInt("version")
        assertEquals(13, version)
        assertEquals(
            "迁移链必须逐版本连续，且覆盖到导出 Schema 的版本",
            (1 until version).map { it to it + 1 },
            migrations.map { it.startVersion to it.endVersion },
        )
    }

    @Test
    fun replayedMigrationsProduceExportedSchema() {
        val target = schema(13)
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            createSchema(db, schema(1))
            for (migration in migrations) {
                for (sql in recordedSql(migration)) db.exec(sql)
            }
            assertSchemaMatches(db, target)
        }
    }

    /** 用某个版本的 Schema JSON 建库（createSql + 索引 + setupQueries）。 */
    private fun createSchema(db: Connection, schema: JSONObject) {
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val table = entities.getJSONObject(i)
            val name = table.getString("tableName")
            db.exec(table.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = table.optJSONArray("indices") ?: JSONArray()
            for (j in 0 until indices.length()) {
                db.exec(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
            }
        }
        for (query in schema.getJSONArray("setupQueries")) db.exec(query.toString())
    }

    /**
     * 让迁移对象在真实的 [SupportSQLiteDatabase] 类型下执行一次，只收集它 `execSQL` 的语句。
     * 迁移体里除了 `execSQL` 不调用别的方法；其余方法用代理返回默认值。
     */
    private fun recordedSql(migration: Migration): List<String> {
        val recorded = mutableListOf<String>()
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "execSQL" -> {
                    recorded += args!![0] as String
                    null
                }
                "toString" -> "RecordingSupportSQLiteDatabase(${migration.startVersion}->${migration.endVersion})"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                else -> defaultReturn(method.returnType)
            }
        }
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler,
        ) as SupportSQLiteDatabase
        migration.migrate(database)
        return recorded
    }

    private fun defaultReturn(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Character.TYPE -> ' '
        else -> null
    }

    private fun assertSchemaMatches(db: Connection, schema: JSONObject) {
        val entities = schema.getJSONArray("entities")
        val expectedTables = mutableSetOf<String>()
        for (i in 0 until entities.length()) {
            val table = entities.getJSONObject(i)
            val name = table.getString("tableName")
            expectedTables += name

            val expectedColumns = linkedMapOf<String, Boolean>()
            val fields = table.getJSONArray("fields")
            for (j in 0 until fields.length()) {
                val field = fields.getJSONObject(j)
                expectedColumns[field.getString("columnName")] = field.optBoolean("notNull", false)
            }
            val actualColumns = linkedMapOf<String, Boolean>()
            db.query("PRAGMA table_info(`$name`)").use { rows ->
                while (rows.next()) actualColumns[rows.getString("name")] = rows.getInt("notnull") == 1
            }
            assertEquals("$name 的列名与导出的 Schema 不一致", expectedColumns.keys, actualColumns.keys)
            for ((column, notNull) in expectedColumns) {
                assertEquals("$name.$column 的 notNull 与导出的 Schema 不一致", notNull, actualColumns[column])
            }

            val expectedIndices = linkedMapOf<String, Pair<Boolean, List<String>>>()
            val indices = table.optJSONArray("indices") ?: JSONArray()
            for (j in 0 until indices.length()) {
                val index = indices.getJSONObject(j)
                val columns = index.getJSONArray("columnNames")
                expectedIndices[index.getString("name")] =
                    index.getBoolean("unique") to (0 until columns.length()).map { columns.getString(it) }
            }
            val indexNames = mutableListOf<String>()
            db.query("PRAGMA index_list(`$name`)").use { rows ->
                while (rows.next()) {
                    val indexName = rows.getString("name")
                    if (!indexName.startsWith("sqlite_autoindex")) indexNames += indexName
                }
            }
            val actualIndices = linkedMapOf<String, Pair<Boolean, List<String>>>()
            for (indexName in indexNames) {
                val columns = mutableListOf<String>()
                db.query("PRAGMA index_info(`$indexName`)").use { rows ->
                    while (rows.next()) columns += rows.getString("name")
                }
                val unique = db.query("PRAGMA index_list(`$name`)").use { rows ->
                    var value = false
                    while (rows.next()) if (rows.getString("name") == indexName) value = rows.getInt("unique") == 1
                    value
                }
                actualIndices[indexName] = unique to columns
            }
            assertEquals("$name 的索引与导出的 Schema 不一致", expectedIndices, actualIndices)
        }

        val actualTables = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rows ->
            while (rows.next()) actualTables += rows.getString(1).orEmpty()
        }
        assertEquals("迁移后的表集合与导出的 Schema 不一致", expectedTables + "room_master_table", actualTables)
    }

    private fun schema(version: Int): JSONObject {
        val relative = "schemas/com.gongfpp.sonfolio.data.local.SonfolioDatabase/$version.json"
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relative)
            if (candidate.isFile) return JSONObject(candidate.readText()).getJSONObject("database")
            directory = directory.parentFile
        }
        throw AssertionError("找不到导出的 Schema：$relative（user.dir=${System.getProperty("user.dir")}）")
    }
}

private fun Connection.exec(sql: String) {
    createStatement().use { it.execute(sql) }
}

private fun Connection.query(sql: String) = createStatement().executeQuery(sql)
