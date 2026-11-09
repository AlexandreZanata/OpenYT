package com.openyt.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.android.downloader.AndroidYtDlpEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.MediaKind
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-network verification of the Android extraction engine.
 *
 * This is intentionally separated from unit tests and is not part of CI. Run it on
 * a connected device with network access:
 *
 * `./gradlew :androidApp:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class AndroidEngineNetworkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val YOUTUBE_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"

    @Test
    fun inspectsPublicMetadata() = runBlocking {
        val engine = AndroidYtDlpEngine(context)

        val result = withTimeout(180_000) {
            engine.inspect("https://archive.org/details/BigBuckBunny_124")
        }

        assertTrue("expected success but got $result", result is EngineResult.Success)
        val media = (result as EngineResult.Success).value
        assertEquals("Big Buck Bunny", media.title)
        assertTrue("expected at least one format", media.formats.isNotEmpty())
    }

    @Test
    fun inspectsYouTubeMetadata() = runBlocking {
        val engine = AndroidYtDlpEngine(context)

        val result = withTimeout(180_000) {
            engine.inspect("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        }

        assertTrue("expected success but got $result", result is EngineResult.Success)
        val media = (result as EngineResult.Success).value
        assertEquals("Me at the zoo", media.title)
        assertTrue("expected at least one format", media.formats.isNotEmpty())
    }

    @Test
    fun downloadsAndMergesYouTubeVideo(): Unit = runBlocking {
        val engine = AndroidYtDlpEngine(context)
        val directory = File(context.cacheDir, "openyt-merge-test")
        directory.deleteRecursively()
        directory.mkdirs()

        val inspection = withTimeout(180_000) { engine.inspect(YOUTUBE_URL) }
        assertTrue("inspection failed: $inspection", inspection is EngineResult.Success)

        val request = DownloadRequest(
            url = YOUTUBE_URL,
            kind = MediaKind.VIDEO,
            formatId = null,
            destinationDirectory = directory.absolutePath,
        )
        val events = withTimeout(600_000) { engine.download(request, "merge-test").toList() }
        val completed = events.filterIsInstance<DownloadEvent.Completed>().lastOrNull()
        assertTrue("expected completion, events=$events", completed != null)

        val file = File(completed!!.filePath)
        assertTrue("final file is missing: ${file.absolutePath}", file.isFile)
        assertTrue("final file is empty", file.length() > 0)
        assertTrue(
            "unexpected container: ${file.extension}",
            file.extension.lowercase() in setOf("mp4", "webm", "mkv"),
        )
        directory.deleteRecursively()
    }

    @Test
    fun extractsAudioFromYouTube(): Unit = runBlocking {
        val engine = AndroidYtDlpEngine(context)
        val directory = File(context.cacheDir, "openyt-audio-test")
        directory.deleteRecursively()
        directory.mkdirs()

        val inspection = withTimeout(180_000) { engine.inspect(YOUTUBE_URL) }
        assertTrue("inspection failed: $inspection", inspection is EngineResult.Success)

        val request = DownloadRequest(
            url = YOUTUBE_URL,
            kind = MediaKind.AUDIO,
            formatId = null,
            destinationDirectory = directory.absolutePath,
        )
        val events = withTimeout(600_000) { engine.download(request, "audio-test").toList() }
        val completed = events.filterIsInstance<DownloadEvent.Completed>().lastOrNull()
        assertTrue("expected completion, events=$events", completed != null)

        val file = File(completed!!.filePath)
        assertTrue("final file is missing: ${file.absolutePath}", file.isFile)
        assertTrue("final file is empty", file.length() > 0)
        assertEquals("m4a", file.extension.lowercase())
        directory.deleteRecursively()
    }

    @Test
    fun reportsProgressAndCancelsActiveDownload() = runBlocking {
        val engine = AndroidYtDlpEngine(context)
        val directory = File(context.cacheDir, "openyt-cancel-test")
        directory.deleteRecursively()

        val inspection = withTimeout(180_000) {
            engine.inspect("https://archive.org/details/BigBuckBunny_124")
        }
        assertTrue("inspection failed: $inspection", inspection is EngineResult.Success)
        val media = (inspection as EngineResult.Success).value
        val format = media.formats.maxByOrNull { it.sizeBytes ?: 0L }
        assertTrue("no formats to download", format != null)

        val downloadId = "cancel-test"
        val request = DownloadRequest(
            url = "https://archive.org/details/BigBuckBunny_124",
            kind = MediaKind.VIDEO,
            formatId = format!!.id,
            destinationDirectory = directory.absolutePath,
        )

        val events = Collections.synchronizedList(mutableListOf<DownloadEvent>())
        val sawProgress = AtomicBoolean(false)
        val job = launch(Dispatchers.IO) {
            engine.download(request, downloadId).collect { event ->
                events += event
                if (event is DownloadEvent.Progress) sawProgress.set(true)
            }
        }

        withTimeout(90_000) {
            while (
                !sawProgress.get() &&
                events.none { it is DownloadEvent.Failed || it is DownloadEvent.Completed }
            ) {
                delay(50)
            }
        }

        if (events.any { it is DownloadEvent.Failed || it is DownloadEvent.Completed }) {
            job.join()
            throw AssertionError("download finished before cancel, events=$events")
        }

        engine.cancel(downloadId)
        job.join()

        assertTrue(
            "expected cancellation, events=$events",
            events.lastOrNull() is DownloadEvent.Canceled,
        )
        val leftovers = directory.listFiles()?.map { it.name }.orEmpty()
        assertTrue("temporary files were not cleaned: $leftovers", leftovers.isEmpty())
        directory.deleteRecursively()
        Unit
    }
}
