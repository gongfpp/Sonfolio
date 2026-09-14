package com.gongfpp.sonfolio.recording

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureJournalTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun survivesRestartWithoutAnyDatabaseAndKeepsAudioUntilAcknowledged() {
        val directory = folder.newFolder("journal")
        val wav = folder.newFile("sample.wav")
        val fact = CapturedChunk("chunk-1", 1_000, wav.absolutePath, 16_000, 1)
        CaptureJournal(directory).save(fact)
        assertEquals(listOf(fact), CaptureJournal(directory).pending())
        val closed = fact.copy(endedAt = 2_000, bytes = 32_044, state = "RECORDED")
        CaptureJournal(directory).save(closed)
        assertEquals(listOf(closed), CaptureJournal(directory).pending())
        CaptureJournal(directory).acknowledge(fact.id)
        assertTrue(CaptureJournal(directory).pending().isEmpty())
        assertTrue(wav.exists())
    }

    @Test fun temporaryIncompleteWriteDoesNotReplacePreviousFact() {
        val directory = folder.newFolder("journal")
        val fact = CapturedChunk("chunk-1", 1_000, "/private/recordings/test.wav", 16_000, 1)
        CaptureJournal(directory).save(fact)
        java.io.File(directory, "chunk-1.tmp").writeText("incomplete")
        assertEquals(listOf(fact), CaptureJournal(directory).pending())
    }
}
