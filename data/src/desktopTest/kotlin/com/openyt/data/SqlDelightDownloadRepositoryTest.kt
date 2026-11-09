package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.domain.DownloadFailure
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
            repository.insertTask(sampleTask(id = "download-1"))
            repository.updateTask(
                sampleTask(id = "download-1").copy(
                    status = DownloadStatus.COMPLETED,
                    percent = 100.0,
                    filePath = "/tmp/video.mp4",
                    sizeBytes = 1234,
                ),
            )
            repository.insertLibraryEntry(
                LibraryEntry("download-1", "Sample media", MediaKind.VIDEO, "/tmp/video.mp4", 1234),
            )
            database = createOpenYtDatabase(file.absolutePath)
            repository = SqlDelightDownloadRepository(database)

            val tasks = repository.loadTasks()
            assertEquals(1, tasks.size)
            assertEquals("download-1", tasks.single().id)
            assertEquals(DownloadStatus.COMPLETED, tasks.single().status)
            assertEquals("/tmp/video.mp4", tasks.single().filePath)
            assertEquals(1234L, tasks.single().sizeBytes)

            val library = repository.loadLibrary()
            assertEquals(1, library.size)
            assertEquals("Sample media", library.single().title)
            assertEquals(1234L, library.single().sizeBytes)
        } finally {
            file.delete()
        }
    }

    @Test
    fun marksQueuedDownloadingAndProcessingAsInterrupted() = runBlocking {
        val database = fileDatabase()
        val repository = SqlDelightDownloadRepository(database)

        repository.insertTask(sampleTask("download-1", DownloadStatus.QUEUED))
        repository.insertTask(sampleTask("download-2", DownloadStatus.DOWNLOADING))
        repository.insertTask(sampleTask("download-3", DownloadStatus.PROCESSING))
        repository.insertTask(sampleTask("download-4", DownloadStatus.COMPLETED))
        repository.markActiveInterrupted()

        val byId = repository.loadTasks().associateBy { it.id }
        assertEquals(DownloadStatus.INTERRUPTED, byId.getValue("download-1").status)
        assertEquals(DownloadStatus.INTERRUPTED, byId.getValue("download-2").status)
        assertEquals(DownloadStatus.INTERRUPTED, byId.getValue("download-3").status)
        assertEquals(DownloadStatus.COMPLETED, byId.getValue("download-4").status)
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
