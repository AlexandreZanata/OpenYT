package com.openyt.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfirmedEngineOutputTest {
    @Test fun movedSubtitleUsesOnlyItsDeclaredRelativePath() {
        val root = Files.createTempDirectory("openyt-subtitle-contract").toFile()
        try {
            val temp = File(root, ".temp").apply { mkdirs() }
            val subtitle = File(root, "Ação.pt.srt").apply { writeText("Olá") }
            val oldPath = File(temp, subtitle.name).absolutePath
            assertEquals(subtitle, confirmedEngineOutput(oldPath, root, temp, true))
            assertNull(confirmedEngineOutput(oldPath, root, temp, false))
            assertNull(confirmedEngineOutput(File(temp, "missing.srt").absolutePath, root, temp, true))
            assertNull(confirmedEngineOutput(null, root, temp, true))
        } finally { root.deleteRecursively() }
    }

    @Test fun confirmsExpectedExtensionAndRejectsMismatches() {
        val root = Files.createTempDirectory("openyt-extension-contract").toFile()
        try {
            val temp = File(root, ".temp").apply { mkdirs() }
            val mp4File = File(root, "Vídeo.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val mkvFile = File(root, "Vídeo.mkv").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            // Matching extension succeeds
            assertEquals(mp4File, confirmedEngineOutput(mp4File.absolutePath, root, temp, false, "mp4"))
            assertEquals(mp4File, confirmedEngineOutput(mp4File.absolutePath, root, temp, false, "MP4"))

            // Mismatched extension is rejected
            assertNull(confirmedEngineOutput(mp4File.absolutePath, root, temp, false, "mkv"))
            assertNull(confirmedEngineOutput(mkvFile.absolutePath, root, temp, false, "mp4"))

            // Null expected extension accepts any existing file
            assertEquals(mp4File, confirmedEngineOutput(mp4File.absolutePath, root, temp, false, null))
            assertEquals(mkvFile, confirmedEngineOutput(mkvFile.absolutePath, root, temp, false, null))
        } finally { root.deleteRecursively() }
    }
}
