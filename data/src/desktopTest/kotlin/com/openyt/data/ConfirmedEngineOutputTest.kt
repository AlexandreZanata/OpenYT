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
}
