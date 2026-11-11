package com.openyt.desktop.share

import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ShareableMedia
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMediaActionsTest {
    @Test fun windowsActionsPreserveTheEntireUnicodePath() {
        val root = Files.createTempDirectory("openyt-shell-").toFile()
        try {
            val file = root.resolve("Vídeo 100% & aula #1 (final).mp4").apply { writeText("fixture") }
            val opened = mutableListOf<String>()
            val revealed = mutableListOf<String>()
            val actions = DesktopMediaActions(null, "Windows 11", { opened += it.path; true }, { revealed += it.path; true })
            val media = ShareableMedia(file.path, "Fixture", MediaKind.VIDEO)
            assertTrue(actions.open(media))
            assertTrue(actions.reveal(media))
            assertEquals(listOf(file.path), opened)
            assertEquals(listOf(file.path), revealed)
        } finally { root.deleteRecursively() }
    }

    @Test fun missingFilesAndDirectoriesCannotBeReportedAsOpened() {
        val root = Files.createTempDirectory("openyt-shell-").toFile()
        try {
            var calls = 0
            val actions = DesktopMediaActions(null, "Windows 11", { calls++; true }, { calls++; true })
            assertFalse(actions.open(ShareableMedia(root.resolve("missing.mp4").path, "Missing", MediaKind.VIDEO)))
            assertFalse(actions.reveal(ShareableMedia(root.resolve("missing.mp4").path, "Missing", MediaKind.VIDEO)))
            assertFalse(actions.open(ShareableMedia(root.path, "Directory", MediaKind.VIDEO)))
            assertEquals(0, calls)
        } finally { root.deleteRecursively() }
    }

    @Test fun aRejectedOrFailingShellActionReturnsFailure() {
        val file = Files.createTempFile("openyt-shell-", ".mp4").toFile()
        try {
            val actions = DesktopMediaActions(null, "Windows 11", { false }, { throw IllegalStateException("native failure") })
            val media = ShareableMedia(file.path, "Fixture", MediaKind.VIDEO)
            assertFalse(actions.open(media))
            assertFalse(actions.reveal(media))
        } finally { file.delete() }
    }
}
