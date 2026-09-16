package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.processing.ChunkProcessing
import org.junit.Assert.*
import org.junit.Test

/** 处理状态只有一个来源：阶段编号、失败判定和可重试集合都在 ChunkProcessing。 */
class ChunkProcessingTest {
    @Test fun stageProgressFollowsTheFourStepModel() {
        assertEquals(ChunkProcessing.StageProgress(0, 1, false), ChunkProcessing.progressOf(ChunkProcessing.RECORDING))
        assertEquals(ChunkProcessing.StageProgress(1, null, false), ChunkProcessing.progressOf(ChunkProcessing.RECORDED))
        assertEquals(2, ChunkProcessing.progressOf(ChunkProcessing.VAD_READY).completed)
        assertNull(ChunkProcessing.progressOf(ChunkProcessing.VAD_READY).active)
        assertEquals(ChunkProcessing.StageProgress(2, 3, false), ChunkProcessing.progressOf(ChunkProcessing.ASR_RUNNING))
        assertEquals(3, ChunkProcessing.progressOf(ChunkProcessing.ASSEMBLY_PENDING).completed)
        assertEquals(4, ChunkProcessing.progressOf(ChunkProcessing.ASSEMBLY_PENDING).active)
        assertTrue(ChunkProcessing.progressOf(ChunkProcessing.ASR_READY).isDone)
        assertTrue(ChunkProcessing.progressOf(ChunkProcessing.AUDIO_DELETED).isDone)
    }

    @Test fun failuresStayOnTheStageThatFailed() {
        assertTrue(ChunkProcessing.progressOf(ChunkProcessing.VAD_FAILED).failed)
        assertEquals(ChunkProcessing.STAGE_VOICE, ChunkProcessing.progressOf(ChunkProcessing.VAD_FAILED).active)
        assertTrue(ChunkProcessing.progressOf(ChunkProcessing.ASR_FAILED).failed)
        assertEquals(ChunkProcessing.STAGE_TRANSCRIBE, ChunkProcessing.progressOf(ChunkProcessing.ASR_FAILED).active)
        assertTrue(ChunkProcessing.progressOf(ChunkProcessing.ASSEMBLY_FAILED).failed)
        assertEquals(ChunkProcessing.STAGE_ASSEMBLE, ChunkProcessing.progressOf(ChunkProcessing.ASSEMBLY_FAILED).active)
    }

    @Test fun actionableStatesExcludeTasksThatWorkManagerResumesItself() {
        assertFalse(ChunkProcessing.isActionable(ChunkProcessing.VAD_RUNNING))
        assertFalse(ChunkProcessing.isActionable(ChunkProcessing.ASR_RUNNING))
        assertFalse(ChunkProcessing.isActionable(ChunkProcessing.RECORDING))
        assertFalse(ChunkProcessing.isActionable(ChunkProcessing.ASR_READY))
        assertFalse(ChunkProcessing.isActionable(ChunkProcessing.AUDIO_DELETED))
        assertTrue(ChunkProcessing.isActionable(ChunkProcessing.RECORDED))
        assertTrue(ChunkProcessing.isActionable(ChunkProcessing.VAD_READY))
        assertTrue(ChunkProcessing.isActionable(ChunkProcessing.ASR_FAILED))
        assertTrue(ChunkProcessing.isActionable(ChunkProcessing.ASSEMBLY_FAILED))
    }
}
