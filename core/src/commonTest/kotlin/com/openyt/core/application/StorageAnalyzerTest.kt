package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageAnalyzerTest {
    @Test
    fun windowsSeparatorsAndCaseDoNotCreateFalseOrphans() {
        val entries = listOf(
            LibraryEntry("a", "Curso Básico", MediaKind.VIDEO, "C:\\Users\\João\\Downloads\\Curso.mkv", 100),
        )
        val files = listOf(
            ScannedMedia("c:/users/joão/downloads/curso.mkv", "Curso", MediaKind.VIDEO, 100),
        )
        assertTrue(StorageAnalyzer.analyze(entries, emptySet(), files).orphanFiles.isEmpty())
    }

    @Test
    fun posixPathsRemainCaseSensitive() {
        val entries = listOf(LibraryEntry("a", "Movie", MediaKind.VIDEO, "/media/Video.mp4", 100))
        val files = listOf(ScannedMedia("/media/video.mp4", "Movie", MediaKind.VIDEO, 100))
        assertEquals(1, StorageAnalyzer.analyze(entries, emptySet(), files).orphanFiles.size)
    }

    @Test
    fun summarizesTotalsLargestDuplicatesMissingAndOrphans() {
        val entries = listOf(
            LibraryEntry("a", "Movie", MediaKind.VIDEO, "/media/dup.mp4", 100),
            LibraryEntry("b", "Movie copy", MediaKind.VIDEO, "/media/dup.mp4", 100),
            LibraryEntry("c", "Song", MediaKind.AUDIO, "/media/song.m4a", 50),
            LibraryEntry("d", "Missing", MediaKind.VIDEO, "/media/missing.mp4", 70),
        )
        val scanned = listOf(
            ScannedMedia("/media/dup.mp4", "dup", MediaKind.VIDEO, 100),
            ScannedMedia("/media/song.m4a", "song", MediaKind.AUDIO, 50),
            ScannedMedia("/media/orphan.mp4", "orphan", MediaKind.VIDEO, 30),
        )

        val report = StorageAnalyzer.analyze(entries, setOf("d"), scanned)

        assertEquals(4, report.totalEntries)
        assertEquals(320L, report.totalBytes)
        assertEquals(listOf("a", "b"), report.largest.take(2).map { it.id })
        assertEquals(1, report.duplicateGroups.size)
        assertEquals(setOf("a", "b"), report.duplicateGroups.single().entries.map { it.id }.toSet())
        assertEquals(listOf("d"), report.missing.map { it.id })
        assertEquals(listOf("/media/orphan.mp4"), report.orphanFiles.map { it.path })
    }

    @Test
    fun emptyLibraryHasNoGroupsOrOrphans() {
        val report = StorageAnalyzer.analyze(emptyList(), emptySet(), emptyList())
        assertEquals(0, report.totalEntries)
        assertEquals(0L, report.totalBytes)
        assertTrue(report.duplicateGroups.isEmpty())
        assertTrue(report.missing.isEmpty())
        assertTrue(report.orphanFiles.isEmpty())
    }
}
