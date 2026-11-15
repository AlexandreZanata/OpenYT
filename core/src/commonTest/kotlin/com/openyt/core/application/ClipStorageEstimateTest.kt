package com.openyt.core.application

import com.openyt.core.domain.MediaSegment
import kotlin.test.*

class ClipStorageEstimateTest {
    @Test fun independentOverlappingExportsCountTwiceAndUnknownInputsStayUnknown() {
        val range = MediaSegment(1_000, 3_000)
        assertEquals(400_000L, estimateClipBytes(1_000_000, 10_000, listOf(range, range)))
        assertNull(estimateClipBytes(null, 10_000, listOf(range)))
        assertNull(estimateClipBytes(100, null, listOf(range)))
        assertNull(estimateClipBytes(100, 0, listOf(range)))
        assertNull(estimateClipBytes(100, 2_000, listOf(range)))
        val huge = MediaSegment(0, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, estimateClipBytes(Long.MAX_VALUE, Long.MAX_VALUE, List(100) { huge }))
    }
}
