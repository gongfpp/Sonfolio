package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.recording.BackupFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupFormatTest {
    private fun fixture(version: Int): JSONObject {
        val tables = JSONObject()
        BackupFormat.tables.filter { (version >= 3 || it != "personal_vocabulary") && (version >= 4 || it != "legacy_notes") }.forEach { tables.put(it, JSONArray()) }
        return JSONObject().put("formatVersion", version).put("tables", tables)
    }
    @Test fun migratesRealLegacyColumnsAndEmptyNotes() {
        for (version in 1..2) {
            val manifest = fixture(version)
            val row = JSONObject().put(if (version == 1) "title" else "generatedTitle", "旧标题")
                .put("briefSummary", "小结").put("note", JSONObject.NULL).put("processingState", "READY")
            manifest.getJSONObject("tables").getJSONArray("conversations").put(row)
            manifest.getJSONObject("tables").getJSONArray("daily_journals").put(JSONObject().put("processingState", "READY"))
            val migrated = BackupFormat.migrate(manifest)
            assertEquals("旧标题", row.getString("generatedTitle"))
            assertEquals("小结", row.getString("briefSummary"))
            assertFalse(row.has("note")); assertFalse(row.has("processingState"))
            assertEquals(0, migrated.getJSONArray("personal_vocabulary").length())
        }
    }
    @Test fun rejectsUnknownTablesAndNonreadyLegacyState() {
        val future = fixture(2)
        future.getJSONObject("tables").put("unknown", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.migrate(future) }
        val old = fixture(1)
        old.getJSONObject("tables").getJSONArray("conversations").put(JSONObject().put("processingState", "BROKEN"))
        assertThrows(IllegalArgumentException::class.java) { BackupFormat.migrate(old) }
        val notes = fixture(2)
        notes.getJSONObject("tables").getJSONArray("conversations").put(JSONObject().put("id", "old").put("generatedTitle", "旧标题").put("note", "不能丢失的用户备注"))
        val archived = BackupFormat.migrate(notes).getJSONArray("legacy_notes").getJSONObject(0)
        assertEquals("不能丢失的用户备注", archived.getString("body"))
        assertEquals("old", archived.getString("conversationId"))
    }
    @Test fun stripsBothUntrustedPathsAndPreservesVersionThreeVocabulary() {
        val row = JSONObject().put("localPath", "/private/secret").put("compressedPath", "/private/key")
        BackupFormat.resetAudioPaths(row)
        assertEquals("", row.getString("localPath")); assertTrue(row.isNull("compressedPath"))
        val current = fixture(3)
        current.getJSONObject("tables").getJSONArray("personal_vocabulary").put(JSONObject().put("term", "声迹"))
        assertEquals("声迹", BackupFormat.migrate(current).getJSONArray("personal_vocabulary").getJSONObject(0).getString("term"))
    }
}
