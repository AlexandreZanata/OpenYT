package com.openyt.desktop.downloader

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals

class ExecutableLocatorTest {
    @Test
    fun findsToolInBundledDirectory() {
        val directory = File(System.getProperty("java.io.tmpdir"), "openyt-bundled-test")
        directory.deleteRecursively()
        directory.mkdirs()
        val name = if (System.getProperty("os.name").lowercase().contains("win")) {
            "yt-dlp.exe"
        } else {
            "yt-dlp"
        }
        val binary = File(directory, name).apply {
            writeText("test")
            setExecutable(true)
        }
        try {
            val locator = ExecutableLocator { directory }
            assertEquals(binary.absolutePath, locator.findYtDlp())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun formatsJavaScriptRuntimeForYtDlp() {
        val directory = File(System.getProperty("java.io.tmpdir"), "openyt-runtime-test")
        directory.deleteRecursively()
        directory.mkdirs()
        val name = if (System.getProperty("os.name").lowercase().contains("win")) {
            "deno.exe"
        } else {
            "deno"
        }
        val binary = File(directory, name).apply {
            writeText("test")
            setExecutable(true)
        }
        try {
            val locator = ExecutableLocator { directory }
            assertEquals("deno:${binary.absolutePath}", locator.findJsRuntimeArgument())
        } finally {
            directory.deleteRecursively()
        }
    }
}
