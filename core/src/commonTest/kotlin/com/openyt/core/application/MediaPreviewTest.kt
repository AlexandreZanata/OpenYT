package com.openyt.core.application

import com.openyt.core.domain.*
import com.openyt.core.testing.FakeDownloadEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MediaPreviewTest {
    @Test fun differentPreviewRangesCannotReuseAStaleCachedClip() = runTest {
        val base = FakeDownloadEngine()
        val engine = object : DownloadEngine by base {
            override fun download(request: DownloadRequest, downloadId: String) = flowOf(
                DownloadEvent.Completed("/cache/" + request.fileNameTemplate.replace("%(id)s", "media").replace("%(ext)s", "mp4"), 100),
            )
        }
        val played = mutableListOf<String>()
        val launcher = object : PlaybackLauncher {
            override val canPlay = true
            override fun play(items: List<PlayableMedia>, startIndex: Int) { played += items[startIndex].path }
        }
        var id = 0
        val preview = EngineMediaPreview(engine, launcher, "/cache") { (++id).toString() }
        for (range in listOf(MediaSegment(0, 1000), MediaSegment(2000, 3000))) {
            assertIs<EngineResult.Success<Unit>>(preview.preview(FakeDownloadEngine.SAMPLE_MEDIA, "https://example.com/video", range))
        }
        assertEquals(2, played.distinct().size)
        assertTrue(base.canceledIds.isEmpty(), "Successful previews must not cancel completed engine IDs")
    }

    @Test fun leavingThePreviewCancelsItsPlatformEngineJob() = runTest {
        val started = CompletableDeferred<Unit>()
        val base = FakeDownloadEngine()
        val engine = object : DownloadEngine by base {
            override fun download(request: DownloadRequest, downloadId: String) = flow<DownloadEvent> {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val launcher = object : PlaybackLauncher {
            override val canPlay = true
            override fun play(items: List<PlayableMedia>, startIndex: Int) = Unit
        }
        val preview = EngineMediaPreview(engine, launcher, "/cache") { "cancel-preview" }
        val job = launch { preview.preview(FakeDownloadEngine.SAMPLE_MEDIA, "https://example.com/video", MediaSegment(0, 1000)) }
        started.await()
        job.cancelAndJoin()
        assertEquals(listOf("cancel-preview"), base.canceledIds)
    }
}
