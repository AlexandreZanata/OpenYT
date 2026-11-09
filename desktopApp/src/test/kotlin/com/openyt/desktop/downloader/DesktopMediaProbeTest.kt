package com.openyt.desktop.downloader

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopMediaProbeTest {
    @Test
    fun probesGeneratedMediaWhenFfmpegIsAvailable(): Unit = runBlocking {
        val locator = ExecutableLocator()
        val ffmpeg = locator.findFfmpeg()
        assumeTrue("ffmpeg/ffprobe not available", ffmpeg != null && locator.findFfprobe() != null)

        val directory = File(System.getProperty("java.io.tmpdir"), "openyt-probe-test")
        directory.deleteRecursively()
        directory.mkdirs()
        val file = File(directory, "sample.mp4")

        val process = ProcessBuilder(
            ffmpeg!!,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc=duration=1:size=320x240:rate=10",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=1",
            "-c:v",
            "libx264",
            "-c:a",
            "aac",
            "-shortest",
            file.absolutePath,
        ).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        assertEquals(0, process.waitFor(), "ffmpeg failed to generate the fixture")

        val info = DesktopMediaProbe(locator).probe(file.absolutePath)

        assertNotNull(info)
        assertEquals(320, info.width)
        assertEquals(240, info.height)
        assertEquals("h264", info.videoCodec)
        assertEquals("aac", info.audioCodec)
        val duration = assertNotNull(info.durationMillis)
        assertTrue(duration in 900L..1_100L)
        directory.deleteRecursively()
    }
}
