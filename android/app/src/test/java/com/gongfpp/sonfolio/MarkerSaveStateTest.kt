package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.recording.*
import org.junit.Assert.*
import org.junit.Test

class MarkerSaveStateTest {
    @Test fun onlyMatchingPersistedReceiptCompletesPendingMark() {
        val pending = MarkerSaveState.Saving("request", 3)
        assertEquals(pending, markerResult(pending, RecordingFeedback.Failed("录音失败")))
        assertEquals(pending, markerResult(pending, RecordingFeedback.Marked(1, 20, "another")))
        assertEquals(MarkerSaveState.Saved("request", 2, 3), markerResult(pending, RecordingFeedback.Marked(2, 3, "request")))
        assertEquals(MarkerSaveState.Failed("request", "未保存"), markerResult(pending, RecordingFeedback.Failed("未保存", "request")))
    }
}
