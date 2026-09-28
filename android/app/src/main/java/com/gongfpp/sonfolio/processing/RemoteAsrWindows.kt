package com.gongfpp.sonfolio.processing

import com.gongfpp.sonfolio.data.local.RecordingDao
import com.gongfpp.sonfolio.data.local.RemoteAsrWindowEntity
import com.gongfpp.sonfolio.data.local.SpeechSegmentEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A successful empty response is a completed request, scoped to the exact configuration. */
internal suspend fun transcribeRemoteWindows(
    dao: RecordingDao,
    chunkId: String,
    configKey: String,
    segments: List<SpeechSegmentEntity>,
    isCurrent: () -> Boolean,
    request: suspend (SpeechSegmentEntity) -> String,
): Map<String, Result<String>> {
    val cached = dao.getRemoteAsrWindows(chunkId, configKey)
        .filter { it.state == "TEXT_SUCCESS" || it.state == "EMPTY_SUCCESS" }
        .associate { it.segmentId to Result.success(it.text.orEmpty()) }
    val fetched = coroutineScope {
        val permits = Semaphore(3)
        segments.filter { it.id !in cached }.map { segment -> async {
            permits.withPermit {
                val result = try { Result.success(request(segment)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
                ensureActive()
                check(isCurrent()) { "转文字配置已改变，旧结果未应用" }
                val text = result.getOrNull()
                dao.saveRemoteAsrWindow(RemoteAsrWindowEntity(segment.id, configKey,
                    when { result.isFailure -> "FAILED"; text.isNullOrBlank() -> "EMPTY_SUCCESS"; else -> "TEXT_SUCCESS" }, text))
                segment.id to result
            }
        } }.awaitAll()
    }
    currentCoroutineContext().ensureActive()
    check(isCurrent()) { "转文字配置已改变，旧结果未应用" }
    return cached + fetched
}
