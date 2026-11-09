package com.clipbau.engine.ytdlp

import com.clipbau.core.domain.DownloadFailure
import com.clipbau.core.domain.DownloadEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class YtDlpSupportTest {
    @Test
    fun parsesCompleteProgressLine() {
        val event = YtDlpProgressParser.parse("CLIPBAU_PROGRESS: 42.5%|1048576|2097152|524288|3")

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
        val event = YtDlpProgressParser.parse("CLIPBAU_PROGRESS:7.1%|NA|NA|NA|NA")

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
        assertEquals(100.0, YtDlpProgressParser.parse("CLIPBAU_PROGRESS:120%|NA|NA|NA|NA")?.percent)
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
            CLIPBAU_PROGRESS:100%|1024|1024|NA|NA
            /tmp/a.webm
        """.trimIndent()

        assertEquals("/tmp/a.webm", YtDlpOutputParser.extractFilePath(stdout))
    }

    @Test
    fun extractsWindowsPath() {
        assertEquals(
            "C:\\Users\\me\\Videos\\a.mp4",
            YtDlpOutputParser.extractFilePath("C:\\Users\\me\\Videos\\a.mp4"),
        )
    }
}
