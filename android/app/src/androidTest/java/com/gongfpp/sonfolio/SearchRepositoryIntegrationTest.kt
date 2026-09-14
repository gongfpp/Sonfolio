package com.gongfpp.sonfolio

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchRepositoryIntegrationTest {
    @Test fun searchBeyondOneHundredRequiresEveryTermAndRefreshesAfterMarker() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val preferenceName = "sonfolio-search-qa-${System.nanoTime()}"
        val repository = ConversationRepository(database, SonfolioPreferences(context, preferenceName))
        val dao = database.recordingDao()
        val base = System.currentTimeMillis() - 3_600_000L
        try {
            database.withTransaction {
                dao.insertChunk(AudioChunkEntity("chunk", base, base + 125_000, "/qa/metadata-only.wav", 4_000_044, 16_000, 1, "ASR_READY", null))
                repeat(125) { index ->
                    val id = index.toString().padStart(3, '0')
                    val offset = index * 1_000L
                    dao.insertSpeechSegments(listOf(SpeechSegmentEntity("s-$id", "chunk", offset, offset + 1_000, 1f, "ASR_READY")))
                    dao.insertTranscript(TranscriptEntity("t-$id", "s-$id", null, base + offset, base + offset + 1_000,
                        "甲乙 丙丁 戊己 庚辛" + if (index < 110) " 壬癸" else "", "zh", "qa", "qa", "ASR_READY", null))
                }
                repository.rebuildFromTranscripts()
            }
            val query = "甲乙 丙丁 戊己 庚辛 壬癸"
            val first = repository.observeSearch(query).first()
            assertEquals(100, first.hits.size)
            assertTrue(first.hasMore)
            val all = repository.observeSearch(query, visibleLimit = 200).first()
            assertEquals(110, all.hits.size)
            assertFalse(all.hasMore)
            assertEquals(first.hits, all.hits.take(100))
            assertTrue(repository.observeSearch("甲乙 丙丁 戊己 庚辛 不存在").first().hits.isEmpty())
            assertNotNull(repository.observeSearch("字".repeat(513)).first().errorMessage)

            val firstEmpty = CompletableDeferred<Unit>()
            val marked = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10_000) {
                    repository.observeSearch(query, markedOnly = true, visibleLimit = 200)
                        .onEach { if (it.hits.isEmpty()) firstEmpty.complete(Unit) }
                        .first { it.hits.isNotEmpty() }
                }
            }
            withTimeout(10_000) { firstEmpty.await() }
            dao.insertMarker(MarkerEntity("marker", base + 60_500, 1_000, 0, null))
            val updated = marked.await()
            assertEquals(110, updated.hits.size)
            assertTrue(updated.hits.all { it.isMarked })
        } finally {
            database.close()
            context.deleteSharedPreferences(preferenceName)
        }
    }
}
