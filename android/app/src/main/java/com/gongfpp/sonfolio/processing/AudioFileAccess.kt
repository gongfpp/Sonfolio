package com.gongfpp.sonfolio.processing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Serializes consumers and explicit cleanup, never the AudioRecord capture loop. */
internal object AudioFileAccess {
    val mutex = Mutex()
    private val inferenceBudget = Mutex()
    enum class Reader(val label: String) { PROCESSING("正在识别"), EXPORT("正在导出"), BACKUP("正在备份"), PLAYBACK("正在播放") }
    private val readers = mutableMapOf<String, MutableMap<Reader, Int>>()

    /** Called while holding mutex, so cleanup and lease acquisition are atomic. */
    fun isProcessing(chunkId: String): Boolean = chunkId in readers
    fun busyReason(chunkId: String): String? = readers[chunkId]?.keys?.joinToString("、") { it.label }

    suspend fun <T> processingRead(chunkId: String, block: suspend () -> T): T = inferenceBudget.withLock {
        read(setOf(chunkId), Reader.PROCESSING, block)
    }

    /** Shared per-recording lease; unrelated cleanup and readers do not wait for network/ZIP I/O. */
    suspend fun <T> read(chunkIds: Set<String>, reader: Reader, block: suspend () -> T): T {
        mutex.withLock {
            chunkIds.forEach { id ->
                val counts = readers.getOrPut(id) { mutableMapOf() }
                counts[reader] = (counts[reader] ?: 0) + 1
            }
        }
        try { return block() }
        finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    chunkIds.forEach { id ->
                        val counts = readers.getValue(id)
                        val remaining = counts.getValue(reader) - 1
                        if (remaining == 0) counts.remove(reader) else counts[reader] = remaining
                        if (counts.isEmpty()) readers.remove(id)
                    }
                }
            }
        }
    }
}
