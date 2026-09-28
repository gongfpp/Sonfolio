package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.processing.AudioFileAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class AudioFileAccessTest {
    @Test fun processingLeaseProtectsChunkWithoutHoldingGlobalLockAndReleasesOnCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            AudioFileAccess.processingRead("fixture") { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        withTimeout(1000) {
            AudioFileAccess.mutex.withLock {
                assertTrue(AudioFileAccess.isProcessing("fixture"))
                assertFalse(AudioFileAccess.isProcessing("unrelated"))
            }
        }
        job.cancelAndJoin()
        AudioFileAccess.mutex.withLock { assertFalse(AudioFileAccess.isProcessing("fixture")) }
    }
}
