package com.gongfpp.sonfolio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimelineTest {
    private val timeline = PlaybackTimeline(listOf(
        PlaybackSlice("a.wav", 0, 60_000, 300_000),
        PlaybackSlice("b.wav", 300_000, 300_000, 360_000),
    ))

    @Test fun mapsConversationAndFileTimeWithoutDoubleOffset() {
        assertEquals(60_000L, timeline.locate(0)!!.fileOffset)
        assertEquals(0L, timeline.position(0, 60_000))
        assertEquals(30_000L, timeline.locate(270_000)!!.fileOffset)
        assertEquals(270_000L, timeline.position(1, 30_000))
    }

    @Test fun crossesChunkBoundaryOnceAndClampsEnd() {
        assertEquals(1, timeline.locate(240_000)!!.index)
        assertEquals(0L, timeline.locate(240_000)!!.fileOffset)
        assertEquals(300_000L, timeline.locate(999_999)!!.position)
    }

    @Test fun seeksAcrossMissingAudioToNextAvailableSlice() {
        val gap = PlaybackTimeline(listOf(PlaybackSlice("a", 0, 0, 10_000), PlaybackSlice("b", 20_000, 20_000, 30_000)))
        assertEquals(20_000L, gap.locate(15_000)!!.position)
    }
}
