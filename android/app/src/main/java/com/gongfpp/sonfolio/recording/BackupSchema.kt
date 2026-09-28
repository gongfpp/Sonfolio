package com.gongfpp.sonfolio.recording

import org.json.JSONObject

/**
 * Frozen logical format v4. This contract is deliberately NOT derived from Room at runtime.
 * A new database column cannot silently change exports or invalidate an old backup.
 */
internal object BackupSchema {
    private val definitions = linkedMapOf(
        "audio_chunks" to "id:TEXT,startedAtMillis:INTEGER,endedAtMillis:INTEGER?,localPath:TEXT,byteSize:INTEGER,sampleRateHz:INTEGER,channelCount:INTEGER,processingState:TEXT,errorMessage:TEXT?,recordedZoneId:TEXT,recordedOffsetSeconds:INTEGER,localStartDate:TEXT,compressedPath:TEXT?,compressedBytes:INTEGER?",
        "speech_segments" to "id:TEXT,audioChunkId:TEXT,startOffsetMillis:INTEGER,endOffsetMillis:INTEGER,speechProbability:REAL,processingState:TEXT",
        "transcripts" to "id:TEXT,speechSegmentId:TEXT,conversationId:TEXT?,startedAtMillis:INTEGER,endedAtMillis:INTEGER,text:TEXT,languageTag:TEXT,modelName:TEXT,modelVersion:TEXT,processingState:TEXT,errorMessage:TEXT?,originalText:TEXT?",
        "conversations" to "id:TEXT,kind:TEXT,startedAtMillis:INTEGER,endedAtMillis:INTEGER,zoneId:TEXT,generatedTitle:TEXT,titleOverride:TEXT?,briefSummary:TEXT,summaryLevel:TEXT,localStartDate:TEXT",
        "conversation_summaries" to "id:TEXT,conversationId:TEXT,keyPointsJson:TEXT,decisionsJson:TEXT,followUpsJson:TEXT,openQuestionsJson:TEXT,generatedLocally:INTEGER,modelVersion:TEXT?,generatedAtMillis:INTEGER",
        "markers" to "id:TEXT,markedAtMillis:INTEGER,windowBeforeMillis:INTEGER,windowAfterMillis:INTEGER,note:TEXT?",
        "recording_gaps" to "id:TEXT,startedAtMillis:INTEGER,endedAtMillis:INTEGER?,reason:TEXT,recoveredAutomatically:INTEGER,kind:TEXT",
        "daily_journals" to "id:TEXT,localDate:TEXT,zoneId:TEXT,narrative:TEXT,memorableJson:TEXT,possibleActionsJson:TEXT,sourceConversationCount:INTEGER,generatedAtMillis:INTEGER,modelVersion:TEXT?",
        "summary_runs" to "sourceKey:TEXT,sourceHash:TEXT,provider:TEXT,model:TEXT,outputJson:TEXT?,state:TEXT,message:TEXT?,updatedAtMillis:INTEGER,progressIndex:INTEGER,progressJson:TEXT?",
        "conversation_aliases" to "oldId:TEXT,canonicalId:TEXT",
        "personal_vocabulary" to "id:TEXT,term:TEXT,status:TEXT,seenCount:INTEGER,firstSeenAtMillis:INTEGER,lastSeenAtMillis:INTEGER,acceptedAtMillis:INTEGER?,sourceOriginal:TEXT?,sourceCorrected:TEXT?",
        "legacy_notes" to "conversationId:TEXT,title:TEXT,body:TEXT",
    )
    data class Column(val name: String, val type: String, val nullable: Boolean)
    data class Record(val values: Map<String, Any>)
    private val defaults: Map<String, Any> = mapOf(
        "audio_chunks.recordedZoneId" to "", "audio_chunks.recordedOffsetSeconds" to 0L,
        "audio_chunks.localStartDate" to "", "conversations.localStartDate" to "",
        "recording_gaps.kind" to "INTERRUPTION", "summary_runs.progressIndex" to 0L,
    )
    fun columns(table: String): List<Column> = definitions.getValue(table).split(",").map {
        val (name, type) = it.split(":")
        Column(name, type.removeSuffix("?"), type.endsWith("?"))
    }
    fun decode(table: String, row: JSONObject): Record {
        val fields = columns(table)
        require(row.keys().asSequence().toSet().all { name -> fields.any { it.name == name } }) {
            "备份含当前格式不支持的字段，未丢弃任何内容"
        }
        return Record(fields.associate { field ->
            val value = if (row.has(field.name)) row.get(field.name)
                else defaults["$table.${field.name}"] ?: if (field.nullable) JSONObject.NULL
                else error("备份缺少必要字段")
            require(if (value == JSONObject.NULL) field.nullable else when (field.type) {
                "TEXT" -> value is String
                "INTEGER" -> value is Int || value is Long
                "REAL" -> value is Number && value.toDouble().isFinite()
                else -> false
            }) { "备份字段类型不正确" }
            field.name to value
        })
    }
}
