package com.gongfpp.sonfolio.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_aliases", indices = [Index("canonicalId")])
data class ConversationAliasEntity(@PrimaryKey val oldId: String, val canonicalId: String)

@Entity(
    tableName = "audio_chunks",
    indices = [Index("startedAtMillis")],
)
data class AudioChunkEntity(
    @PrimaryKey val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val localPath: String,
    val byteSize: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val processingState: String,
    val errorMessage: String?,
    /** 录音发生时的时区；日期归属以它为准，而不是之后打开 App 时的设备时区。 */
    @androidx.room.ColumnInfo(defaultValue = "") val recordedZoneId: String = "",
    /** 录音发生时的 UTC 偏移（秒），供无时区数据库环境追溯。 */
    @androidx.room.ColumnInfo(defaultValue = "0") val recordedOffsetSeconds: Int = 0,
    /** 录音发生时按 recordedZoneId 算出的当地日期（ISO），日历按它分桶。 */
    @androidx.room.ColumnInfo(defaultValue = "") val localStartDate: String = "",
    /** 整理完成后生成的 AAC 压缩音；为空表示尚未压缩或仅剩原音。 */
    val compressedPath: String? = null,
    val compressedBytes: Long? = null,
)

@Entity(
    tableName = "speech_segments",
    foreignKeys = [
        ForeignKey(
            entity = AudioChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["audioChunkId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("audioChunkId")],
)
data class SpeechSegmentEntity(
    @PrimaryKey val id: String,
    val audioChunkId: String,
    val startOffsetMillis: Long,
    val endOffsetMillis: Long,
    val speechProbability: Float,
    val processingState: String,
)

@Entity(
    tableName = "transcripts",
    foreignKeys = [
        ForeignKey(
            entity = SpeechSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["speechSegmentId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("speechSegmentId"), Index("conversationId"), Index("startedAtMillis")],
)
data class TranscriptEntity(
    @PrimaryKey val id: String,
    val speechSegmentId: String,
    val conversationId: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val text: String,
    val languageTag: String,
    val modelName: String,
    val modelVersion: String,
    val processingState: String,
    val errorMessage: String?,
    /** 用户修正前的原始识别文字；为空表示从未修正过。 */
    val originalText: String? = null,
)

@Entity(
    tableName = "conversations",
    indices = [Index("startedAtMillis"), Index("processingState")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val zoneId: String,
    /** 自动生成的标题；AI 和规则只能写这个字段，永远不覆盖用户输入。 */
    val generatedTitle: String,
    /** 用户手动修改过的标题；为空表示仍在使用自动标题。 */
    val titleOverride: String? = null,
    val briefSummary: String,
    val summaryLevel: String,
    val processingState: String,
    /** 用户为这段对话写的简短备注。 */
    val note: String? = null,
    /** 对话开始时刻按所属录音时区算出的当地日期；为空表示旧数据，按设备时区回退。 */
    @androidx.room.ColumnInfo(defaultValue = "") val localStartDate: String = "",
)

@Entity(
    tableName = "conversation_summaries",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId", unique = true)],
)
data class ConversationSummaryEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val keyPointsJson: String,
    val decisionsJson: String,
    val followUpsJson: String,
    val openQuestionsJson: String,
    val generatedLocally: Boolean,
    val modelVersion: String?,
    val generatedAtMillis: Long,
)

@Entity(
    tableName = "markers",
    indices = [Index("markedAtMillis")],
)
data class MarkerEntity(
    @PrimaryKey val id: String,
    val markedAtMillis: Long,
    val windowBeforeMillis: Long,
    val windowAfterMillis: Long,
    val note: String?,
)

@Entity(
    tableName = "recording_gaps",
    indices = [Index("startedAtMillis")],
)
data class RecordingGapEntity(
    @PrimaryKey val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val reason: String,
    val recoveredAutomatically: Boolean,
    @androidx.room.ColumnInfo(defaultValue = "'INTERRUPTION'") val kind: String = "INTERRUPTION",
)

@Entity(
    tableName = "daily_journals",
    indices = [Index("localDate", unique = true)],
)
data class DailyJournalEntity(
    @PrimaryKey val id: String,
    val localDate: String,
    val zoneId: String,
    val narrative: String,
    val memorableJson: String,
    val possibleActionsJson: String,
    val sourceConversationCount: Int,
    val generatedAtMillis: Long,
    val modelVersion: String?,
    val processingState: String,
)
