package com.gongfpp.sonfolio.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val title: String,
    val briefSummary: String,
    val summaryLevel: String,
    val processingState: String,
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
    val endedAtMillis: Long,
    val reason: String,
    val recoveredAutomatically: Boolean,
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
