package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.recording.BackupSchema
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupSchemaTest {
    @Test fun archiveFieldsAreExplicitAndFutureFieldsAreNeverDropped() {
        val row = JSONObject().put("conversationId", "old").put("title", "标题").put("body", "备注")
        assertEquals("备注", BackupSchema.decode("legacy_notes", row).values["body"])
        row.put("futureField", "must keep")
        assertThrows(IllegalArgumentException::class.java) { BackupSchema.decode("legacy_notes", row) }
    }

    @Test fun missingRequiredAndWrongTypeAreRejected() {
        val missing = JSONObject().put("conversationId", "old").put("title", "标题")
        assertThrows(IllegalStateException::class.java) { BackupSchema.decode("legacy_notes", missing) }
        missing.put("body", 42)
        assertThrows(IllegalArgumentException::class.java) { BackupSchema.decode("legacy_notes", missing) }
    }
}
