package com.openyt.desktop.downloader

import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.MediaKind
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-network integration test for the desktop engine.
 *
 * Opt-in only; it requires a yt-dlp binary and network access:
 *
 * `OPENYT_RUN_NETWORK_TESTS=1 OPENYT_YTDLP=/path/to/yt-dlp ./gradlew :desktopApp:test`
 */
class DesktopYtDlpEngineIntegrationTest {
    private val source = "https://archive.org/details/BigBuckBunny_124"
    private val engine = DesktopYtDlpEngine()

    @Before
    fun requireOptIn() {
        assumeTrue(
            "set OPENYT_RUN_NETWORK_TESTS=1 to run real-network tests",
            System.getenv("OPENYT_RUN_NETWORK_TESTS") == "1",
        )
        assumeTrue("yt-dlp not available", ExecutableLocator().findYtDlp() != null)
    }

    @Test
    fun inspectsHappyPathMetadata(): Unit = runBlocking {
        val result = withTimeout(180_000) { engine.inspect(source) }

        assertTrue(result is EngineResult.Success, "expected success but got $result")
        val media = (result as EngineResult.Success).value
        assertEquals("Big Buck Bunny", media.title)
        assertTrue(media.formats.isNotEmpty(), "expected at least one format")
    }

    @Test
    fun downloadsWithProgressAndProducesValidFile(): Unit = runBlocking {
        val inspection = withTimeout(180_000) { engine.inspect(source) }
        val media = (inspection as EngineResult.Success).value
        val format = media.formats.minByOrNull { it.sizeBytes ?: Long.MAX_VALUE }
        assertTrue(format != null, "no formats available")

        val directory = File(System.getProperty("java.io.tmpdir"), "openyt-desktop-test")
        directory.deleteRecursively()
        val request = DownloadRequest(
            url = source,
            kind = MediaKind.VIDEO,
            formatId = format!!.id,
            destinationDirectory = directory.absolutePath,
        )

        val events = withTimeout(600_000) {
            engine.download(request, "desktop-download").toList()
        }

        val progress = events.filterIsInstance<DownloadEvent.Progress>()
        assertTrue(progress.isNotEmpty(), "expected progress events, got $events")
        val completed = events.filterIsInstance<DownloadEvent.Completed>().lastOrNull()
        assertTrue(completed != null, "expected completion, got $events")
        assertTrue(File(completed!!.filePath).isFile, "final file is missing")
        assertTrue(File(completed.filePath).length() > 0, "final file is empty")
        directory.deleteRecursively()
    }

    @Test
    fun cancelsActiveDownload(): Unit = runBlocking {
        val inspection = withTimeout(180_000) { engine.inspect(source) }
        val media = (inspection as EngineResult.Success).value
        val format = media.formats.maxByOrNull { it.sizeBytes ?: 0L }
        assertTrue(format != null, "no formats available")

        val directory = File(System.getProperty("java.io.tmpdir"), "openyt-desktop-cancel")
        directory.deleteRecursively()
        val request = DownloadRequest(
            url = source,
            kind = MediaKind.VIDEO,
            formatId = format!!.id,
            destinationDirectory = directory.absolutePath,
        )

        val events = Collections.synchronizedList(mutableListOf<DownloadEvent>())
        val sawProgress = AtomicBoolean(false)
        val job = launch(Dispatchers.IO) {
            engine.download(request, "desktop-cancel").collect { event ->
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

        engine.cancel("desktop-cancel")
        job.join()

        assertTrue(
            events.lastOrNull() is DownloadEvent.Canceled,
            "expected cancellation, events=$events",
        )
        val leftovers = directory.listFiles()?.map { it.name }.orEmpty()
        assertTrue(leftovers.isEmpty(), "temporary files were not cleaned: $leftovers")
        directory.deleteRecursively()
    }

    @Test
    fun downloadsAndMergesYouTubeWithFfmpeg(): Unit = runBlocking {
        val youtube = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        val inspection = withTimeout(180_000) { engine.inspect(youtube) }
        assertTrue(inspection is EngineResult.Success, "inspection failed: $inspection")

        val directory = File(System.getProperty("java.io.tmpdir"), "openyt-desktop-merge")
        directory.deleteRecursively()
        val request = DownloadRequest(
            url = youtube,
            kind = MediaKind.VIDEO,
            formatId = null,
            destinationDirectory = directory.absolutePath,
        )

        val events = withTimeout(600_000) {
            engine.download(request, "desktop-merge").toList()
        }
        val completed = events.filterIsInstance<DownloadEvent.Completed>().lastOrNull()
        assertTrue(completed != null, "expected completion, events=$events")

        val file = File(completed!!.filePath)
        assertTrue(file.isFile, "final file is missing")
        assertTrue(file.length() > 0, "final file is empty")

        val ffprobe = ExecutableLocator().findFfprobe()
        assertTrue(ffprobe != null, "ffprobe is required for this test")
        val streams = runFfprobe(ffprobe!!, file)
        assertTrue(streams.contains("video"), "missing video stream: $streams")
        assertTrue(streams.contains("audio"), "missing audio stream: $streams")
        directory.deleteRecursively()
    }

    private fun runFfprobe(binary: String, file: File): String {
        val process = ProcessBuilder(
            binary,
            "-v",
            "error",
            "-show_entries",
            "stream=codec_type",
            "-of",
            "csv=p=0",
            file.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }
}
