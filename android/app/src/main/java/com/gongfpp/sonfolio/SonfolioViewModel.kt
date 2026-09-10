package com.gongfpp.sonfolio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gongfpp.sonfolio.recording.RecordingController
import com.gongfpp.sonfolio.recording.RecordingService
import com.gongfpp.sonfolio.recording.RecordingStatus
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

    val recordingFeedback = RecordingController.feedback

    init {
        viewModelScope.launch {
            if (!RecordingService.isRunningInProcess) {
                recordingRepository.recoverDanglingChunks()
            }
            repository.seedDemoDataIfEmpty()
        }
    }

    fun startRecording() {
        RecordingController.start(getApplication())
    }

    fun stopRecording() {
        RecordingController.stop(getApplication())
    }

    fun markCurrentMoment() {
        RecordingController.mark(getApplication())
    }
}
