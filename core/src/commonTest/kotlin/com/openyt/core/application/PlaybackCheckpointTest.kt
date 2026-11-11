package com.openyt.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackCheckpointTest {
    @Test
    fun resumesInsideTheFileAndRestartsCompletedItems() {
        assertEquals(10_000, PlaybackCheckpoint(10_000, 60_000).resumeMillis)
        assertEquals(0, PlaybackCheckpoint(-1, 60_000).resumeMillis)
        assertEquals(0, PlaybackCheckpoint(59_000, 60_000).resumeMillis)
        assertEquals(0, PlaybackCheckpoint(70_000, 60_000).resumeMillis)
    }
    @Test
    fun miniWindowDimensionsHandlePortraitAndInvalidRatios() {
        for (ratio in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY, 0.5, 16.0 / 9.0, 100.0)) {
            val size = miniPlayerSize(ratio)
            assertEquals(420, size.width)
            assertTrue(size.height in 223..888)
        }
    }
}
