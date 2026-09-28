package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.processing.AudioFileAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class AudioFileAccessTest {
    @Test fun sharedReadersProtectUntilLastReaderExitsAndKeepUnrelatedFilesAvailable() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val export = launch {
            AudioFileAccess.read(setOf("same"), AudioFileAccess.Reader.EXPORT) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        AudioFileAccess.read(setOf("same"), AudioFileAccess.Reader.PLAYBACK) {
            AudioFileAccess.mutex.withLock {
                assertEquals("正在导出、正在播放", AudioFileAccess.busyReason("same"))
                assertFalse(AudioFileAccess.isProcessing("unrelated"))
            }
            export.cancelAndJoin()
            AudioFileAccess.mutex.withLock { assertEquals("正在播放", AudioFileAccess.busyReason("same")) }
        }
        AudioFileAccess.mutex.withLock { assertNull(AudioFileAccess.busyReason("same")) }
    }
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
