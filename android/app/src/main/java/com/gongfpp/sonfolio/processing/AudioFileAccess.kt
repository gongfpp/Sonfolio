package com.gongfpp.sonfolio.processing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Serializes consumers and explicit cleanup, never the AudioRecord capture loop. */
internal object AudioFileAccess {
    val mutex = Mutex()
    private val inferenceBudget = Mutex()
    private val readers = mutableSetOf<String>()

    /** Called while holding mutex, so cleanup and lease acquisition are atomic. */
    fun isProcessing(chunkId: String): Boolean = chunkId in readers

    suspend fun <T> processingRead(chunkId: String, block: suspend () -> T): T = inferenceBudget.withLock {
        mutex.withLock { check(readers.add(chunkId)) }
        try { block() }
        finally { withContext(NonCancellable) { mutex.withLock { readers.remove(chunkId) } } }
    }
}
