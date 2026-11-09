package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryQueryTest {
    @Test
    fun filtersByTitleAndFileNameCaseInsensitively() {
        val entries = listOf(
            entry("1", "Big Buck Bunny", "/videos/bunny.mp4"),
            entry("2", "Me at the zoo", "/videos/zoo.webm"),
            entry("3", "Other clip", "/videos/other.mp4"),
        )

        val byTitle = LibraryQuery.apply(entries, emptySet(), LibraryFilter(query = "BUNNY"))
        assertEquals(listOf("1"), byTitle.map { it.entry.id })

        val byFile = LibraryQuery.apply(entries, emptySet(), LibraryFilter(query = "zoo"))
        assertEquals(listOf("2"), byFile.map { it.entry.id })
    }

    @Test
    fun filtersByKindAvailabilityAndDuplicates() {
        val entries = listOf(
            entry("1", "Video A", "/a.mp4", MediaKind.VIDEO, 100),
            entry("2", "Audio A", "/a.m4a", MediaKind.AUDIO, 100),
            entry("3", "Video B", "/b.mp4", MediaKind.VIDEO, 200),
            entry("4", "Video B", "/c.mp4", MediaKind.VIDEO, 200),
        )
        val missing = setOf("3")
        val duplicates = LibraryQuery.duplicateIds(entries)
        assertEquals(setOf("3", "4"), duplicates)

        val audio = LibraryQuery.apply(entries, missing, LibraryFilter(kind = MediaKind.AUDIO))
        assertEquals(listOf("2"), audio.map { it.entry.id })

        val missingOnly = LibraryQuery.apply(entries, missing, LibraryFilter(missingOnly = true))
        assertEquals(listOf("3"), missingOnly.map { it.entry.id })
        assertTrue(missingOnly.single().missing)

        val duplicateOnly = LibraryQuery.apply(entries, missing, LibraryFilter(duplicatesOnly = true))
        assertEquals(setOf("3", "4"), duplicateOnly.map { it.entry.id }.toSet())
        assertTrue(duplicateOnly.all { it.duplicate })
    }

    @Test
    fun detectsDuplicatesByPathAndByContent() {
        val entries = listOf(
            entry("1", "Same path", "/same.mp4", size = 10),
            entry("2", "Same path again", "/same.mp4", size = 11),
            entry("3", "Content twin", "/three.mp4", size = 42),
            entry("4", "Content twin", "/four.mp4", size = 42),
            entry("5", "Unique", "/five.mp4", size = 99),
        )

        assertEquals(setOf("1", "2", "3", "4"), LibraryQuery.duplicateIds(entries))
        assertEquals(setOf("2", "4"), LibraryQuery.duplicateRemovalIds(entries))
    }

    @Test
    fun handlesTenThousandEntriesWithSearchAndDuplicates() {
        val entries = (1..10_000).map { index ->
            LibraryEntry(
                id = "id-$index",
                title = if (index % 2 == 0) "Video $index" else "Audio $index",
                kind = if (index % 2 == 0) MediaKind.VIDEO else MediaKind.AUDIO,
                filePath = "/media/$index.bin",
                sizeBytes = (index % 500).toLong(),
            )
        } + LibraryEntry(
            id = "duplicate",
            title = "Video 2",
            kind = MediaKind.VIDEO,
            filePath = "/media/2.bin",
            sizeBytes = 2,
        )
        val missing = setOf("id-1", "id-2", "id-3")

        val result = LibraryQuery.apply(
            entries = entries,
            missingIds = missing,
            filter = LibraryFilter(query = "video 9998", kind = MediaKind.VIDEO),
        )
        assertEquals(1, result.size)
        assertEquals("Video 9998", result.single().entry.title)

        val duplicateCount = LibraryQuery.duplicateIds(entries).size
        assertTrue(duplicateCount > 0)
        assertTrue(LibraryQuery.duplicateRemovalIds(entries).isNotEmpty())

        val missingResult = LibraryQuery.apply(entries, missing, LibraryFilter(missingOnly = true))
        assertEquals(3, missingResult.size)
    }

    private fun entry(
        id: String,
        title: String,
        path: String,
        kind: MediaKind = MediaKind.VIDEO,
        size: Long? = 1,
    ) = LibraryEntry(
        id = id,
        title = title,
        kind = kind,
        filePath = path,
        sizeBytes = size,
    )
}
