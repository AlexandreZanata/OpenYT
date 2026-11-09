package com.openyt.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.createOpenYtDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDatabasePersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "openyt-instrumented-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun persistsQueueAndLibraryAndMarksActiveInterrupted() = runBlocking {
        var repository = SqlDelightDownloadRepository(createOpenYtDatabase(context, databaseName))
        repository.insertTask(
            sampleTask("download-1", DownloadStatus.DOWNLOADING),
        )
        repository.insertLibraryEntry(
            LibraryEntry("download-1", "Sample media", MediaKind.VIDEO, "/tmp/video.mp4", 1234),
        )

        repository = SqlDelightDownloadRepository(createOpenYtDatabase(context, databaseName))
        repository.markActiveInterrupted()
        val tasks = repository.loadTasks()
        assertEquals(1, tasks.size)
        assertEquals(DownloadStatus.INTERRUPTED, tasks.single().status)

        val library = repository.loadLibrary()
        assertEquals(1, library.size)
        assertEquals("Sample media", library.single().title)

        repository.deleteLibraryEntry("download-1")
        assertTrue(repository.loadLibrary().isEmpty())
        assertEquals(1, repository.loadTasks().size)
    }

    private fun sampleTask(id: String, status: DownloadStatus) = DownloadTask(
        id = id,
        media = MediaInfo(
            sourceId = "abc",
            source = "youtube",
            title = "Sample media",
            webpageUrl = "https://example.com/watch",
        ),
        request = DownloadRequest(
            url = "https://example.com/watch",
            kind = MediaKind.VIDEO,
            destinationDirectory = "/tmp/openyt",
        ),
        status = status,
    )
}
