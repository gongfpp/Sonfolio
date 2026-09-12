package com.gongfpp.sonfolio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gongfpp.sonfolio.recording.RecordingController
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
            recordingRepository.enqueuePendingVad()
            recordingRepository.enqueuePendingAsr()
            repository.rebuildFromTranscripts()
            sonfolioApplication.database.conversationDao().deleteDemoConversations()
        }
    }

    fun startRecording() {
        RecordingController.start(getApplication())
    }

    fun stopRecording() {
        RecordingController.stop(getApplication())
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
