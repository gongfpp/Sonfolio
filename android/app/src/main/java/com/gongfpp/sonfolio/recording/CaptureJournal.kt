package com.gongfpp.sonfolio.recording

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Small durable facts outside Room. Audio capture never awaits a database writer. */
internal data class CapturedChunk(
    val id: String, val startedAt: Long, val path: String, val sampleRate: Int, val channels: Int,
    val endedAt: Long? = null, val bytes: Long = 0, val state: String = "RECORDING", val error: String? = null,
)

internal class CaptureJournal(private val directory: File) {
    @Synchronized fun save(fact: CapturedChunk) {
        require(fact.id.matches(Regex("[a-zA-Z0-9-]+")))
        check(directory.isDirectory || directory.mkdirs()) { "无法建立录音恢复日志目录" }
        val target = File(directory, "${fact.id}.capture")
        val temporary = File(directory, "${fact.id}.tmp")
        val data = Properties().apply {
            setProperty("id", fact.id); setProperty("startedAt", fact.startedAt.toString())
            setProperty("path", fact.path); setProperty("sampleRate", fact.sampleRate.toString())
            setProperty("channels", fact.channels.toString()); setProperty("bytes", fact.bytes.toString())
            setProperty("state", fact.state)
            fact.endedAt?.let { setProperty("endedAt", it.toString()) }
            fact.error?.let { setProperty("error", it) }
        }
        FileOutputStream(temporary).use { stream -> data.store(stream, null); stream.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized fun pending(): List<CapturedChunk> = directory.listFiles().orEmpty()
        .filter { it.name.endsWith(".capture") }.map { file ->
            val data = Properties().apply { file.inputStream().use { load(it) } }
            CapturedChunk(data.getProperty("id"), data.getProperty("startedAt").toLong(), data.getProperty("path"),
                data.getProperty("sampleRate").toInt(), data.getProperty("channels").toInt(),
                data.getProperty("endedAt")?.toLong(), data.getProperty("bytes").toLong(),
                data.getProperty("state"), data.getProperty("error"))
        }.sortedBy { it.startedAt }

    /** Called only after the closed fact is committed. Never removes WAV files. */
    @Synchronized fun acknowledge(id: String) {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        check(File(directory, "$id.capture").delete()) { "录音已入库，但恢复日志尚未移除" }
    }
}
