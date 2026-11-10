package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadPriority
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.data.db.OpenYtDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightDownloadRepositoryTest {
    @Test
    fun persistsTasksAndLibraryAcrossReopen() = runBlocking {
        val file = File.createTempFile("openyt-test-", ".db").apply { delete() }
        try {
            var database = createOpenYtDatabase(file.absolutePath)
            var repository = SqlDelightDownloadRepository(database)
            repository.insertTask(
                sampleTask(id = "download-1").copy(
                    priority = DownloadPriority.HIGH,
                    attempt = 2,
                ),
            )
            repository.updateTask(
                sampleTask(id = "download-1").copy(
                    status = DownloadStatus.COMPLETED,
                    percent = 100.0,
                    filePath = "/tmp/video.mp4",
                    sizeBytes = 1234,
                    priority = DownloadPriority.HIGH,
                    attempt = 2,
                ),
            )
            repository.insertLibraryEntry(
                LibraryEntry(
                    "download-1",
                    "Sample media",
                    MediaKind.VIDEO,
                    "/tmp/video.mp4",
                    1234,
                    thumbnailUrl = "https://example.com/t.jpg",
                ),
            )
            database = createOpenYtDatabase(file.absolutePath)
            repository = SqlDelightDownloadRepository(database)

            val tasks = repository.loadTasks()
            assertEquals(1, tasks.size)
            assertEquals("download-1", tasks.single().id)
            assertEquals(DownloadStatus.COMPLETED, tasks.single().status)
            assertEquals("/tmp/video.mp4", tasks.single().filePath)
            assertEquals(1234L, tasks.single().sizeBytes)
            assertEquals(DownloadPriority.HIGH, tasks.single().priority)
            assertEquals(2, tasks.single().attempt)

            val library = repository.loadLibrary()
            assertEquals(1, library.size)
            assertEquals("Sample media", library.single().title)
            assertEquals(1234L, library.single().sizeBytes)
            assertEquals("https://example.com/t.jpg", library.single().thumbnailUrl)
        } finally {
            file.delete()
        }
    }

    @Test
    fun persistsLibraryTagsAndRemovesThemWithTheEntry() = runBlocking {
        val database = fileDatabase()
        val repository = SqlDelightDownloadRepository(database)
        repository.insertLibraryEntry(
            LibraryEntry(
                id = "tag-entry",
                title = "Tagged",
                kind = MediaKind.AUDIO,
                filePath = "/tmp/tagged.m4a",
                tags = listOf("music", "favorite"),
            ),
        )

        assertEquals(
            listOf("favorite", "music"),
            repository.loadLibrary().single().tags,
        )

        repository.addLibraryTag("tag-entry", "live")
        assertEquals(
            listOf("favorite", "live", "music"),
            repository.loadLibrary().single().tags,
        )

        repository.removeLibraryTag("tag-entry", "favorite")
        assertEquals(
            listOf("live", "music"),
            repository.loadLibrary().single().tags,
        )

        repository.deleteLibraryEntry("tag-entry")
        assertTrue(repository.loadLibrary().isEmpty())
    }

    @Test
    fun marksQueuedDownloadingAndProcessingAsInterrupted() = runBlocking {
        val database = fileDatabase()
        val repository = SqlDelightDownloadRepository(database)

        repository.insertTask(sampleTask("download-1", DownloadStatus.QUEUED))
        repository.insertTask(sampleTask("download-2", DownloadStatus.DOWNLOADING))
        repository.insertTask(sampleTask("download-3", DownloadStatus.PROCESSING))
        repository.insertTask(sampleTask("download-4", DownloadStatus.COMPLETED))
        repository.insertTask(sampleTask("download-5", DownloadStatus.PAUSED))
        repository.markActiveInterrupted()

        val byId = repository.loadTasks().associateBy { it.id }
        assertEquals(DownloadStatus.INTERRUPTED, byId.getValue("download-1").status)
        assertEquals(DownloadStatus.INTERRUPTED, byId.getValue("download-2").status)
        assertEquals(DownloadStatus.INTERRUPTED, byId.getValue("download-3").status)
        assertEquals(DownloadStatus.COMPLETED, byId.getValue("download-4").status)
        assertEquals(DownloadStatus.PAUSED, byId.getValue("download-5").status)
    }

    @Test
    fun roundTripsStructuredFailures() = runBlocking {
        val database = fileDatabase()
        val repository = SqlDelightDownloadRepository(database)
        val failures = listOf(
            DownloadFailure.InvalidUrl("bad scheme"),
            DownloadFailure.UnsupportedSource,
            DownloadFailure.Unavailable("region"),
            DownloadFailure.Network,
            DownloadFailure.InsufficientSpace,
            DownloadFailure.PermissionDenied,
            DownloadFailure.DependencyMissing("ffmpeg"),
            DownloadFailure.ProcessingFailed("merge failed"),
            DownloadFailure.Unexpected("boom"),
        )

        failures.forEachIndexed { index, failure ->
            repository.insertTask(
                sampleTask("download-$index", DownloadStatus.FAILED).copy(failure = failure),
            )
        }

        val stored = repository.loadTasks().associateBy { it.id }
        failures.forEachIndexed { index, failure ->
            assertEquals(failure, stored.getValue("download-$index").failure)
        }
    }

    @Test
    fun deletingLibraryEntryDoesNotTouchTask() = runBlocking {
        val database = fileDatabase()
        val repository = SqlDelightDownloadRepository(database)
        repository.insertTask(sampleTask("download-1", DownloadStatus.COMPLETED))
        repository.insertLibraryEntry(
            LibraryEntry("download-1", "Sample media", MediaKind.VIDEO, "/tmp/video.mp4", 1234),
        )

        repository.deleteLibraryEntry("download-1")

        assertTrue(repository.loadLibrary().isEmpty())
        assertEquals(1, repository.loadTasks().size)
    }

    @Test
    fun storesCollectionsAndMembership() = runBlocking {
        val repository = SqlDelightDownloadRepository(fileDatabase())
        repository.insertCollection(Collection("collection-1", "Favorites"))
        repository.insertCollection(Collection("collection-2", "Later"))
        repository.addEntryToCollection("collection-1", "download-1")
        repository.addEntryToCollection("collection-1", "download-2")

        assertEquals(
            listOf("Favorites", "Later"),
            repository.loadCollections().map { it.name },
        )
        assertEquals(
            setOf("download-1", "download-2"),
            repository.loadCollectionMembership().getValue("collection-1"),
        )

        repository.removeEntryFromCollection("collection-1", "download-2")
        assertEquals(
            setOf("download-1"),
            repository.loadCollectionMembership().getValue("collection-1"),
        )

        repository.deleteCollection("collection-1")
        assertNull(repository.loadCollectionMembership()["collection-1"])
        assertEquals(1, repository.loadCollections().size)
    }

    @Test
    fun deletingLibraryEntryClearsCollectionMembership() = runBlocking {
        val repository = SqlDelightDownloadRepository(fileDatabase())
        repository.insertTask(sampleTask("download-1", DownloadStatus.COMPLETED))
        repository.insertLibraryEntry(
            LibraryEntry("download-1", "Sample media", MediaKind.VIDEO, "/tmp/video.mp4", 1),
        )
        repository.insertCollection(Collection("collection-1", "Favorites"))
        repository.addEntryToCollection("collection-1", "download-1")

        repository.deleteLibraryEntry("download-1")

        assertTrue(repository.loadCollectionMembership().isEmpty())
        assertTrue(repository.loadCollections().isNotEmpty())
    }

    @Test
    fun missingFailureMapsToNull() = runBlocking {
        val database = fileDatabase()
        val repository = SqlDelightDownloadRepository(database)
        repository.insertTask(sampleTask("download-1"))
        assertNull(repository.loadTasks().single().failure)
    }

    private fun fileDatabase(): OpenYtDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OpenYtDatabase.Schema.create(driver)
        return OpenYtDatabase(driver)
    }

    private fun sampleTask(
        id: String,
        status: DownloadStatus = DownloadStatus.QUEUED,
    ) = DownloadTask(
        id = id,
        media = MediaInfo(
            sourceId = "abc",
            source = "youtube",
            title = "Sample media",
            author = "Author",
            durationSeconds = 42,
            thumbnailUrl = "https://example.com/t.jpg",
            webpageUrl = "https://example.com/watch",
        ),
        request = DownloadRequest(
            url = "https://example.com/watch",
            kind = MediaKind.VIDEO,
            formatId = "137",
            destinationDirectory = "/tmp/openyt",
        ),
        status = status,
    )
}
