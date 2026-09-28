package com.gongfpp.sonfolio.recording

import org.json.JSONArray
import org.json.JSONObject

/** Explicit logical migrations, independent of the installed Room schema version. */
internal object BackupFormat {
    const val VERSION = 3
    val tables = listOf("audio_chunks", "speech_segments", "conversations", "transcripts", "markers", "recording_gaps", "conversation_summaries", "daily_journals", "summary_runs", "conversation_aliases", "personal_vocabulary")

    fun migrate(manifest: JSONObject): JSONObject {
        val version = manifest.optInt("formatVersion", manifest.optInt("version", 0))
        require(version in 1..VERSION) { "不支持此备份版本，请使用相应版本的声迹" }
        val data = manifest.getJSONObject("tables")
        val expected = if (version < 3) tables - "personal_vocabulary" else tables
        require(data.keys().asSequence().toSet() == expected.toSet()) { "备份表不完整或包含未知表" }
        if (version < 3) {
            // v1/v2 never exported vocabulary. Do not pretend it was recovered.
            data.put("personal_vocabulary", JSONArray())
            for (table in listOf("conversations", "daily_journals")) {
                val rows = data.getJSONArray(table)
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    if (table == "conversations") {
                        if (version == 1 && row.has("title")) {
                            require(!row.has("generatedTitle")) { "备份标题字段冲突" }
                            row.put("generatedTitle", row.remove("title"))
                        }
                        if (row.has("note")) {
                            val note = if (row.isNull("note")) "" else row.getString("note")
                            // Summaries are rebuilt automatically, so moving notes there would lose user content later.
                            require(note.isBlank()) { "旧备份包含非空对话备注，当前版本无法完整保留。请先在旧版本导出备注；本次未导入或删除任何数据" }
                            row.remove("note")
                        }
                    }
                    if (row.has("processingState")) {
                        require(row.getString("processingState") == "READY") { "旧备份存在不支持的内容状态，未丢弃任何内容" }
                        row.remove("processingState")
                    }
                }
            }
        }
        return data
    }

    /** Never carry local or compressed absolute paths across the trust boundary. */
    fun resetAudioPaths(row: JSONObject) {
        row.put("localPath", "").put("compressedPath", JSONObject.NULL)
    }
}
