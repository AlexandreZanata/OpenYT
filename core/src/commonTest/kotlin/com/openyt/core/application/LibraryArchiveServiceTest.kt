package com.openyt.core.application

import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.SmartCollection
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryArchiveServiceTest {
    @Test
    fun roundTripsEntriesCollectionsMembershipAndSmartCollections() = runBlocking {
        val source = FakeRepository()
        source.library += LibraryEntry(
            id = "entry-1",
            title = "Clip",
            kind = MediaKind.AUDIO,
            filePath = "/media/clip.m4a",
            author = "Artist",
            tags = listOf("music"),
        )
        source.collections += Collection("collection-1", "Favorites")
        source.membership["collection-1"] = mutableSetOf("entry-1")
        source.smart += SmartCollection("smart-1", "Music", tag = "music")

        val archive = LibraryArchiveService(source).export()
        val decoded = LibraryArchiveCodec.decode(LibraryArchiveCodec.encode(archive)).getOrThrow()

        val target = FakeRepository()
        val result = LibraryArchiveService(target).import(decoded)

        assertEquals(1, result.entriesImported)
        assertEquals(1, result.collectionsImported)
        assertEquals(1, result.smartCollectionsImported)
        assertEquals(source.library, target.library)
        assertEquals(source.collections, target.collections)
        assertEquals(source.membership, target.membership)
        assertEquals(source.smart, target.smart)
    }

    @Test
    fun mergeKeepsLocalPathAndMergesTags() = runBlocking {
        val target = FakeRepository()
        target.library += LibraryEntry(
            id = "entry-1",
            title = "Old",
            kind = MediaKind.AUDIO,
            filePath = "/local/clip.m4a",
            tags = listOf("local"),
        )
        val archive = LibraryArchive(
            entries = listOf(
                LibraryEntryRecord(
                    id = "entry-1",
                    title = "New",
                    kind = "AUDIO",
                    filePath = "/archive/clip.m4a",
                    tags = listOf("archive"),
                ),
            ),
        )

        val result = LibraryArchiveService(target).import(archive, LibraryImportPolicy.MERGE)

        val entry = target.library.single()
        assertEquals(1, result.entriesUpdated)
        assertEquals("New", entry.title)
        assertEquals("/local/clip.m4a", entry.filePath)
        assertEquals(listOf("local", "archive"), entry.tags)
    }

    @Test
    fun replaceUsesArchivePathAndImportsMissingFiles() = runBlocking {
        val target = FakeRepository()
        target.library += LibraryEntry(
            id = "entry-1",
            title = "Old",
            kind = MediaKind.VIDEO,
            filePath = "/local/old.mp4",
        )
        val archive = LibraryArchive(
            entries = listOf(
                LibraryEntryRecord(
                    id = "entry-1",
                    title = "New",
                    kind = "VIDEO",
                    filePath = "/missing/new.mp4",
                ),
                LibraryEntryRecord(
                    id = "entry-2",
                    title = "Duplicate",
                    kind = "VIDEO",
                    filePath = "/missing/new.mp4",
                ),
            ),
        )

        LibraryArchiveService(target).import(archive, LibraryImportPolicy.REPLACE)

        val entries = target.library.sortedBy { it.id }
        assertEquals("/missing/new.mp4", entries.first().filePath)
        assertEquals(2, entries.size)
        assertTrue(LibraryQuery.duplicateIds(entries).isNotEmpty())
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val encoded = """{"schemaVersion":99,"entries":[]}"""
        assertTrue(LibraryArchiveCodec.decode(encoded).isFailure)
    }

    private class FakeRepository : DownloadRepository {
        val library = mutableListOf<LibraryEntry>()
        val collections = mutableListOf<Collection>()
        val membership = mutableMapOf<String, MutableSet<String>>()
        val smart = mutableListOf<SmartCollection>()

        override suspend fun loadTasks(): List<DownloadTask> = emptyList()
        override suspend fun insertTask(task: DownloadTask) = Unit
        override suspend fun updateTask(task: DownloadTask) = Unit
        override suspend fun deleteTask(id: String) = Unit
        override suspend fun markActiveInterrupted() = Unit
        override suspend fun loadLibrary(): List<LibraryEntry> = library.toList()
        override suspend fun insertLibraryEntry(entry: LibraryEntry) {
            library.removeAll { it.id == entry.id }
            library += entry
        }

        override suspend fun deleteLibraryEntry(id: String) {
            library.removeAll { it.id == id }
        }

        override suspend fun updateLibraryMetadata(id: String, title: String, author: String?) = Unit
        override suspend fun addLibraryTag(entryId: String, tag: String) = Unit
        override suspend fun removeLibraryTag(entryId: String, tag: String) = Unit
        override suspend fun loadCollections(): List<Collection> = collections.toList()
        override suspend fun insertCollection(collection: Collection) {
            collections.removeAll { it.id == collection.id }
            collections += collection
        }

        override suspend fun deleteCollection(id: String) {
            collections.removeAll { it.id == id }
            membership.remove(id)
        }

        override suspend fun loadCollectionMembership(): Map<String, Set<String>> =
            membership.mapValues { it.value.toSet() }

        override suspend fun addEntryToCollection(collectionId: String, entryId: String) {
            membership.getOrPut(collectionId) { mutableSetOf() } += entryId
        }

        override suspend fun removeEntryFromCollection(collectionId: String, entryId: String) {
            membership[collectionId]?.remove(entryId)
        }

        override suspend fun loadSmartCollections(): List<SmartCollection> = smart.toList()
        override suspend fun insertSmartCollection(collection: SmartCollection) {
            smart.removeAll { it.id == collection.id }
            smart += collection
        }

        override suspend fun deleteSmartCollection(id: String) {
            smart.removeAll { it.id == id }
        }
    }
}
