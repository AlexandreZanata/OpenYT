package com.openyt.desktop.downloader

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledToolsTest {
    @Test
    fun copiesBundledToolsAndRestoresExecutablePermission() {
        val root = File(System.getProperty("java.io.tmpdir"), "openyt-bundled-tools-test")
        root.deleteRecursively()
        val source = File(root, "resources").apply { mkdirs() }
        val cache = File(root, "cache")
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val name = if (windows) "yt-dlp.exe" else "yt-dlp"
        File(source, name).writeText("test")

        val previousResources = System.getProperty("compose.application.resources.dir")
        val previousCache = System.getProperty("openyt.bundledToolsCache")
        try {
            System.setProperty("compose.application.resources.dir", source.absolutePath)
            System.setProperty("openyt.bundledToolsCache", cache.absolutePath)

            val directory = BundledTools.directory()
            val copied = File(directory, name)

            assertEquals("test", copied.readText())
            if (!windows) {
                assertTrue(copied.canExecute(), "bundled tool must be executable")
            }
        } finally {
            restore("compose.application.resources.dir", previousResources)
            restore("openyt.bundledToolsCache", previousCache)
            root.deleteRecursively()
        }
    }

    private fun restore(name: String, value: String?) {
        if (value == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, value)
        }
    }
}
