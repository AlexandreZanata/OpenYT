package com.openyt.data

import com.openyt.core.application.*
import com.openyt.core.domain.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ClipJobStoreTest {
    @Test fun restartMarksActiveClipInterruptedAndRetainsRecipeAndCompletedReceipt() = runBlocking {
        val root = Files.createTempDirectory("clip-journal-").toFile()
        try {
            val path = root.resolve("library.db").path
            val request = MediaProcessingRequest("/local/source.mp4", "Source", MediaKind.VIDEO, MediaOperation.RENDER_CLIP,
                MediaSegment(0, 1000), sourceEntryId = "entry", sourceDurationMillis = 2000, jobId = "clip-1")
            val store = SqlDelightClipJobStore(createOpenYtDatabase(path))
            store.save(ClipJobRecord("clip-1", request, "sha", ClipJobStatus.RUNNING))
            store.complete(ClipJobRecord("clip-2", request.copy(jobId = "clip-2"), "sha", ClipJobStatus.SUCCEEDED,
                ProcessedMedia("processed-clip-2", "Clip", "/local/clip.mp4", MediaKind.VIDEO, 100)))
            val reopened = SqlDelightClipJobStore(createOpenYtDatabase(path))
            reopened.interruptRunning()
            assertEquals(ClipJobStatus.INTERRUPTED, reopened.get("clip-1")!!.status)
            assertEquals(request, reopened.get("clip-1")!!.request)
            assertEquals(ClipJobStatus.SUCCEEDED, reopened.get("clip-2")!!.status)
            assertEquals(2, reopened.load("entry").size)
            assertEquals("processed-clip-2", SqlDelightDownloadRepository(createOpenYtDatabase(path)).loadLibrary().single().id)
        } finally { root.deleteRecursively() }
    }
}
