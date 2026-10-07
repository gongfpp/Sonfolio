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
    /** Fixed manifests reconstructed from the checked-in historical schemas, NOT rewritten current exports. */
    @Test fun historicalSchemaFixturesPreserveNotesTitlesRelationsAndAudio() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (version in listOf(5, 9, 10)) {
            val root = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "qa-historical-backup-").toFile()
            val context = object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = root }
            val target = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
            val second = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
            try {
                val wav = File(root, "fixture.wav")
                WavChunkWriter(wav, 16000, 1).use { it.write(ByteArray(32000), 32000) }
                val bytes = wav.readBytes()
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val manifest = instrumentation.context.assets.open("backup-fixtures/schema-$version.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
                manifest.getJSONObject("tables").getJSONArray("audio_chunks").getJSONObject(0).put("backupSha256", hash)
                val archive = File(root, "historical.zip")
                java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
                    zip.putNextEntry(java.util.zip.ZipEntry("audio/legacy-audio.wav")); zip.write(bytes); zip.closeEntry()
                }
                MemoryBackup(context, target, kotlinx.coroutines.sync.Mutex()).restore(Uri.fromFile(archive))
                val conversation = target.conversationDao().getConversation("legacy-conv")!!
                assertEquals("旧标题", conversation.generatedTitle)
                if (version >= 6) assertEquals("手动标题", conversation.titleOverride)
                assertEquals("历史备份验收文字", target.conversationDao().getReadyRowsInWindow(0, 3000).single().text)
                assertEquals("legacy-marker", target.conversationDao().getMarkers().single().id)
                assertArrayEquals(bytes, File(target.recordingDao().getChunk("legacy-audio")!!.localPath).readBytes())
                val current = File(root, "current.zip")
                MemoryBackup(context, target, kotlinx.coroutines.sync.Mutex()).export(Uri.fromFile(current))
                MemoryBackup(context, second, kotlinx.coroutines.sync.Mutex()).restore(Uri.fromFile(current))
                second.openHelper.readableDatabase.query("SELECT body FROM legacy_notes WHERE conversationId = 'legacy-conv'").use {
                    assertTrue(it.moveToFirst()); assertEquals("旧版非空备注：请保留这段内容。", it.getString(0))
                }
            } finally { target.close(); second.close(); root.deleteRecursively() }
        }
    }
    @Test fun explicitlyDeletedAudioStillExportsTextWithoutPretendingAudioExists() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "qa-text-export-").toFile()
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        try {
            val archive = File(root, "text.zip")
            RecordingExporter.exportToZip(context, Uri.fromFile(archive), listOf(ChunkExport("deleted", 0, 1000, "", listOf("保留的文字"), audioDeleted = true)))
            java.util.zip.ZipFile(archive).use { zip ->
                assertEquals(1, zip.size())
                val text = zip.getInputStream(zip.getEntry("transcript/deleted.txt")).bufferedReader().readText()
                assertTrue(text.contains("已清理，仅导出文字"))
                assertTrue(text.contains("保留的文字"))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun bothContainersExportCorrectlyAndRestoreDiscardsBothInjectedPaths() = runBlocking {
      for (extension in listOf("wav", "m4a")) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "qa-backup-resource-").toFile()
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val source = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        try {
            val recordings = File(root, "recordings").apply { mkdirs() }
            val audio = File(recordings, "audio.$extension").apply { writeBytes(ByteArray(64) { 7 }) }
            val wavPath = if (extension == "wav") audio.path else ""
            val compressedPath = if (extension == "m4a") audio.path else null
            val outside = File(root, "not-a-recording").apply { writeText("keep") }
            source.recordingDao().insertChunk(AudioChunkEntity("aac-only", 0, 1000, wavPath, if (extension == "wav") audio.length() else 0, 16000, 1, "ASR_READY", null, compressedPath = compressedPath, compressedBytes = if (extension == "m4a") audio.length() else null))
            val ordinary = File(root, "export.zip")
            RecordingExporter.exportToZip(context, Uri.fromFile(ordinary), listOf(ChunkExport("aac-only", 0, 1000, wavPath, listOf("文字"), compressedPath)))
            java.util.zip.ZipFile(ordinary).use { zip ->
                assertNotNull(zip.getEntry("audio/aac-only.$extension"))
                assertNull(zip.getEntry("audio/aac-only.${if (extension == "wav") "m4a" else "wav"}"))
            }
            val archive = File(root, "backup.zip")
            MemoryBackup(context, source, kotlinx.coroutines.sync.Mutex()).export(Uri.fromFile(archive))
            val injected = File(root, "injected.zip")
            java.util.zip.ZipInputStream(archive.inputStream()).use { input ->
                java.util.zip.ZipOutputStream(injected.outputStream()).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val bytes = input.readBytes()
                        output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        if (entry.name == "manifest.json") {
                            val manifest = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
                            manifest.getJSONObject("tables").getJSONArray("audio_chunks").getJSONObject(0)
                                .put("localPath", outside.path).put("compressedPath", outside.path)
                            output.write(manifest.toString().toByteArray())
                        } else output.write(bytes)
                        output.closeEntry()
                    }
                }
            }
            MemoryBackup(context, target, kotlinx.coroutines.sync.Mutex()).restore(Uri.fromFile(injected))
            val restored = target.recordingDao().getChunk("aac-only")!!
            if (extension == "wav") assertNull(restored.compressedPath) else assertEquals("", restored.localPath)
            val restoredFile = File(if (extension == "wav") restored.localPath else restored.compressedPath!!)
            assertTrue(restoredFile.canonicalPath.startsWith(recordings.canonicalPath + "/restore-"))
            assertArrayEquals(audio.readBytes(), restoredFile.readBytes())
            assertEquals("keep", outside.readText())
        } finally { source.close(); target.close(); root.deleteRecursively() }
      }
    }

    @Test fun completeRoundTripKeepsAudioTextMarkersAndRefusesOverwrite() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "backup-test-").toFile()
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val source = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val fixtureMutex = kotlinx.coroutines.sync.Mutex()
        try {
            val file = File(File(root, "recordings").apply { mkdirs() }, "source.wav")
            WavChunkWriter(file, 16_000, 1).use { it.write(ByteArray(32_000), 32_000) }
            val audio = file.readBytes()
            source.recordingDao().insertChunk(AudioChunkEntity("backup-test", 1_000, 2_000, file.path, file.length(), 16_000, 1, "CORRECTION_RUNNING", null))
            source.recordingDao().insertSpeechSegments(listOf(SpeechSegmentEntity("speech", "backup-test", 0, 1_000, 1f, "ASR_READY")))
            source.recordingDao().insertTranscript(TranscriptEntity("text", "speech", null, 1_000, 2_000, "备份测试资料", "zh", "test", "test", "ASR_READY", null))
            source.recordingDao().insertMarker(MarkerEntity("mark", 1_500, 1_000, 0, null))
            val archive = Uri.fromFile(File(root, "complete.zip"))
            MemoryBackup(context, source, fixtureMutex).export(archive)
            MemoryBackup(context, target, fixtureMutex).restore(archive)
            val restored = target.recordingDao().getChunk("backup-test")!!
            assertEquals("CORRECTION_PENDING", restored.processingState)
            assertNotEquals(file.path, restored.localPath)
            assertArrayEquals(audio, File(restored.localPath).readBytes())
            assertEquals("备份测试资料", target.conversationDao().getReadyRowsInWindow(Long.MIN_VALUE, Long.MAX_VALUE).single().text)
            assertEquals("mark", target.conversationDao().getMarkers().single().id)
            assertTrue(runCatching { MemoryBackup(context, target, fixtureMutex).restore(archive) }.isFailure)
            assertArrayEquals(audio, File(restored.localPath).readBytes())
        } finally { source.close(); target.close(); root.deleteRecursively() }
    }

    /** 旧版（manifest version=1/schema=4）备份恢复到当前 schema：缺列补默认值，列名迁移生效。 */
    @Test fun legacyVersionOneBackupRestoresIntoCurrentSchema() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(base.cacheDir.toPath(), "backup-legacy-").toFile()
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val source = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
        val fixtureMutex = kotlinx.coroutines.sync.Mutex()
        try {
            val file = File(File(root, "recordings").apply { mkdirs() }, "legacy.wav")
            WavChunkWriter(file, 16_000, 1).use { it.write(ByteArray(32_000), 32_000) }
            source.recordingDao().insertChunk(AudioChunkEntity("legacy-audio", 1_000, 2_000, file.path, file.length(), 16_000, 1, "ASR_READY", null))
            source.recordingDao().insertSpeechSegments(listOf(SpeechSegmentEntity("legacy-speech", "legacy-audio", 0, 1_000, 1f, "ASR_READY")))
            source.recordingDao().insertTranscript(TranscriptEntity("legacy-text", "legacy-speech", null, 1_000, 2_000, "旧版本备份资料", "zh", "test", "test", "ASR_READY", null))
            source.conversationDao().insertAll(listOf(ConversationEntity("legacy-conv", "Unknown", 1_000, 2_000, "Asia/Shanghai", "手改标题", null, "自动摘要", "BRIEF")))
            val archive = Uri.fromFile(File(root, "current.zip"))
            MemoryBackup(context, source, fixtureMutex).export(archive)

            // 把当前导出改写成 formatVersion=1/schema=4 形态：列名回退、去掉新列。
            val legacy = File(root, "legacy.zip")
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(File(root, "current.zip")))).use { input ->
                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(legacy))).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val content = input.readBytes()
                        if (entry.name == "manifest.json") {
                            val manifest = org.json.JSONObject(content.toString(Charsets.UTF_8))
                            manifest.remove("formatVersion")
                            manifest.remove("databaseSchema")
                            manifest.remove("appVersion")
                            manifest.put("version", 1).put("schema", 4)
                            manifest.getJSONObject("tables").remove("personal_vocabulary")
                            manifest.getJSONObject("tables").remove("legacy_notes")
                            val conversations = manifest.getJSONObject("tables").getJSONArray("conversations")
                            for (index in 0 until conversations.length()) {
                                val row = conversations.getJSONObject(index)
                                row.put("title", row.getString("generatedTitle"))
                                row.remove("generatedTitle")
                                row.remove("titleOverride")
                                row.put("note", "旧版保留的备注")
                            }
                            val transcripts = manifest.getJSONObject("tables").getJSONArray("transcripts")
                            for (index in 0 until transcripts.length()) transcripts.getJSONObject(index).remove("originalText")
                            output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            output.write(manifest.toString().toByteArray(Charsets.UTF_8))
                        } else {
                            output.putNextEntry(java.util.zip.ZipEntry(entry.name)); output.write(content)
                        }
                        output.closeEntry()
                    }
                }
            }
            MemoryBackup(context, target, fixtureMutex).restore(Uri.fromFile(legacy))
            val conversation = target.conversationDao().getConversation("legacy-conv")!!
            assertEquals("手改标题", conversation.generatedTitle)
            target.openHelper.readableDatabase.query("SELECT body FROM legacy_notes WHERE conversationId = 'legacy-conv'").use {
                assertTrue(it.moveToFirst()); assertEquals("旧版保留的备注", it.getString(0))
            }
            assertNull(conversation.titleOverride)
            assertNull(target.conversationDao().getReadyRowsInWindow(Long.MIN_VALUE, Long.MAX_VALUE).single().originalText)
            assertEquals("旧版本备份资料", target.conversationDao().getReadyRowsInWindow(Long.MIN_VALUE, Long.MAX_VALUE).single().text)

            // 缺失非空且无默认值的列仍然拒绝，不静默导入残缺数据。
            source.conversationDao().insertAll(listOf(ConversationEntity("second", "Unknown", 3_000, 4_000, "Asia/Shanghai", "标题", null, "摘要", "BRIEF")))
            val invalid = File(root, "invalid.zip")
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(File(root, "current.zip")))).use { input ->
                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(invalid))).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val content = input.readBytes()
                        if (entry.name == "manifest.json") {
                            val manifest = org.json.JSONObject(content.toString(Charsets.UTF_8))
                            val conversations = manifest.getJSONObject("tables").getJSONArray("conversations")
                            for (index in 0 until conversations.length()) conversations.getJSONObject(index).remove("briefSummary")
                            output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            output.write(manifest.toString().toByteArray(Charsets.UTF_8))
                        } else {
                            output.putNextEntry(java.util.zip.ZipEntry(entry.name)); output.write(content)
                        }
                        output.closeEntry()
                    }
                }
            }
            val fresh = Room.inMemoryDatabaseBuilder(context, SonfolioDatabase::class.java).build()
            try {
                assertTrue(runCatching { MemoryBackup(context, fresh, fixtureMutex).restore(Uri.fromFile(invalid)) }.isFailure)
            } finally { fresh.close() }
        } finally { source.close(); target.close(); root.deleteRecursively() }
    }
}
