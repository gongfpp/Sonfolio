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

    val recordingStatus = recordingRepository.observeStatus()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecordingStatus(),
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

    fun observeSearch(
        query: String,
        dateRange: SearchDateRange = SearchDateRange.All,
        markedOnly: Boolean = false,
        visibleLimit: Int = SEARCH_BATCH_SIZE,
    ): Flow<SearchResults> =
        repository.observeSearch(query, dateRange, markedOnly, visibleLimit)

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
            // 启动恢复各步骤相互独立：一步失败（如 WorkManager 异常、坏恢复日志）只跳过自身，
            // 不允许阻断其后的队列补投递、保留策略清理与整理迁移，否则每次打开都会重复同一失败。
            // WorkManager 自行恢复被中断任务，打开页面不能重置仍在执行的任务。
            runCatching { sonfolioApplication.processingScheduler.refreshConstraints() }
                .onFailure { android.util.Log.e("SonfolioViewModel", "刷新处理约束失败", it) }
            runCatching { sonfolioApplication.summaryCoordinator.refreshConstraints() }
                .onFailure { android.util.Log.e("SonfolioViewModel", "刷新总结约束失败", it) }
            runCatching {
                recordingRepository.recoverOrphanedRunningStates()
                recordingRepository.enqueuePendingVad()
                recordingRepository.enqueuePendingAsr()
            }.onFailure { android.util.Log.e("SonfolioViewModel", "补投递处理队列失败", it) }
            // 压缩与保留策略是存储层面的后台整理；文字与总结永远不受影响。
            runCatching { recordingRepository.applyRetention() }
                .onFailure { android.util.Log.e("SonfolioViewModel", "执行保留策略失败", it) }
            runCatching {
                if (sonfolioApplication.preferences.assemblyVersion < 4) {
                    repository.rebuildFromTranscripts()
                    sonfolioApplication.preferences.completeAssemblyMigration()
                }
            }.onFailure { android.util.Log.e("SonfolioViewModel", "整理迁移失败", it) }
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

    fun updateConversationTitle(conversationId: String, title: String) {
        viewModelScope.launch { repository.updateConversationTitle(conversationId, title) }
    }

    fun resetConversationTitle(conversationId: String) {
        viewModelScope.launch { repository.resetConversationTitle(conversationId) }
    }

    fun updateConversationNote(conversationId: String, note: String?) {
        viewModelScope.launch { repository.updateConversationNote(conversationId, note) }
    }

    fun removeConversationMarker(conversationId: String) {
        viewModelScope.launch { repository.removeMarkerForConversation(conversationId) }
    }

    fun updateTranscriptText(transcriptId: String, text: String, onCandidates: (List<String>) -> Unit = {}) {
        viewModelScope.launch {
            val prompts = repository.updateTranscriptText(transcriptId, text)
            if (prompts.isNotEmpty()) onCandidates(prompts)
        }
    }

    /** 用户确认候选词加入个人词汇；确认后才会进入本地 ASR 热词。 */
    fun acceptVocabulary(term: String) {
        viewModelScope.launch { sonfolioApplication.vocabularyRepository.accept(term) }
    }

    /** 用户忽略候选词；不再提示，且不参与热词。 */
    fun ignoreVocabulary(term: String) {
        viewModelScope.launch { sonfolioApplication.vocabularyRepository.ignore(term) }
    }
}
