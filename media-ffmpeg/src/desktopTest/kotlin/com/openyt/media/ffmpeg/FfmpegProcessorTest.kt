package com.openyt.media.ffmpeg

import com.openyt.core.application.MediaOperation
import com.openyt.core.application.MediaProcessingRequest
import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaSegment
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FfmpegProcessorTest {
    @Test fun failedRuntimeInitializationReturnsAStructuredDependencyError() = runBlocking<Unit> {
        val processor = FfmpegMediaProcessor({ error("runtime initialization failed") })
        val result = processor.process(MediaProcessingRequest("/fixture.mp4", "Fixture", MediaKind.VIDEO, MediaOperation.TRIM, MediaSegment(0, 1000)))
        val failure = assertIs<EngineResult.Failure>(result)
        assertIs<com.openyt.core.domain.DownloadFailure.DependencyMissing>(failure.failure)
    }

    @Test
    fun trimArgumentsAreStructuredAndNeverOverwriteTheSource() {
        val request = MediaProcessingRequest("/media/ação; echo x.mp4", "Ação", MediaKind.VIDEO, MediaOperation.TRIM, MediaSegment(1_000, 3_000))
        val arguments = FfmpegPlan.arguments(request, "/media/clip.mp4")
        assertTrue("-n" in arguments)
        assertEquals(request.sourcePath, arguments[arguments.indexOf("-i") + 1])
        assertEquals("2.0", arguments[arguments.indexOf("-t") + 1])
        assertEquals("copy", arguments[arguments.indexOf("-c") + 1])
    }

    @Test
    fun realTrimConversionAndNormalizationPreserveTheOriginal() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("OPENYT_RUN_MEDIA_TESTS") == "1")
        val binary = System.getenv("OPENYT_FFMPEG") ?: "/usr/bin/ffmpeg"
        val directory = Files.createTempDirectory("openyt-media-").toFile()
        try {
            val source = File(directory, "Ação original.mp4")
            val command = ProcessBuilder(binary, "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=4:size=160x120:rate=10", "-f", "lavfi", "-i", "sine=duration=4:frequency=440", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", source.absolutePath).redirectErrorStream(true).start()
            val diagnostic = command.inputStream.bufferedReader().readText()
            assertEquals(0, command.waitFor(), diagnostic)
            val ffprobe = System.getenv("OPENYT_FFPROBE") ?: File(File(binary).parentFile, "ffprobe").absolutePath
            fun probe(path: String, entries: String): String {
                val process = ProcessBuilder(ffprobe, "-v", "error", "-show_entries", entries, "-of", "default=noprint_wrappers=1:nokey=1", path).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor())
                return output.trim()
            }
            val original = source.readBytes().toList()
            val processor = FfmpegMediaProcessor({ binary })
            val trim = processor.process(MediaProcessingRequest(source.absolutePath, "Ação", MediaKind.VIDEO, MediaOperation.TRIM, MediaSegment(1_000, 3_000)))
            val trimmed = assertIs<EngineResult.Success<*>>(trim).value as com.openyt.core.application.ProcessedMedia
            assertTrue(File(trimmed.path).length() > 0)
            assertTrue(probe(trimmed.path, "format=duration").toDouble() in 1.5..3.5)
            assertTrue(probe(trimmed.path, "stream=codec_name").contains("h264"))
            for (preset in listOf(AudioPreset.M4A, AudioPreset.MP3, AudioPreset.OPUS)) {
                val converted = processor.process(MediaProcessingRequest(source.absolutePath, "Ação", MediaKind.VIDEO, MediaOperation.CONVERT_AUDIO, audioPreset = preset, normalize = true))
                val output = assertIs<EngineResult.Success<*>>(converted).value as com.openyt.core.application.ProcessedMedia
                assertEquals(preset.extension, File(output.path).extension)
                assertTrue(File(output.path).length() > 0)
                val codec = when (preset) { AudioPreset.M4A -> "aac"; AudioPreset.MP3 -> "mp3"; else -> "opus" }
                assertTrue(probe(output.path, "stream=codec_name").contains(codec))
            }
            assertEquals(original, source.readBytes().toList())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".openyt-process-") })
        } finally { directory.deleteRecursively() }
    }
}
