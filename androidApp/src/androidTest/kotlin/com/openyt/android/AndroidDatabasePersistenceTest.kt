package com.openyt.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.core.application.StudyService
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.StudyResult
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.data.createOpenYtDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun persistsStudyNotesBookmarksRangesAndCompletionAcrossReopen() = runBlocking {
        val db = createOpenYtDatabase(context, databaseName)
        val downloadRepo = SqlDelightDownloadRepository(db)
        val studyRepo = SqlDelightStudyRepository(db)
        var seq = 0
        val service = StudyService(
            repository = studyRepo,
            nowEpochMillis = { 1_700_000_000_000L },
            nextId = { "android-study-${++seq}" },
        )

        val revision = MediaRevision(sizeBytes = 2048L, lastModifiedEpochMillis = 5_000L, contentSha256 = "sha-a1")
        downloadRepo.insertLibraryEntry(
            LibraryEntry("study-entry-1", "Android Study Video", MediaKind.VIDEO, "/tmp/study.mp4", 2048L, mediaRevision = revision),
        )

        assertTrue(service.createNote("study-entry-1", 12_000L, "Android note 📚") is StudyResult.Success)
        assertTrue(service.createBookmark("study-entry-1", 24_000L, "Key moment") is StudyResult.Success)
        assertTrue(service.saveRange("study-entry-1", 5_000L, 9_000L, "Loop range") is StudyResult.Success)
        assertTrue(service.setEntryStudied("study-entry-1", true) is StudyResult.Success)

        val reopenedStudy = SqlDelightStudyRepository(createOpenYtDatabase(context, databaseName))
        val bundle = reopenedStudy.loadEntryStudyBundle("study-entry-1")
        assertTrue(bundle.entryExists)
        assertEquals(1, bundle.notes.size)
        assertEquals("Android note 📚", bundle.notes.single().body)
        assertEquals(1, bundle.bookmarks.size)
        assertEquals(1, bundle.savedRanges.size)
        assertTrue(bundle.studyState.isStudied)
        assertFalse(bundle.hasStaleAnchors)
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
