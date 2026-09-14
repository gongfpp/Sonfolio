package com.gongfpp.sonfolio

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.gongfpp.sonfolio.data.local.*
import com.gongfpp.sonfolio.recording.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupIntegrationTest {
    @Test fun completeRoundTripKeepsAudioTextMarkersAndRefusesOverwrite() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "backup-test-").toFile()
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val source = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val fixtureMutex = kotlinx.coroutines.sync.Mutex()
        try {
            val file = File(root, "source.wav")
            WavChunkWriter(file, 16_000, 1).use { it.write(ByteArray(32_000), 32_000) }
            val audio = file.readBytes()
            source.recordingDao().insertChunk(AudioChunkEntity("backup-test", 1_000, 2_000, file.path, file.length(), 16_000, 1, "ASR_READY", null))
            source.recordingDao().insertSpeechSegments(listOf(SpeechSegmentEntity("speech", "backup-test", 0, 1_000, 1f, "ASR_READY")))
            source.recordingDao().insertTranscript(TranscriptEntity("text", "speech", null, 1_000, 2_000, "备份测试资料", "zh", "test", "test", "ASR_READY", null))
            source.recordingDao().insertMarker(MarkerEntity("mark", 1_500, 1_000, 0, null))
            val archive = Uri.fromFile(File(root, "complete.zip"))
            MemoryBackup(context, source, fixtureMutex).export(archive)
            MemoryBackup(context, target, fixtureMutex).restore(archive)
            val restored = target.recordingDao().getChunk("backup-test")!!
            assertNotEquals(file.path, restored.localPath)
            assertArrayEquals(audio, File(restored.localPath).readBytes())
            assertEquals("备份测试资料", target.conversationDao().getReadyTranscriptRows().single().text)
            assertEquals("mark", target.conversationDao().getMarkers().single().id)
            assertTrue(runCatching { MemoryBackup(context, target, fixtureMutex).restore(archive) }.isFailure)
            assertArrayEquals(audio, File(restored.localPath).readBytes())
        } finally { source.close(); target.close(); root.deleteRecursively() }
    }
}
