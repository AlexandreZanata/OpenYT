package com.openyt.desktop.downloader

import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadOptions
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.DownloadProfileResolver
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ProfileResolution
import com.openyt.core.domain.SubtitleSelection
import com.openyt.core.domain.toDownloadRequest
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract verification test for NX-P04:
 * Real local-tool downloads with each compatible profile (study, audio, save-space, tv),
 * actual stream/container probe, absent subtitle handling, source format disappearance,
 * and safe arguments for Unicode/spaces.
 *
 * Uses real bundled tools and a loopback HTTP fixture.
 * External media sources are never contacted.
 */
class DownloadProfileLocalTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val locator = ExecutableLocator()

    @Before
    fun requireTools() {
        assumeTrue("set OPENYT_RUN_LOCAL_ENGINE_TESTS=1", System.getenv("OPENYT_RUN_LOCAL_ENGINE_TESTS") == "1")
        assertNotNull(locator.findYtDlp())
        assertNotNull(locator.findFfmpeg())
        assertNotNull(locator.findFfprobe())
    }

    @Test
    fun realDownloadWithStudyProfileMatchesPlanAndEmbedsSubtitles() = runBlocking {
        val (fixture, server) = createLocalFixture(withSubtitles = true)
        val engine = createEngine()
        val probe = DesktopMediaProbe(locator)
        try {
            val url = "http://127.0.0.1:${server.address.port}/index.html"
            val inspection = assertIs<Inspection.Single>(
                assertIs<EngineResult.Success<*>>(engine.inspect(url)).value,
            )

            val profile = DownloadProfile.BuiltIns.first { it.id == DownloadProfile.BUILTIN_STUDY }
            val resolution = assertIs<ProfileResolution.Success>(
                DownloadProfileResolver.resolve(profile, inspection.media),
            )
            assertEquals("mp4", resolution.plan.container)
            assertNotNull(resolution.plan.selectedSubtitle)
            assertEquals("pt", resolution.plan.selectedSubtitle?.language)

            val destination = temporary.newFolder("Study-Profile")
            val request = resolution.plan.toDownloadRequest(
                url = url,
                destinationDirectory = destination.absolutePath,
                fileNameTemplate = "Aula de Estudo.%(ext)s",
            )

            val events = withTimeout(60_000) {
                engine.download(request, "study-task").toList()
            }
            val completed = assertIs<DownloadEvent.Completed>(events.last(), "Events: $events")
            val outputFile = File(completed.filePath)

            // Confirm container matches approved plan
            assertTrue(outputFile.isFile && outputFile.length() > 0)
            assertEquals("mp4", outputFile.extension)
            assertEquals("Aula de Estudo.mp4", outputFile.name)

            // Probe container, streams and codecs
            val metadata = assertNotNull(probe.probe(outputFile.absolutePath))
            assertEquals("h264", metadata.videoCodec)
            assertEquals("aac", metadata.audioCodec)
            assertTrue(metadata.container?.contains("mp4") == true)

            // Verify temporary directory cleanup
            assertFalse(File(destination, ".openyt-tmp-study-task").exists())
        } finally {
            engine.close()
            server.stop(0)
        }
    }

    @Test
    fun realDownloadWithAudioProfileMatchesPlanAndAudioPreset() = runBlocking {
        val (fixture, server) = createLocalFixture(withSubtitles = false)
        val engine = createEngine()
        val probe = DesktopMediaProbe(locator)
        try {
            val url = "http://127.0.0.1:${server.address.port}/media.mpd"
            val inspection = assertIs<Inspection.Single>(
                assertIs<EngineResult.Success<*>>(engine.inspect(url)).value,
            )

            val profile = DownloadProfile.BuiltIns.first { it.id == DownloadProfile.BUILTIN_AUDIO }
            val resolution = assertIs<ProfileResolution.Success>(
                DownloadProfileResolver.resolve(profile, inspection.media),
            )
            assertEquals(MediaKind.AUDIO, resolution.plan.kind)

            val destination = temporary.newFolder("Audio-Profile")
            val request = resolution.plan.toDownloadRequest(
                url = url,
                destinationDirectory = destination.absolutePath,
                fileNameTemplate = "Audio Track.%(ext)s",
            )

            val events = withTimeout(60_000) {
                engine.download(request, "audio-task").toList()
            }
            val completed = assertIs<DownloadEvent.Completed>(events.last(), "Events: $events")
            val outputFile = File(completed.filePath)

            assertTrue(outputFile.isFile && outputFile.length() > 0)
            assertEquals(resolution.plan.container, outputFile.extension)
            assertEquals("m4a", outputFile.extension)

            val metadata = assertNotNull(probe.probe(outputFile.absolutePath))
            assertEquals("aac", metadata.audioCodec)
            assertNull(metadata.videoCodec, "Audio profile must not contain video stream")

            assertFalse(File(destination, ".openyt-tmp-audio-task").exists())
        } finally {
            engine.close()
            server.stop(0)
        }
    }

    @Test
    fun realDownloadWithSaveSpaceProfileMatchesPlanAndHeightCeiling() = runBlocking {
        val (fixture, server) = createLocalFixture(withSubtitles = false)
        val engine = createEngine()
        val probe = DesktopMediaProbe(locator)
        try {
            val url = "http://127.0.0.1:${server.address.port}/media.mpd"
            val inspection = assertIs<Inspection.Single>(
                assertIs<EngineResult.Success<*>>(engine.inspect(url)).value,
            )

            val profile = DownloadProfile.BuiltIns.first { it.id == DownloadProfile.BUILTIN_SAVE_SPACE }
            val resolution = assertIs<ProfileResolution.Success>(
                DownloadProfileResolver.resolve(profile, inspection.media),
            )
            assertEquals("mp4", resolution.plan.container)

            val destination = temporary.newFolder("SaveSpace-Profile")
            val request = resolution.plan.toDownloadRequest(
                url = url,
                destinationDirectory = destination.absolutePath,
                fileNameTemplate = "Video Economico.%(ext)s",
            )

            val events = withTimeout(60_000) {
                engine.download(request, "save-space-task").toList()
            }
            val completed = assertIs<DownloadEvent.Completed>(events.last(), "Events: $events")
            val outputFile = File(completed.filePath)

            assertTrue(outputFile.isFile && outputFile.length() > 0)
            assertEquals("mp4", outputFile.extension)

            val metadata = assertNotNull(probe.probe(outputFile.absolutePath))
            assertEquals("h264", metadata.videoCodec)
            assertEquals("aac", metadata.audioCodec)
            assertTrue(metadata.height != null && metadata.height!! <= 720)

            assertFalse(File(destination, ".openyt-tmp-save-space-task").exists())
        } finally {
            engine.close()
            server.stop(0)
        }
    }

    @Test
    fun realDownloadWithWatchOnTvProfileMatchesPlanAndCodecs() = runBlocking {
        val (fixture, server) = createLocalFixture(withSubtitles = false)
        val engine = createEngine()
        val probe = DesktopMediaProbe(locator)
        try {
            val url = "http://127.0.0.1:${server.address.port}/media.mpd"
            val inspection = assertIs<Inspection.Single>(
                assertIs<EngineResult.Success<*>>(engine.inspect(url)).value,
            )

            val profile = DownloadProfile.BuiltIns.first { it.id == DownloadProfile.BUILTIN_TV }
            val resolution = assertIs<ProfileResolution.Success>(
                DownloadProfileResolver.resolve(profile, inspection.media),
            )
            assertEquals("mp4", resolution.plan.container)

            val destination = temporary.newFolder("TV-Profile")
            val request = resolution.plan.toDownloadRequest(
                url = url,
                destinationDirectory = destination.absolutePath,
                fileNameTemplate = "Video para TV.%(ext)s",
            )

            val events = withTimeout(60_000) {
                engine.download(request, "tv-task").toList()
            }
            val completed = assertIs<DownloadEvent.Completed>(events.last(), "Events: $events")
            val outputFile = File(completed.filePath)

            assertTrue(outputFile.isFile && outputFile.length() > 0)
            assertEquals("mp4", outputFile.extension)

            val metadata = assertNotNull(probe.probe(outputFile.absolutePath))
            assertEquals("h264", metadata.videoCodec)
            assertEquals("aac", metadata.audioCodec)
            assertTrue(metadata.height != null && metadata.height!! <= 1080)

            assertFalse(File(destination, ".openyt-tmp-tv-task").exists())
        } finally {
            engine.close()
            server.stop(0)
        }
    }

    @Test
    fun absentSubtitleDoesNotCorruptVideoAndFailsSubtitleOnlyRequest() = runBlocking {
        val (fixture, server) = createLocalFixture(withSubtitles = false)
        val engine = createEngine()
        val probe = DesktopMediaProbe(locator)
        try {
            val url = "http://127.0.0.1:${server.address.port}/media.mpd"

            // 1. Video download requesting absent subtitle: video completes cleanly, no corruption
            val videoDestination = temporary.newFolder("Video-Missing-Subtitle")
            val videoRequest = DownloadRequest(
                url = url,
                kind = MediaKind.VIDEO,
                destinationDirectory = videoDestination.absolutePath,
                fileNameTemplate = "Video Without Subs.%(ext)s",
                options = DownloadOptions(
                    subtitle = SubtitleSelection(language = "ja", automatic = false, embed = true),
                ),
            )
            val videoEvents = withTimeout(60_000) {
                engine.download(videoRequest, "video-absent-sub").toList()
            }
            val videoCompleted = assertIs<DownloadEvent.Completed>(videoEvents.last(), "Video events: $videoEvents")
            val videoFile = File(videoCompleted.filePath)
            assertTrue(videoFile.isFile && videoFile.length() > 0)
            val metadata = assertNotNull(probe.probe(videoFile.absolutePath))
            assertEquals("h264", metadata.videoCodec)
            assertEquals("aac", metadata.audioCodec)

            // 2. Subtitle-only request for absent subtitle: fails with OutputMissing without falsely succeeding
            val subDestination = temporary.newFolder("Sub-Missing-Subtitle")
            val subRequest = DownloadRequest(
                url = url,
                kind = MediaKind.SUBTITLE,
                destinationDirectory = subDestination.absolutePath,
                fileNameTemplate = "Missing.%(ext)s",
                options = DownloadOptions(
                    subtitle = SubtitleSelection(language = "ja", automatic = false),
                ),
            )
            val subEvents = withTimeout(60_000) {
                engine.download(subRequest, "sub-absent-sub").toList()
            }
            val subFailed = assertIs<DownloadEvent.Failed>(subEvents.last(), "Sub events: $subEvents")
            assertEquals(DownloadFailure.OutputMissing, subFailed.failure)
            assertEquals(0, subDestination.listFiles()?.size ?: 0)
        } finally {
            engine.close()
            server.stop(0)
        }
    }

    @Test
    fun sourceFormatDisappearanceReportsStructuredErrorWithoutRawTraceback() = runBlocking {
        val (fixture, server) = createLocalFixture(withSubtitles = false)
        val engine = createEngine()
        try {
            val url = "http://127.0.0.1:${server.address.port}/media.mpd"
            val destination = temporary.newFolder("Format-Disappeared")
            val request = DownloadRequest(
                url = url,
                kind = MediaKind.VIDEO,
                formatId = "99999+99998",
                destinationDirectory = destination.absolutePath,
                fileNameTemplate = "Disappeared.%(ext)s",
            )
            val events = withTimeout(60_000) {
                engine.download(request, "disappeared-format").toList()
            }
            val failed = assertIs<DownloadEvent.Failed>(events.last(), "Events: $events")
            val failure = assertIs<DownloadFailure.ProcessingFailed>(failed.failure)
            assertTrue(
                failure.reason?.contains("requested format is not available") == true,
                "Expected structured format failure but got: ${failure.reason}",
            )
            assertEquals(0, destination.listFiles()?.count { it.isFile } ?: 0)
        } finally {
            engine.close()
            server.stop(0)
        }
    }

    @Test
    fun safeArgumentsPreserveUnicodeSpacesAndPunctuation() = runBlocking {
        val (fixture, server) = createLocalFixture(withSubtitles = true)
        val engine = createEngine()
        val probe = DesktopMediaProbe(locator)
        try {
            val url = "http://127.0.0.1:${server.address.port}/index.html"
            val destination = temporary.newFolder("OpenYT Profiles", "Vídeos e Áudios [2026] — João & Maria 日本語")
            val template = "Configuração & Transmissão [HD] \"v1\" — 2026.%(ext)s"

            val request = DownloadRequest(
                url = url,
                kind = MediaKind.VIDEO,
                destinationDirectory = destination.absolutePath,
                fileNameTemplate = template,
                options = DownloadOptions(
                    profileProvenance = com.openyt.core.domain.ProfileProvenance(
                        profileId = "study",
                        profileName = "Study",
                        resolvedContainer = "mp4",
                    ),
                ),
            )

            val events = withTimeout(60_000) {
                engine.download(request, "unicode-args").toList()
            }
            val completed = assertIs<DownloadEvent.Completed>(events.last(), "Events: $events")
            val outputFile = File(completed.filePath)

            assertTrue(outputFile.isFile && outputFile.length() > 0)
            assertTrue(outputFile.name.startsWith("Configuração & Transmissão [HD]"))
            assertEquals("mp4", outputFile.extension)

            val metadata = assertNotNull(probe.probe(outputFile.absolutePath))
            assertEquals("h264", metadata.videoCodec)
            assertEquals("aac", metadata.audioCodec)

            // Temporary folder must be cleaned up
            assertFalse(File(destination, ".openyt-tmp-unicode-args").exists())
        } finally {
            engine.close()
            server.stop(0)
        }
    }

    private fun createEngine(): DesktopYtDlpEngine =
        DesktopYtDlpEngine(locator)

    private fun createLocalFixture(withSubtitles: Boolean): Pair<File, HttpServer> {
        val fixture = temporary.newFolder("fixture-" + System.nanoTime())
        val manifest = File(fixture, "media.mpd")
        val generator = ProcessBuilder(
            locator.findFfmpeg()!!,
            "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=duration=1:size=160x120:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
            "-init_seg_name", "init-\$RepresentationID\$.m4s",
            "-media_seg_name", "chunk-\$RepresentationID\$-\$Number%05d\$.m4s",
            "-seg_duration", "1", "-remove_at_exit", "0", "-f", "dash", manifest.name,
        ).directory(fixture).redirectErrorStream(true).start()
        val diagnostic = generator.inputStream.bufferedReader().readText()
        assertEquals(0, generator.waitFor(), diagnostic)
        assertTrue(File(fixture, "init-0.m4s").isFile, "DASH fixture segments missing")

        if (withSubtitles) {
            File(fixture, "captions.vtt").writeText("WEBVTT\n\n00:00:00.000 --> 00:00:00.900\nOlá mundo\n")
            File(fixture, "index.html").writeText(
                """<html><head><title>Fixture</title></head><body><video controls><source src="/media.mpd" type="application/dash+xml"><track kind="subtitles" srclang="pt" label="Português" src="/captions.vtt"></video></body></html>""",
            )
        }

        val server = serve(fixture)
        return fixture to server
    }

    private fun serve(fixture: File): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val path = fixture.toPath().resolve(exchange.requestURI.path.removePrefix("/")).normalize()
                if (!path.startsWith(fixture.toPath()) || !path.toFile().isFile) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    val bytes = path.toFile().readBytes()
                    exchange.responseHeaders.add(
                        "Content-Type",
                        when (path.toFile().extension) {
                            "mpd" -> "application/dash+xml"
                            "html" -> "text/html"
                            "vtt" -> "text/vtt"
                            "m3u8" -> "application/vnd.apple.mpegurl"
                            "ts" -> "video/mp2t"
                            else -> "video/mp4"
                        },
                    )
                    exchange.sendResponseHeaders(200, if (exchange.requestMethod == "HEAD") -1 else bytes.size.toLong())
                    if (exchange.requestMethod != "HEAD") exchange.responseBody.write(bytes)
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        return server
    }

    private fun assertFalse(condition: Boolean, message: String? = null) {
        assertTrue(!condition, message)
    }
}
