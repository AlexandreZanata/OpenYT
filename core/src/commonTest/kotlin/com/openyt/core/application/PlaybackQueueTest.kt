package com.openyt.core.application

import com.openyt.core.domain.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQueueTest {
    private val items = listOf(
        PlayableMedia("/a.mp4", "A", MediaKind.VIDEO),
        PlayableMedia("/b.mp4", "B", MediaKind.VIDEO),
        PlayableMedia("/c.mp4", "C", MediaKind.VIDEO),
    )

    @Test
    fun movesForwardAndStopsAtTheEnd() {
        var queue = PlaybackQueue.of(items, 0)
        assertEquals("A", queue.current?.title)
        queue = queue.next()
        assertEquals("B", queue.current?.title)
        queue = queue.next()
        assertEquals("C", queue.current?.title)
        queue = queue.next()
        assertEquals("C", queue.current?.title)
        assertFalse(queue.hasNext)
    }

    @Test
    fun movesBackwardAndStopsAtTheStart() {
        var queue = PlaybackQueue.of(items, 2)
        queue = queue.previous()
        assertEquals("B", queue.current?.title)
        queue = queue.previous()
        assertEquals("A", queue.current?.title)
        queue = queue.previous()
        assertEquals("A", queue.current?.title)
        assertFalse(queue.hasPrevious)
    }

    @Test
    fun handlesEmptyAndOutOfRangeSelections() {
        val empty = PlaybackQueue.of(emptyList(), 4)
        assertNull(empty.current)
        assertFalse(empty.hasNext)
        assertFalse(empty.hasPrevious)

        val queue = PlaybackQueue.of(items, 99)
        assertEquals("C", queue.current?.title)
        assertEquals("B", queue.select(1).current?.title)
        assertTrue(queue.select(99).current?.title == "C")
    }
}
