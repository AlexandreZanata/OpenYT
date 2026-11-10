package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BulkFilePlannerTest {
    @Test
    fun plansRenamesAndAppendsOriginalExtension() {
        val entries = listOf(
            LibraryEntry("a", "First", MediaKind.VIDEO, "/media/a.mp4", 1),
            LibraryEntry("b", "Second", MediaKind.AUDIO, "/media/b.m4a", 1, author = "Artist"),
        )

        val plan = BulkFilePlanner.planRename(entries, "{index} - {title}") { false }

        assertTrue(plan.canApply)
        assertEquals(listOf("/media/1 - First.mp4", "/media/2 - Second.m4a"), plan.changes.map { it.toPath })
    }

    @Test
    fun detectsExistingAndDuplicateTargets() {
        val entries = listOf(
            LibraryEntry("a", "Same", MediaKind.VIDEO, "/media/a.mp4", 1),
            LibraryEntry("b", "Same", MediaKind.VIDEO, "/media/b.mp4", 1),
        )

        val renamed = BulkFilePlanner.planRename(entries, "{title}") { false }
        assertFalse(renamed.canApply)
        assertTrue(renamed.conflicts.any { it.contains("duplicate target") })

        val moved = BulkFilePlanner.planMove(entries, "/dest") { it == "/dest/a.mp4" }
        assertFalse(moved.canApply)
        assertTrue(moved.conflicts.any { it.contains("target exists") })
    }

    @Test
    fun plansMovesKeepingFileName() {
        val entries = listOf(LibraryEntry("a", "First", MediaKind.VIDEO, "/media/a.mp4", 1))
        val plan = BulkFilePlanner.planMove(entries, "/dest/") { false }
        assertEquals("/dest/a.mp4", plan.changes.single().toPath)
    }
}
