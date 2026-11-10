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

class FolderImportServiceTest {
    @Test
    fun importsScannedMediaOnceAndNeverMutatesFiles() = runBlocking {
        val repository = FakeRepository()
        val scanner = FolderScanner {
            listOf(
                ScannedMedia("/media/movie.mp4", "movie", MediaKind.VIDEO, 10, 123),
                ScannedMedia("/media/song.m4a", "song", MediaKind.AUDIO, 20, 456),
            )
        }
        val service = FolderImportService(scanner, repository)

        val first = service.import("/media")
        assertEquals(2, first.added)
        assertEquals(0, first.skipped)
        assertEquals(2, repository.library.size)
        assertTrue(repository.library.any { it.title == "movie" && it.filePath == "/media/movie.mp4" })

        val second = service.import("/media")
        assertEquals(0, second.added)
        assertEquals(2, second.skipped)
        assertEquals(2, repository.library.size)
    }

    private class FakeRepository : DownloadRepository {
        val library = mutableListOf<LibraryEntry>()

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
        override suspend fun loadCollections(): List<Collection> = emptyList()
        override suspend fun insertCollection(collection: Collection) = Unit
        override suspend fun deleteCollection(id: String) = Unit
        override suspend fun loadCollectionMembership(): Map<String, Set<String>> = emptyMap()
        override suspend fun addEntryToCollection(collectionId: String, entryId: String) = Unit
        override suspend fun removeEntryFromCollection(collectionId: String, entryId: String) = Unit
        override suspend fun loadSmartCollections(): List<SmartCollection> = emptyList()
        override suspend fun insertSmartCollection(collection: SmartCollection) = Unit
        override suspend fun deleteSmartCollection(id: String) = Unit
    }
}
