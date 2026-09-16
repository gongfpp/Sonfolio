package com.gongfpp.sonfolio.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM personal_vocabulary ORDER BY CASE status WHEN 'CANDIDATE' THEN 0 WHEN 'ACCEPTED' THEN 1 ELSE 2 END, seenCount DESC, lastSeenAtMillis DESC")
    fun observeAll(): Flow<List<PersonalVocabularyEntity>>

    @Query("SELECT * FROM personal_vocabulary WHERE term = :term LIMIT 1")
    suspend fun getByTerm(term: String): PersonalVocabularyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PersonalVocabularyEntity)

    @Query("UPDATE personal_vocabulary SET status = 'ACCEPTED', acceptedAtMillis = :acceptedAtMillis WHERE term = :term")
    suspend fun acceptByTerm(term: String, acceptedAtMillis: Long)

    @Query("UPDATE personal_vocabulary SET status = 'IGNORED' WHERE term = :term")
    suspend fun ignoreByTerm(term: String)

    @Query("DELETE FROM personal_vocabulary WHERE term = :term")
    suspend fun deleteByTerm(term: String)

    @Query("SELECT term FROM personal_vocabulary WHERE status = 'ACCEPTED' ORDER BY lastSeenAtMillis DESC LIMIT 200")
    suspend fun acceptedTerms(): List<String>
}
