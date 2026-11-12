package com.openyt.desktop.downloader

import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.engine.ytdlp.YtDlpOutputParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopYtDlpEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun confirmsUnicodeOutputAndUsesAnExplicitUtf8Protocol() = runBlocking {
        val directory = temporary.newFolder("João", "Curso Básico")
        val file = File(directory, "01 - Configuração 日本語.mkv").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val engine = engine { arguments ->
            assertEquals("utf-8", arguments[arguments.indexOf("--encoding") + 1])
            assertTrue("--ignore-config" in arguments)
            assertEquals(YtDlpOutputParser.FILE_PATH_TEMPLATE, arguments[arguments.indexOf("--print") + 1])
            val charset = arguments.indexOf("--encoding").takeIf { it >= 0 }
                ?.let { Charset.forName(arguments[it + 1]) } ?: Charset.forName("windows-1252")
            MemoryProcess(finalRecord(file).toByteArray(charset))
        }

        val events = engine.download(request(directory), "unicode").toList()

        assertEquals(DownloadEvent.Completed(file.absolutePath, 3), events.last())
        assertTrue(file.isFile)
        assertTrue(!File(directory, ".openyt-tmp-unicode").exists())
    }

    @Test
    fun doesNotMistakeAFileInTheLogForSuccessfulCompletion() = runBlocking {
        val directory = temporary.newFolder()
        val file = File(directory, "unfinished.mp4").apply { writeText("partial stream") }
        val events = engine { MemoryProcess(file.absolutePath.toByteArray()) }
            .download(request(directory), "no-final-record").toList()

        assertEquals(DownloadEvent.Failed(DownloadFailure.OutputMissing), events.last())
        assertTrue(file.isFile, "an unconfirmed user file must remain on disk")
    }

    @Test
    fun rejectsOutputWhenContainerDoesNotMatchApprovedPlan() = runBlocking {
        val directory = temporary.newFolder()
        // yt-dlp produced webm, but plan required mp4
        val file = File(directory, "video.webm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val profileRequest = DownloadRequest(
            url = "https://example.com/media",
            kind = com.openyt.core.domain.MediaKind.VIDEO,
            destinationDirectory = directory.absolutePath,
            options = com.openyt.core.domain.DownloadOptions(
                profileProvenance = com.openyt.core.domain.ProfileProvenance(
                    profileId = "study",
                    profileName = "Study",
                    resolvedContainer = "mp4",
                ),
            ),
        )
        val events = engine { MemoryProcess(finalRecord(file).toByteArray()) }
            .download(profileRequest, "container-mismatch").toList()

        assertEquals(DownloadEvent.Failed(DownloadFailure.OutputMissing), events.last())
    }

    @Test
    fun acceptsOutputWhenContainerMatchesApprovedPlan() = runBlocking {
        val directory = temporary.newFolder()
        val file = File(directory, "video.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val profileRequest = DownloadRequest(
            url = "https://example.com/media",
            kind = com.openyt.core.domain.MediaKind.VIDEO,
            destinationDirectory = directory.absolutePath,
            options = com.openyt.core.domain.DownloadOptions(
                profileProvenance = com.openyt.core.domain.ProfileProvenance(
                    profileId = "study",
                    profileName = "Study",
                    resolvedContainer = "mp4",
                ),
            ),
        )
        val events = engine { arguments ->
            assertTrue(arguments.contains("--merge-output-format"))
            assertEquals("mp4", arguments[arguments.indexOf("--merge-output-format") + 1])
            assertTrue(arguments.contains("--remux-video"))
            assertEquals("mp4", arguments[arguments.indexOf("--remux-video") + 1])
            MemoryProcess(finalRecord(file).toByteArray())
        }.download(profileRequest, "container-match").toList()

        assertEquals(DownloadEvent.Completed(file.absolutePath, 3), events.last())
    }

    @Test
    fun rejectsMissingAndEmptyFinalFiles() = runBlocking {
        val directory = temporary.newFolder()
        val file = File(directory, "empty.mkv")
        for (createEmpty in listOf(false, true)) {
            if (createEmpty) file.createNewFile()
            val events = engine { MemoryProcess(finalRecord(file).toByteArray()) }
                .download(request(directory), "invalid-output").toList()
            assertEquals(DownloadEvent.Failed(DownloadFailure.OutputMissing), events.last())
        }
    }

    @Test
    fun preservesRealProcessingFailuresEvenIfAnOutputFileExists() = runBlocking {
        val directory = temporary.newFolder()
        val file = File(directory, "failed.mkv").apply { writeText("partial") }
        val events = engine {
            MemoryProcess(finalRecord(file).toByteArray(), "ERROR: Postprocessing failed", exitCode = 1)
        }.download(request(directory), "failed-processing").toList()

        assertIs<DownloadFailure.ProcessingFailed>(assertIs<DownloadEvent.Failed>(events.last()).failure)
        assertTrue(file.isFile)
    }

    @Test
    fun inspectionUsesUtf8AndIgnoresAmbientToolConfiguration() = runBlocking {
        val result = engine { arguments ->
            assertEquals("utf-8", arguments[arguments.indexOf("--encoding") + 1])
            assertTrue("--ignore-config" in arguments)
            MemoryProcess("""{"id":"1","title":"Curso Básico 日本語","webpage_url":"https://example.com/media"}""".toByteArray())
        }.inspect("https://example.com/media")

        assertEquals("Curso Básico 日本語", assertIs<Inspection.Single>(assertIs<EngineResult.Success<*>>(result).value).media.title)
    }

    @Test
    fun processLaunchFailureIsStructured() = runBlocking {
        val directory = temporary.newFolder()
        val events = engine { throw IOException("private path and tool error") }
            .download(request(directory), "launch-failure").toList()
        assertEquals(DownloadEvent.Failed(DownloadFailure.Unexpected(null)), events.last())
        assertTrue(!File(directory, ".openyt-tmp-launch-failure").exists())
    }

    @Test
    fun pauseDuringProcessStartupKeepsThePartialDirectory() = runBlocking {
        val directory = temporary.newFolder()
        lateinit var pauseEngine: DesktopYtDlpEngine
        pauseEngine = engine {
            runBlocking { pauseEngine.pause("startup-pause") }
            MemoryProcess(byteArrayOf())
        }
        val events = pauseEngine.download(request(directory), "startup-pause").toList()
        assertEquals(DownloadEvent.Paused, events.last())
        assertTrue(File(directory, ".openyt-tmp-startup-pause").isDirectory)
    }

    private fun engine(start: (List<String>) -> Process): DesktopYtDlpEngine {
        val tools = temporary.newFolder()
        File(tools, "yt-dlp").apply {
            writeText("fixture")
            setExecutable(true)
        }
        File(tools, "yt-dlp.exe").writeText("fixture")
        return DesktopYtDlpEngine(ExecutableLocator { tools }, start)
    }

    private fun request(directory: File) = DownloadRequest(
        url = "https://example.com/media",
        destinationDirectory = directory.absolutePath,
    )

    private fun finalRecord(file: File) = "OPENYT_FILE:" + Json.encodeToString(file.absolutePath) + "\n"

    private class MemoryProcess(
        private val stdout: ByteArray,
        private val stderr: String = "",
        private val exitCode: Int = 0,
    ) : Process() {
        override fun getInputStream() = ByteArrayInputStream(stdout)
        override fun getErrorStream() = ByteArrayInputStream(stderr.toByteArray())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = exitCode
        override fun exitValue() = exitCode
        override fun isAlive() = false
        override fun destroy() = Unit
    }
}
