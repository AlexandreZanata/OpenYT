package com.openyt.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test
    fun redactsCredentialsQueryTokensAndFragments() {
        assertEquals(
            "https://example.com/path",
            DiagnosticsRedactor.redactUrl(
                "https://user:pass@example.com/path?token=abc&key=123#frag",
            ),
        )
        assertEquals(
            "https://example.com/a",
            DiagnosticsRedactor.redactUrl("https://example.com/a?token=secret"),
        )
    }

    @Test
    fun redactsHomeDirectory() {
        assertEquals(
            "~/Downloads/OpenYT/video.mp4",
            DiagnosticsRedactor.redactPath("/home/alice/Downloads/OpenYT/video.mp4", "/home/alice"),
        )
        assertEquals("<local>/file", DiagnosticsRedactor.redactPath("/tmp/file", "/home/alice"))
    }

    @Test
    fun pathsOutsideHomeAndSimilarPrefixesDoNotExposePrivateRoots() {
        assertEquals("<local>/private.mp4", DiagnosticsRedactor.redactPath("/home/alice-other/private.mp4", "/home/alice"))
        assertEquals("~/Downloads/video.mp4", DiagnosticsRedactor.redactPath("C:\\Users\\Alice\\Downloads\\video.mp4", "c:\\users\\alice"))
        assertEquals("not a URL", DiagnosticsRedactor.redactUrl("not a URL"))
    }

    @Test
    fun formatsCountsToolsAndNoRawUrls() {
        val text = DiagnosticsFormatter.format(
            PlatformDiagnostics(
                appVersion = "1.0.0",
                platform = "Linux",
                engineName = "yt-dlp (desktop)",
                engineVersion = "2026.10.26",
                downloadDirectory = "~/Downloads/OpenYT",
                databasePath = "~/.local/share/OpenYT/openyt.db",
                tools = listOf(
                    DiagnosticTool("yt-dlp", available = true, version = "2026.10.26"),
                    DiagnosticTool("ffmpeg", available = false),
                ),
            ),
            taskCount = 2,
            libraryCount = 3,
            collectionCount = 1,
        )

        assertTrue(text.contains("version: 1.0.0"))
        assertTrue(text.contains("tasks: 2"))
        assertTrue(text.contains("library: 3"))
        assertTrue(text.contains("collections: 1"))
        assertTrue(text.contains("yt-dlp: available (2026.10.26)"))
        assertTrue(text.contains("ffmpeg: missing"))
        assertTrue(!text.contains("https://"))
    }
}
