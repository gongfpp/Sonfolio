package com.gongfpp.sonfolio.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Immutable legacy user content; intentionally not cascade-deleted when conversations are rebuilt. */
@Entity(tableName = "legacy_notes")
data class LegacyNoteEntity(
    @PrimaryKey val conversationId: String,
    val title: String,
    val body: String,
)

@Dao
interface LegacyNoteDao {
    @Query("SELECT * FROM legacy_notes ORDER BY conversationId")
    fun observeAll(): Flow<List<LegacyNoteEntity>>
}
