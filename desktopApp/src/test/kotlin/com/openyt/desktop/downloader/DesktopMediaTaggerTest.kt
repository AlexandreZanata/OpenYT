package com.openyt.desktop.downloader

import com.openyt.core.application.AudioMetadata
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopMediaTaggerTest {
    @Test
    fun writesEmbeddedTagsWithoutRenamingTheFile() = runBlocking {
        assumeTrue(System.getenv("OPENYT_RUN_MEDIA_TESTS") == "1")
        val locator = ExecutableLocator()
        val ffmpeg = locator.findFfmpeg()
        val ffprobe = locator.findFfprobe()
        assumeTrue(ffmpeg != null && ffprobe != null)

        val directory = File(System.getProperty("java.io.tmpdir"), "openyt-tagger-test")
        directory.deleteRecursively()
        directory.mkdirs()
        val file = File(directory, "fixture.m4a")
        try {
            val (generateExit, generateOutput) = exec(
                ffmpeg!!,
                listOf(
                    "-y",
                    "-f", "lavfi",
                    "-i", "sine=frequency=440:duration=1",
                    "-c:a", "aac",
                    file.absolutePath,
                ),
            )
            assertEquals(0, generateExit, generateOutput)

            val tagged = DesktopMediaTagger(locator).writeTags(
                path = file.absolutePath,
                metadata = AudioMetadata("New title", "New artist"),
            )
            assertTrue(tagged, "tagging failed")
            assertTrue(file.isFile, "original path must still exist")

            val (probeExit, probeOutput) = exec(
                ffprobe!!,
                listOf(
                    "-v", "quiet",
                    "-show_entries", "format_tags=title,artist",
                    "-of", "default=noprint_wrappers=1",
                    file.absolutePath,
                ),
            )
            assertEquals(0, probeExit, probeOutput)
            assertTrue(probeOutput.contains("title=New title"), probeOutput)
            assertTrue(probeOutput.contains("artist=New artist"), probeOutput)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun exec(binary: String, arguments: List<String>): Pair<Int, String> {
        val process = ProcessBuilder(listOf(binary) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor(60, TimeUnit.SECONDS)
        return process.exitValue() to output
    }
}
