package com.gongfpp.sonfolio.recording

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.gongfpp.sonfolio.data.local.SonfolioDatabase
import com.gongfpp.sonfolio.processing.AudioFileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

/** Versioned logical backup. Keys, models, local absolute paths and system jobs are excluded. */
internal class MemoryBackup(private val context: Context, private val database: SonfolioDatabase,
    private val audioFileMutex: kotlinx.coroutines.sync.Mutex = AudioFileAccess.mutex) {
    suspend fun export(uri: Uri): String = withContext(Dispatchers.IO) { audioFileMutex.withLock {
        requireIdle()
        // 三个概念分开：formatVersion 是备份格式自身版本，databaseSchema 是导出时的 Room 版本，
        // appVersion 只做追溯记录；恢复兼容旧 formatVersion，而不是要求 schema 相等。
        val manifest = JSONObject()
            .put("format", "sonfolio-memory")
            .put("formatVersion", FORMAT_VERSION)
            .put("databaseSchema", database.openHelper.readableDatabase.version)
            .put("appVersion", buildAppVersion())
        val tables = JSONObject()
        database.withTransaction {
            val sql = database.openHelper.readableDatabase
            TABLES.forEach { table ->
                val rows = JSONArray()
                sql.query("SELECT * FROM $table").use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = JSONObject()
                        cursor.columnNames.forEachIndexed { index, name -> row.put(name, when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                            else -> error("备份遇到不支持的数据类型，未生成完整备份")
                        }) }
                        rows.put(row)
                    }
                }
                tables.put(table, rows)
            }
        }
        val files = linkedMapOf<String, File>()
        val chunks = tables.getJSONArray("audio_chunks")
        for (index in 0 until chunks.length()) {
            ensureActive()
            val row = chunks.getJSONObject(index)
            require(!row.isNull("endedAtMillis")) { "仍有录音未收尾，请返回首页恢复现场后再备份" }
            val id = safeId(row.getString("id"))
            val wavPath = row.getString("localPath")
            // JSONObject.NULL 经 optString 会变成字面量 "null"；必须先判断 isNull。
            val compressedPath = if (row.isNull("compressedPath")) null else row.optString("compressedPath").takeIf { it.isNotBlank() }
            if (row.getString("processingState") != "AUDIO_DELETED") {
                when {
                    wavPath.isNotBlank() && File(wavPath).isFile -> {
                        val name = "audio/$id.wav"
                        row.put("localPath", "").put("compressedPath", JSONObject.NULL)
                        row.put("backupAudio", name).put("backupBytes", File(wavPath).length()).put("backupSha256", hash(File(wavPath)))
                        files[name] = File(wavPath)
                    }
                    compressedPath != null && File(compressedPath).isFile -> {
                        // 原始 WAV 已按保留策略删除时，备份压缩音；文字始终完整。
                        val name = "audio/$id.m4a"
                        row.put("localPath", "").put("compressedPath", "")
                        row.put("backupAudio", name).put("backupBytes", File(compressedPath).length()).put("backupSha256", hash(File(compressedPath)))
                        files[name] = File(compressedPath)
                    }
                    else -> error("部分录音丢失，无法制作完整备份；已有数据未改动")
                }
            } else {
                row.put("localPath", "").put("compressedPath", JSONObject.NULL)
            }
        }
        manifest.put("tables", tables)
        val metadata = manifest.toString().toByteArray(Charsets.UTF_8)
        require(metadata.size <= MAX_METADATA) { "文字数据超过当前单份备份上限（64 MB），请先分批导出录音" }
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入备份位置")
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(metadata); zip.closeEntry()
            files.forEach { (name, file) ->
                ensureActive()
                zip.putNextEntry(ZipEntry(name)); file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break; zip.write(buffer, 0, n) }
                }; zip.closeEntry()
            }
        }
        "完整备份已写入：${chunks.length()} 份录音记录、${tables.getJSONArray("transcripts").length()} 条转写，包含标记、总结与对话关系。"
    } }

    /** Empty destination only: never replaces or merges an existing user's recording library. */
    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) { audioFileMutex.withLock {
        requireIdle(); requireEmpty()
        val directory = File(context.filesDir, "recordings/restore-${UUID.randomUUID()}")
        check(directory.mkdirs()) { "无法建立恢复目录" }
        var committed = false
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                require(zip.nextEntry?.name == "manifest.json") { "不是声迹完整备份文件，普通录音导出不能用于恢复" }
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    ensureActive(); val n = zip.read(buffer); if (n < 0) break
                    require(bytes.size() + n <= MAX_METADATA) { "备份文字数据过大" }; bytes.write(buffer, 0, n)
                }
                val manifest = JSONObject(bytes.toString("UTF-8"))
                require(manifest.getString("format") == "sonfolio-memory") { "不是声迹完整备份文件，普通录音导出不能用于恢复" }
                // 旧版 manifest 写的是 "version"；新版分开 formatVersion 与 databaseSchema。
                val formatVersion = manifest.optInt("formatVersion", manifest.optInt("version", 0))
                require(formatVersion in 1..FORMAT_VERSION) { "不支持此备份版本，请使用相应版本的声迹" }
                val tables = manifest.getJSONObject("tables")
                require(tables.keys().asSequence().toSet() == TABLES.toSet()) { "备份表不完整" }
                val chunks = tables.getJSONArray("audio_chunks")
                val expected = linkedMapOf<String, JSONObject>()
                val ids = mutableSetOf<String>()
                for (index in 0 until chunks.length()) {
                    val row = chunks.getJSONObject(index)
                    val id = safeId(row.getString("id"))
                    require(ids.add(id) && !row.isNull("endedAtMillis")) { "备份包含重复或未完成的录音" }
                    // 备份中可播放文件的扩展名以清单条目为准（wav 或 m4a）。
                    val extension = if (row.optString("backupAudio", "").endsWith(".m4a")) "m4a" else "wav"
                    val file = File(directory, "$id.$extension")
                    row.put("localPath", if (extension == "wav") file.path else "")
                    if (extension == "m4a") row.put("compressedPath", file.path)
                    if (row.getString("processingState") != "AUDIO_DELETED") {
                        require(row.getString("backupAudio") == "audio/$id.$extension" && row.getLong("backupBytes") >= 44) { "录音清单无效" }
                        expected[row.getString("backupAudio")] = row
                    } else {
                        // 已清理的切片不指向任何存在的文件。
                        row.put("localPath", "")
                        if (!row.isNull("compressedPath")) row.put("compressedPath", "")
                    }
                }
                val required = expected.values.fold(0L) { total, row -> Math.addExact(total, row.getLong("backupBytes")) }
                require(required >= 0 && directory.usableSpace > required + RESERVE) { "空间不足，需容纳全部录音并另留 512 MB" }
                while (true) {
                    ensureActive()
                    val entry = zip.nextEntry ?: break
                    val row = expected.remove(entry.name) ?: error("备份包含未知或重复文件")
                    // 压缩音条目的目标是 compressedPath；原始 WAV 目标仍是 localPath。
                    val target = File(if (entry.name.endsWith(".m4a")) row.getString("compressedPath") else row.getString("localPath"))
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    FileOutputStream(target).use { output ->
                        while (true) {
                            ensureActive(); val n = zip.read(buffer); if (n < 0) break
                            count += n
                            require(count <= row.getLong("backupBytes") && directory.usableSpace > RESERVE) { "录音大小不符或剩余空间不足" }
                            digest.update(buffer, 0, n); output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                    require(count == row.getLong("backupBytes") && hex(digest.digest()) == row.getString("backupSha256")) { "备份录音校验失败，未导入任何记录" }
                }
                require(expected.isEmpty()) { "备份缺少录音文件，未导入任何记录" }
                ensureActive()
                withContext(NonCancellable) { database.withTransaction {
                    requireEmpty(); requireIdle()
                    val sql = database.openHelper.writableDatabase
                    TABLES.forEach { table ->
                        val columnInfo = sql.query("PRAGMA table_info($table)").use { c ->
                            buildMap {
                                while (c.moveToNext()) {
                                    val name = c.getString(c.getColumnIndexOrThrow("name"))
                                    put(name, ColumnRule(
                                        notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1,
                                        hasDefault = !c.isNull(c.getColumnIndexOrThrow("dflt_value")),
                                    ))
                                }
                            }
                        }
                        val columns = columnInfo.keys
                        val rows = tables.getJSONArray(table)
                        for (index in 0 until rows.length()) {
                            val row = rows.getJSONObject(index)
                            if (table == "audio_chunks") {
                                listOf("backupAudio", "backupBytes", "backupSha256").forEach(row::remove)
                                when (row.getString("processingState")) {
                                    "VAD_RUNNING" -> row.put("processingState", "RECORDED")
                                    "ASR_RUNNING" -> row.put("processingState", "VAD_READY")
                                }
                            }
                            if (table == "summary_runs" && row.getString("state") in listOf("RUNNING", "QUEUED")) row.put("state", "CANCELLED").put("message", "备份已恢复，请重新配置总结方式后手动生成")
                            // 旧格式备份的列名迁移（如 conversations.title → generatedTitle）。
                            if (formatVersion == 1) LEGACY_RENAMES[table].orEmpty().forEach { (from, to) ->
                                if (row.has(from)) { row.put(to, row.get(from)); row.remove(from) }
                            }
                            val rowKeys = row.keys().asSequence().toSet()
                            require(columns.containsAll(rowKeys)) { "备份字段与当前版本不匹配" }
                            // 缺失列仅允许旧备份尚未包含的可空/有默认值列（如 note、originalText），其余拒绝。
                            val missing = columns - rowKeys
                            require(missing.all { val rule = columnInfo.getValue(it); !rule.notNull || rule.hasDefault }) { "备份字段与当前版本不匹配" }
                            val values = ContentValues()
                            rowKeys.forEach { name -> when (val value = row.get(name)) {
                                JSONObject.NULL -> values.putNull(name)
                                is String -> values.put(name, value)
                                is Int -> values.put(name, value)
                                is Long -> values.put(name, value)
                                is Double -> values.put(name, value)
                                else -> error("备份字段类型不正确")
                            } }
                            sql.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
                        }
                    }
                    sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "备份关联数据不完整" } }
                }; committed = true }
                "已恢复 ${chunks.length()} 份录音记录及转写、标记、总结。模型和 API Key 不在备份内，请在设置重新下载或配置。"
            }
        } finally {
            // Only this newly-created, validated staging directory is removed on failure.
            if (!committed) directory.deleteRecursively()
        }
    } }

    private fun requireIdle() {
        require(!RecordingService.isRunningInProcess) { "请先停止录音再备份或恢复" }
        require(File(context.filesDir, "capture-journal").listFiles().orEmpty().none { it.extension == "capture" }) { "录音现场还在入库，请返回首页稍后再试" }
    }
    private suspend fun requireEmpty() = database.withTransaction {
        TABLES.forEach { table -> database.openHelper.readableDatabase.query("SELECT 1 FROM $table LIMIT 1").use { require(!it.moveToFirst()) { "为保护现有记录，只允许恢复到没有数据的声迹；不会覆盖或清空本机内容" } } }
    }
    private fun safeId(id: String): String { require(id.matches(Regex("[a-zA-Z0-9_-]{1,160}"))) { "备份录音标识无效" }; return id }
    private suspend fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(256 * 1024); while (true) { kotlin.coroutines.coroutineContext.ensureActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    companion object {
        private val TABLES = listOf("audio_chunks", "speech_segments", "conversations", "transcripts", "markers", "recording_gaps", "conversation_summaries", "daily_journals", "summary_runs", "conversation_aliases")
        private const val MAX_METADATA = 64 * 1024 * 1024
        private const val RESERVE = 512L * 1024 * 1024
        private const val FORMAT_VERSION = 2
        /** formatVersion 1 备份在恢复时改名到当前列名；缺失的可空列按默认值导入。 */
        private val LEGACY_RENAMES = mapOf("conversations" to mapOf("title" to "generatedTitle"))

        private data class ColumnRule(val notNull: Boolean, val hasDefault: Boolean)
    }

    private fun buildAppVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "unknown"
    }.getOrDefault("unknown")
}
