package com.openyt.desktop.downloader

import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadOptions
import com.openyt.core.domain.DownloadPriority
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.DownloadProfileResolver
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaFormat
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ProfileProvenance
import com.openyt.core.domain.ProfileResolution
import com.openyt.core.domain.SubtitleSelection
import com.openyt.core.domain.SubtitleTrack
import com.openyt.core.domain.toDownloadRequest
import com.openyt.data.SqlDelightDownloadProfileRepository
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.createOpenYtDatabase
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract verification test for NX-P06:
 * Combined native preview/download/restart/retry flow and regression of existing
 * manual format flow across SQLite persistence and local engine execution.
 */
class DownloadProfileNativeFlowTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun sampleMedia(): MediaInfo = MediaInfo(
        sourceId = "lecture-2026",
        source = "youtube",
        title = "Arquitetura Multiplataforma e Perfis de Download",
        webpageUrl = "https://example.com/watch?v=lecture-2026",
        durationSeconds = 1200,
        author = "OpenYT Academy",
        formats = listOf(
            MediaFormat(
                id = "137",
                kind = MediaKind.VIDEO,
                container = "mp4",
                height = 1080,
                videoCodec = "avc1.640028",
                sizeBytes = 60_000_000L,
            ),
            MediaFormat(
                id = "136",
                kind = MediaKind.VIDEO,
                container = "mp4",
                height = 720,
                videoCodec = "avc1.4d401f",
                sizeBytes = 30_000_000L,
            ),
            MediaFormat(
                id = "140",
                kind = MediaKind.AUDIO,
                container = "m4a",
                audioCodec = "mp4a.40.2",
                bitrateKbps = 128.0,
                sizeBytes = 6_000_000L,
            ),
        ),
        subtitles = listOf(
            SubtitleTrack(language = "pt", name = "Português", automatic = false),
            SubtitleTrack(language = "en", name = "English", automatic = false),
        ),
    )

    @Test
    fun combinedNativeFlowWithSqlitePersistencePreservesProfileProvenanceAcrossRestartAndRetry() = runBlocking {
        val dbFile = temporary.newFile("openyt-combined-flow.db")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine1 = SimulatedEngine()

        val db1 = createOpenYtDatabase(dbFile.absolutePath)
        val repo1 = SqlDelightDownloadRepository(db1)
        val profileRepo1 = SqlDelightDownloadProfileRepository(db1)
        val coordinator1 = DownloadCoordinator(engine1, scope1, repo1)
        coordinator1.restore()

        val media = sampleMedia()

        // 1. Resolve Profile Plan (Preview)
        val profile = DownloadProfile.study("pt")
        val resolution = assertIs<ProfileResolution.Success>(
            DownloadProfileResolver.resolve(profile, media),
        )
        val plan = resolution.plan
        assertEquals("mp4", plan.container)
        assertEquals("137+140", "${plan.selectedVideoFormat?.id}+${plan.selectedAudioFormat?.id}")
        assertEquals("pt", plan.selectedSubtitle?.language)

        // 2. Enqueue Plan (Explicit Download Action)
        val destinationFolder = temporary.newFolder("study-downloads")
        val taskId = coordinator1.enqueuePlan(
            media = media,
            plan = plan,
            destinationDirectory = destinationFolder.absolutePath,
            fileNameTemplate = "Modulo 01 - %(title)s.%(ext)s",
        )
        coordinator1.awaitPersistence()

        // 3. Verify task exists with full ProfileProvenance in SQLite
        val tasksAfterEnqueue = repo1.loadTasks()
        assertEquals(1, tasksAfterEnqueue.size)
        val queuedTask = tasksAfterEnqueue.first()
        assertEquals(taskId, queuedTask.id)
        val provenance = assertNotNull(queuedTask.request.options.profileProvenance)
        assertEquals(DownloadProfile.BUILTIN_STUDY, provenance.profileId)
        assertEquals("Study offline", provenance.profileName)
        assertEquals("mp4", provenance.resolvedContainer)
        assertEquals("137+140", provenance.selectedFormatId)
        assertEquals(1080, provenance.effectiveHeight)

        // 4. Simulate application restart (close coordinator 1, open coordinator 2 on same DB)
        scope1.cancel()
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine2 = SimulatedEngine(autoEmitCompleted = true)
        val db2 = createOpenYtDatabase(dbFile.absolutePath)
        val repo2 = SqlDelightDownloadRepository(db2)
        val coordinator2 = DownloadCoordinator(engine2, scope2, repo2)

        coordinator2.restore()

        val restoredTasks = coordinator2.tasks.value
        assertEquals(1, restoredTasks.size)
        val restoredTask = restoredTasks.first()
        assertEquals(taskId, restoredTask.id)
        // Tasks active across restart transition cleanly to INTERRUPTED
        assertEquals(DownloadStatus.INTERRUPTED, restoredTask.status)

        val restoredProvenance = assertNotNull(restoredTask.request.options.profileProvenance)
        assertEquals(DownloadProfile.BUILTIN_STUDY, restoredProvenance.profileId)
        assertEquals("mp4", restoredProvenance.resolvedContainer)
        assertEquals("137+140", restoredProvenance.selectedFormatId)
        assertEquals("Modulo 01 - %(title)s.%(ext)s", restoredTask.request.fileNameTemplate)

        // 5. Retry the interrupted task (retry creates a fresh queued task with the same request)
        coordinator2.retry(taskId)
        coordinator2.awaitPersistence()

        val tasksAfterRetry = coordinator2.tasks.value
        assertEquals(2, tasksAfterRetry.size)
        val retriedTask = tasksAfterRetry.last()
        assertEquals(restoredTask.request, retriedTask.request)

        val retriedProvenance = assertNotNull(retriedTask.request.options.profileProvenance)
        assertEquals(DownloadProfile.BUILTIN_STUDY, retriedProvenance.profileId)
        assertEquals("mp4", retriedProvenance.resolvedContainer)
        assertEquals("137+140", retriedProvenance.selectedFormatId)

        // 6. Complete task through engine and verify task and library entry preserve provenance
        val completedTask = kotlinx.coroutines.withTimeout(5_000) {
            while (true) {
                val current = coordinator2.tasks.value.first { it.id == retriedTask.id }
                if (current.status == DownloadStatus.COMPLETED) return@withTimeout current
                kotlinx.coroutines.delay(20)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
        assertEquals(DownloadStatus.COMPLETED, completedTask.status)
        assertEquals(provenance, completedTask.request.options.profileProvenance)

        scope2.cancel()

        scope2.cancel()
    }

    @Test
    fun manualFormatSelectionFlowSurvivesAlongsideProfilesWithoutInterference() = runBlocking {
        val dbFile = temporary.newFile("openyt-manual-flow.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SimulatedEngine()

        val db = createOpenYtDatabase(dbFile.absolutePath)
        val repo = SqlDelightDownloadRepository(db)
        val coordinator = DownloadCoordinator(engine, scope, repo)
        coordinator.restore()

        val media = sampleMedia()

        // 1. Enqueue manual format request (raw format ID without profile provenance)
        val destinationFolder = temporary.newFolder("manual-downloads")
        val manualRequest = DownloadRequest(
            url = media.webpageUrl,
            kind = MediaKind.VIDEO,
            formatId = "136+140",
            destinationDirectory = destinationFolder.absolutePath,
            fileNameTemplate = "Manual - %(title)s.%(ext)s",
            options = DownloadOptions(), // No profileProvenance
        )
        val taskId = coordinator.enqueue(media = media, request = manualRequest)
        coordinator.awaitPersistence()

        // 2. Verify manual task in DB has raw formatId and null profileProvenance
        val tasksInDb = repo.loadTasks()
        assertEquals(1, tasksInDb.size)
        val task = tasksInDb.first()
        assertEquals(taskId, task.id)
        assertEquals("136+140", task.request.formatId)
        assertNull(task.request.options.profileProvenance, "Manual format task must not have profile provenance")

        // 3. Restart and restore from DB
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine2 = SimulatedEngine()
        val db2 = createOpenYtDatabase(dbFile.absolutePath)
        val repo2 = SqlDelightDownloadRepository(db2)
        val coordinator2 = DownloadCoordinator(engine2, scope2, repo2)
        coordinator2.restore()

        val restored = coordinator2.tasks.value.first()
        assertEquals("136+140", restored.request.formatId)
        assertNull(restored.request.options.profileProvenance)
        assertEquals("Manual - %(title)s.%(ext)s", restored.request.fileNameTemplate)

        // 4. Retry manual task
        coordinator2.retry(taskId)
        coordinator2.awaitPersistence()

        val tasksAfterRetry = coordinator2.tasks.value
        assertEquals(2, tasksAfterRetry.size)
        val retried = tasksAfterRetry.last()
        assertTrue(retried.status.isActive, "Retried manual task must be active (queued or downloading)")
        assertEquals("136+140", retried.request.formatId)
        assertNull(retried.request.options.profileProvenance)

        scope.cancel()
        scope2.cancel()
    }

    @Test
    fun realLocalLoopbackToolFlowVerifiesProfileAndManualOutputs() = runBlocking {
        assumeTrue("set OPENYT_RUN_LOCAL_ENGINE_TESTS=1", System.getenv("OPENYT_RUN_LOCAL_ENGINE_TESTS") == "1")

        val locator = ExecutableLocator()
        assertNotNull(locator.findYtDlp(), "yt-dlp required")
        assertNotNull(locator.findFfmpeg(), "ffmpeg required")
        assertNotNull(locator.findFfprobe(), "ffprobe required")

        val (fixture, server) = createLocalHttpFixture()
        val engine = DesktopYtDlpEngine(locator)
        val probe = DesktopMediaProbe(locator)
        val probeDir = temporary.newFolder("Real-Native-Flow")

        try {
            val url = "http://127.0.0.1:${server.address.port}/index.html"
            val inspection = assertIs<Inspection.Single>(
                assertIs<EngineResult.Success<*>>(engine.inspect(url)).value,
            )

            // Flow A: Profile Download (Study profile)
            val profile = DownloadProfile.study("pt")
            val resolution = assertIs<ProfileResolution.Success>(
                DownloadProfileResolver.resolve(profile, inspection.media),
            )
            val profileRequest = resolution.plan.toDownloadRequest(
                url = url,
                destinationDirectory = probeDir.absolutePath,
                fileNameTemplate = "Profile-Study.%(ext)s",
            )
            val profileEvents = withTimeout(60_000) {
                engine.download(profileRequest, "native-study-task").toList()
            }
            val profileCompleted = assertIs<DownloadEvent.Completed>(profileEvents.last(), "Events: $profileEvents")
            val profileFile = File(profileCompleted.filePath)
            assertTrue(profileFile.isFile && profileFile.length() > 0)
            assertEquals("mp4", profileFile.extension)

            val profileMeta = assertNotNull(probe.probe(profileFile.absolutePath))
            assertEquals("h264", profileMeta.videoCodec)
            assertEquals("aac", profileMeta.audioCodec)

            // Flow B: Manual Format Selection Download (raw request, no profile remuxing)
            val manualVideo = inspection.media.formats.firstOrNull { it.kind == MediaKind.VIDEO }
            val manualAudio = inspection.media.formats.firstOrNull { it.kind == MediaKind.AUDIO }
            val manualFormatId = when {
                manualVideo != null && manualAudio != null -> "${manualVideo.id}+${manualAudio.id}"
                manualVideo != null -> manualVideo.id
                manualAudio != null -> manualAudio.id
                else -> null
            }
            val manualRequest = DownloadRequest(
                url = url,
                kind = MediaKind.VIDEO,
                formatId = manualFormatId,
                destinationDirectory = probeDir.absolutePath,
                fileNameTemplate = "Manual-Direct.%(ext)s",
            )
            val manualEvents = withTimeout(60_000) {
                engine.download(manualRequest, "native-manual-task").toList()
            }
            val manualCompleted = assertIs<DownloadEvent.Completed>(manualEvents.last(), "Events: $manualEvents")
            val manualFile = File(manualCompleted.filePath)
            assertTrue(manualFile.isFile && manualFile.length() > 0)
            assertEquals("mp4", manualFile.extension)

            val manualMeta = assertNotNull(probe.probe(manualFile.absolutePath))
            assertEquals("h264", manualMeta.videoCodec)
            assertEquals("aac", manualMeta.audioCodec)
        } finally {
            engine.close()
            server.stop(0)
        }
    }

    private fun createLocalHttpFixture(): Pair<File, HttpServer> {
        val locator = ExecutableLocator()
        val fixture = temporary.newFolder("fixture-native-" + System.nanoTime())
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

        File(fixture, "captions.vtt").writeText("WEBVTT\n\n00:00:00.000 --> 00:00:00.900\nLegenda de teste\n")
        File(fixture, "index.html").writeText(
            """<html><head><title>Fixture</title></head><body><video controls><source src="/media.mpd" type="application/dash+xml"><track kind="subtitles" srclang="pt" label="Português" src="/captions.vtt"></video></body></html>""",
        )

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
        return fixture to server
    }
}

private class SimulatedEngine(var autoEmitCompleted: Boolean = false) : com.openyt.core.domain.DownloadEngine {
    override suspend fun inspect(url: String): EngineResult<Inspection> =
        EngineResult.Failure(DownloadFailure.UnsupportedSource)

    override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = flow {
        emit(DownloadEvent.Starting)
        emit(DownloadEvent.Progress(percent = 25.0, speedBytesPerSecond = 1_000_000, etaSeconds = 10))
        if (autoEmitCompleted) {
            val outputPath = File(request.destinationDirectory, "Modulo 01 - Aula.mp4").apply {
                parentFile?.mkdirs()
                createNewFile()
            }.absolutePath
            emit(DownloadEvent.Completed(outputPath, 10_000_000L))
        }
    }

    override suspend fun cancel(downloadId: String) = Unit

    override suspend fun capabilities(): EngineCapabilities = EngineCapabilities(
        engineName = "simulated",
        engineVersion = "1.0",
        supportsPause = true,
        supportsSubtitles = true,
    )

    override fun close() = Unit
}
