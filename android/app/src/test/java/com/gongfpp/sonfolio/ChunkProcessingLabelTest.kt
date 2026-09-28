package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.processing.ChunkProcessing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkProcessingLabelTest {
    @Test fun waitingWithoutAnActiveWorkerHasANextStageLabel() {
        assertEquals("② 录音已保存，等待找人声", ChunkProcessing.stagedLabelOf(ChunkProcessing.RECORDED))
        assertEquals("② 录音已保存，等待找人声", ChunkProcessing.stagedLabelOf(ChunkProcessing.RECOVERED))
        assertEquals("③ 人声已找到，等待转写", ChunkProcessing.stagedLabelOf(ChunkProcessing.VAD_READY))
    }

    @Test fun allActionableAndRunningStatesCanRender() {
        (ChunkProcessing.actionableStates + ChunkProcessing.runningStates + ChunkProcessing.assembledStates).forEach {
            assertTrue(ChunkProcessing.stagedLabelOf(it).isNotBlank())
        }
    }
}
