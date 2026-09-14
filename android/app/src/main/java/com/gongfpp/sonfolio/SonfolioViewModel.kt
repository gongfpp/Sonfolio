package com.gongfpp.sonfolio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gongfpp.sonfolio.recording.RecordingController
import com.gongfpp.sonfolio.recording.RecordingExporter
import com.gongfpp.sonfolio.recording.RecordingService
import com.gongfpp.sonfolio.recording.RecordingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SonfolioViewModel(application: Application) : AndroidViewModel(application) {
    private val sonfolioApplication = application as SonfolioApplication
    private val repository = sonfolioApplication.conversationRepository
    private val recordingRepository = sonfolioApplication.recordingRepository

    val conversations = repository.observeTimeline()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val recordingStatus = recordingRepository.observeStatus()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecordingStatus(),
        )

    val recordingChunks = recordingRepository.observeChunks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val recordingFeedback = RecordingController.feedback

    fun observeTimeline(start: Long, end: Long) = repository.observeTimeline(start, end)
    fun observeChunks(start: Long, end: Long, limit: Int = Int.MAX_VALUE, includeDeleted: Boolean = true) = recordingRepository.observeChunks(start, end, limit, includeDeleted)
    fun observeConversation(id: String) = repository.observeConversation(id)
    val calendarSpans = sonfolioApplication.database.conversationDao().observeCalendarSpans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversationAliases = sonfolioApplication.database.conversationDao().observeAliases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recordingGaps = sonfolioApplication.database.recordingDao().observeGaps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeTranscript(conversationId: String): Flow<List<TranscriptLine>> =
        repository.observeTranscript(conversationId)

    fun observeSearch(query: String, filter: String = "全部", visibleLimit: Int = SEARCH_BATCH_SIZE): Flow<SearchResults> =
        repository.observeSearch(query, filter, visibleLimit)

    fun observeDailyJournal(localDate: String): Flow<com.gongfpp.sonfolio.data.local.DailyJournalEntity?> =
        repository.observeDailyJournal(localDate)

    fun observeConversationSummary(conversationId: String): Flow<com.gongfpp.sonfolio.data.local.ConversationSummaryEntity?> =
        repository.observeConversationSummary(conversationId)

    init {
        viewModelScope.launch {
            if (!RecordingService.isRunningInProcess) {
                recordingRepository.recoverDanglingChunks(skipWhenServiceRunning = true)
                if (!RecordingService.isRunningInProcess) sonfolioApplication.preferences.clearRecordingSession()
            }
            // WorkManager 自行恢复被中断任务，打开页面不能重置仍在执行的任务。
            sonfolioApplication.processingScheduler.refreshConstraints()
            sonfolioApplication.summaryCoordinator.refreshConstraints()
            recordingRepository.enqueuePendingVad()
            recordingRepository.enqueuePendingAsr()
            if (sonfolioApplication.preferences.assemblyVersion < 4) {
                repository.rebuildFromTranscripts()
                sonfolioApplication.preferences.completeAssemblyMigration()
            }
            sonfolioApplication.database.conversationDao().deleteDemoConversations()
        }
    }

    fun startRecording() {
        RecordingController.start(getApplication())
    }

    fun stopRecording() {
        RecordingController.stop(getApplication())
    }

    /** 录音异常退出后，用户选择重新尝试采集。 */
    fun recoverRecording() {
        RecordingController.start(getApplication())
    }

    /** 录音已中断且用户决定不再尝试，主动结束并记录缺口结果。 */
    fun endInterruptedRecording() {
        viewModelScope.launch { recordingRepository.endInterruptedSession() }
    }

    /** 批量删除原始录音切片，protectMarked 为真时跳过被标记保护的片段。 */
    suspend fun deleteChunks(ids: Set<String>, protectMarked: Boolean): String =
        recordingRepository.deleteChunks(ids, protectMarked)

    /** 把选中的切片连同转写打包导出为 zip 到用户指定位置。 */
    suspend fun exportChunksZip(uri: Uri, ids: Set<String>) {
            val items = recordingRepository.collectExportItems(ids)
            RecordingExporter.exportToZip(getApplication(), uri, items)
    }

    fun rebuildConversations() {
        viewModelScope.launch { repository.rebuildFromTranscripts() }
    }

    fun retryProcessing(chunkId: String) {
        viewModelScope.launch { recordingRepository.retryProcessing(chunkId) }
    }

    fun markCurrentMoment(windowMinutes: Int = 3) {
        RecordingController.mark(getApplication(), windowMinutes)
    }
}
