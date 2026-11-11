package com.openyt.desktop.downloader

import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadOptions
import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.MediaSegment
import com.openyt.core.domain.SubtitleSelection
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.MediaKind
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.File
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
import kotlin.test.assertTrue

/** Uses real tools and a loopback fixture. External media sources are never contacted. */
class DesktopYtDlpLocalTest {
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
    fun mergesAndConvertsUnicodeMediaThenConfirmsTheFinalFile() = runBlocking {
        val fixture = temporary.newFolder("fixture")
        val manifest = File(fixture, "media.mpd")
        val generator = ProcessBuilder(
            locator.findFfmpeg()!!,
            "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=duration=1:size=160x120:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
            "-f", "dash", manifest.absolutePath,
        ).redirectErrorStream(true).start()
        val diagnostic = generator.inputStream.bufferedReader().readText()
        assertEquals(0, generator.waitFor(), diagnostic)

        File(fixture, "captions.vtt").writeText("WEBVTT\n\n00:00:00.000 --> 00:00:00.900\nOlá mundo\n")
        File(fixture, "index.html").writeText("""<html><head><title>Fixture</title></head><body><video controls><source src="/media.mpd" type="application/dash+xml"><track kind="subtitles" srclang="pt" label="Português" src="/captions.vtt"></video></body></html>""")
        val server = serve(fixture)
        val engine = DesktopYtDlpEngine(locator)
        try {
            val variants = listOf(
                MediaKind.VIDEO to DownloadOptions(),
                MediaKind.AUDIO to DownloadOptions(),
                MediaKind.AUDIO to DownloadOptions(audioPreset = AudioPreset.MP3, normalizeAudio = true),
                MediaKind.AUDIO to DownloadOptions(audioPreset = AudioPreset.OPUS, normalizeAudio = true),
                MediaKind.SUBTITLE to DownloadOptions(subtitle = SubtitleSelection("pt")),
                MediaKind.VIDEO to DownloadOptions(subtitle = SubtitleSelection("pt", embed = true)),
                MediaKind.VIDEO to DownloadOptions(segment = MediaSegment(0, 800)),
                MediaKind.VIDEO to DownloadOptions(liveDurationSeconds = 1),
            )
            for ((index, variant) in variants.withIndex()) {
                val (kind, options) = variant
                val destination = temporary.newFolder("João-$index-${kind.name}", "Curso Básico 日本語")
                val events = withTimeout(60_000) {
                    engine.download(
                        DownloadRequest(
                            url = "http://127.0.0.1:${server.address.port}/${if (options.subtitle != null) "index.html" else "media.mpd"}",
                            options = options,
                            kind = kind,
                            destinationDirectory = destination.absolutePath,
                            fileNameTemplate = "Configuração e transmissão.%(ext)s",
                        ),
                        "local-${kind.name}",
                    ).toList()
                }
                val completed = assertIs<DownloadEvent.Completed>(events.last(), events.toString())
                val output = File(completed.filePath)
                assertTrue(output.name.startsWith("Configuração e transmissão."))
                assertTrue(output.isFile && output.length() > 0)
                if (kind == MediaKind.SUBTITLE) {
                    assertTrue(output.readText().contains("Olá mundo"))
                } else {
                    val metadata = assertNotNull(DesktopMediaProbe(locator).probe(output.absolutePath))
                    assertNotNull(metadata.audioCodec)
                    if (kind == MediaKind.VIDEO) assertNotNull(metadata.videoCodec)
                    if (kind == MediaKind.AUDIO) assertEquals(options.audioPreset.extension, output.extension)
                }
                assertTrue(!File(destination, ".openyt-tmp-local-${kind.name}").exists())
            }
        } finally {
            engine.close()
            server.stop(0)
        }
    }
    @Test
    fun recordsAnActualLiveHlsFixtureUntilTheDurationLimit() = runBlocking {
        val fixture = temporary.newFolder("live")
        val generator = ProcessBuilder(locator.findFfmpeg()!!,
            "-v", "error", "-re", "-f", "lavfi", "-i", "testsrc=size=160x90:rate=10",
            "-f", "lavfi", "-i", "sine=frequency=440", "-t", "30", "-c:v", "libx264",
            "-pix_fmt", "yuv420p", "-g", "10", "-c:a", "aac", "-f", "hls",
            "-hls_time", "1", "-hls_list_size", "4", File(fixture, "live.m3u8").absolutePath,
        ).redirectErrorStream(true).start()
        val server = serve(fixture)
        val engine = DesktopYtDlpEngine(locator, startProcess = { arguments ->
            ProcessBuilder(arguments).redirectError(File("build/validation/live-engine.err").apply { parentFile.mkdirs() }).start()
        })
        try {
            withTimeout(15_000) {
                while (fixture.listFiles().orEmpty().count { it.extension == "ts" } < 3) kotlinx.coroutines.delay(100)
            }
            val url = "http://127.0.0.1:${server.address.port}/live.m3u8"
            val inspected = assertIs<com.openyt.core.domain.EngineResult.Success<*>>(engine.inspect(url)).value as com.openyt.core.domain.Inspection.Single
            assertTrue(inspected.media.isLive, "Fixture must be a real live playlist without ENDLIST")
            val events = withTimeout(40_000) { engine.download(DownloadRequest(url, destinationDirectory = temporary.newFolder("capture").absolutePath, options = DownloadOptions(liveDurationSeconds = 2)), "live-capture").toList() }
            val completed = assertIs<DownloadEvent.Completed>(events.last(), events.toString())
            val duration = assertNotNull(DesktopMediaProbe(locator).probe(completed.filePath)?.durationMillis)
            assertTrue(duration in 500..5_000, "Bounded live capture duration=$duration")
        } finally { engine.close(); server.stop(0); generator.destroyForcibly(); generator.waitFor() }
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
                    exchange.responseHeaders.add("Content-Type", when (path.toFile().extension) { "mpd" -> "application/dash+xml"; "html" -> "text/html"; "vtt" -> "text/vtt"; "m3u8" -> "application/vnd.apple.mpegurl"; "ts" -> "video/mp2t"; else -> "video/mp4" })
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
}
