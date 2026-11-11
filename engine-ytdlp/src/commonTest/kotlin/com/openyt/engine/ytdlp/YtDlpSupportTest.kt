package com.openyt.engine.ytdlp

import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class YtDlpSupportTest {
    @Test
    fun parsesCompleteProgressLine() {
        val event = YtDlpProgressParser.parse("OPENYT_PROGRESS: 42.5%|1048576|2097152|524288|3")

        assertEquals(
            DownloadEvent.Progress(
                percent = 42.5,
                downloadedBytes = 1_048_576,
                totalBytes = 2_097_152,
                speedBytesPerSecond = 524_288,
                etaSeconds = 3,
            ),
            event,
        )
    }

    @Test
    fun treatsUnknownFieldsAsNull() {
        val event = YtDlpProgressParser.parse("OPENYT_PROGRESS:7.1%|NA|NA|NA|NA")

        assertIs<DownloadEvent.Progress>(event)
        assertNull(event.downloadedBytes)
        assertNull(event.totalBytes)
        assertNull(event.speedBytesPerSecond)
        assertNull(event.etaSeconds)
    }

    @Test
    fun ignoresUnrelatedLines() {
        assertNull(YtDlpProgressParser.parse("[download] 10% of 1MiB"))
    }

    @Test
    fun clampsProgressToValidRange() {
        assertEquals(100.0, YtDlpProgressParser.parse("OPENYT_PROGRESS:120%|NA|NA|NA|NA")?.percent)
    }

    @Test
    fun mapsKnownFailures() {
        assertIs<DownloadFailure.UnsupportedSource>(
            YtDlpErrorMapper.fromToolOutput("ERROR: Unsupported URL: https://x"),
        )
        assertIs<DownloadFailure.InsufficientSpace>(
            YtDlpErrorMapper.fromToolOutput("OSError: [Errno 28] No space left on device"),
        )
        assertIs<DownloadFailure.Network>(
            YtDlpErrorMapper.fromToolOutput("ERROR: Connection timed out"),
        )
        assertIs<DownloadFailure.Unavailable>(
            YtDlpErrorMapper.fromToolOutput("ERROR: Video unavailable"),
        )
        assertIs<DownloadFailure.DependencyMissing>(
            YtDlpErrorMapper.fromToolOutput("ERROR: ffmpeg not found. Please install."),
        )
    }

    @Test
    fun extractsFinalPathFromStdout() {
        val stdout = """
            [download] Destination: /tmp/a.webm
            OPENYT_PROGRESS:100%|1024|1024|NA|NA
            OPENYT_FILE:"/tmp/a.webm"
        """.trimIndent()

        assertEquals("/tmp/a.webm", YtDlpOutputParser.extractFilePath(stdout))
    }

    @Test
    fun extractsWindowsPath() {
        assertEquals(
            "C:\\Users\\me\\Videos\\a.mp4",
            YtDlpOutputParser.extractFilePath("OPENYT_FILE:" + Json.encodeToString("C:\\Users\\me\\Videos\\a.mp4")),
        )
    }

    @Test
    fun finalPathsPreserveUnicodeQuotesAndNetworkShares() {
        val paths = listOf(
            "C:\\Users\\João\\Downloads\\Curso Básico\\Aula ção.mkv",
            "\\\\servidor\\Mídia\\Aula 1.mkv",
            "\\\\?\\C:\\Vídeos\\Aula.mkv",
            "/media/日本語/ação \"final\".mp4",
        )
        paths.forEach { path ->
            assertEquals(path, YtDlpOutputParser.extractFilePath("OPENYT_FILE:" + Json.encodeToString(path)))
        }
        assertEquals(
            "C:\\Vídeos\\Básico.mkv",
            YtDlpOutputParser.extractFilePath("OPENYT_FILE:\"C:\\\\V\\u00eddeos\\\\B\\u00e1sico.mkv\""),
        )
    }

    @Test
    fun logsAndMalformedFinalRecordsNeverConfirmAFile() {
        listOf(
            "[Merger] Merging formats into C:\\Videos\\a.mkv",
            "/tmp/unfinished.mp4",
            "OPENYT_FILE:null",
            "OPENYT_FILE:123",
            "OPENYT_FILE:\"relative.mp4\"",
            "OPENYT_FILE:\"unterminated",
            "[download] OPENYT_FILE:\"/tmp/a.mp4\"",
        ).forEach { assertNull(YtDlpOutputParser.extractFilePath(it), it) }
    }

    @Test
    fun rejectsNonFiniteProgressAndInvalidCounters() {
        listOf("NaN", "Infinity", "-Infinity").forEach {
            assertNull(YtDlpProgressParser.parse("OPENYT_PROGRESS:$it|NA|NA|NA|NA"))
        }
        assertEquals(
            DownloadEvent.Progress(percent = 25.0),
            YtDlpProgressParser.parse("OPENYT_PROGRESS:25%|-1|NaN|Infinity|-Infinity"),
        )
    }
}
