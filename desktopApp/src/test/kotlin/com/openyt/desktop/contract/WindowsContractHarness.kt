package com.openyt.desktop.contract

import com.openyt.core.application.FileAvailability
import com.openyt.core.application.MediaHealthStatus
import com.openyt.core.application.OpenYtEntryLink
import com.openyt.core.application.OpenYtLink
import com.openyt.core.application.OpenYtRoute
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackSessionListener
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.RelinkContentMode
import com.openyt.core.application.StudyAbRepeatController
import com.openyt.core.application.StudyCaptureAndNavigationService
import com.openyt.core.application.StudyExportOptions
import com.openyt.core.application.StudyHostLinkCapability
import com.openyt.core.application.StudyMarkdownExporter
import com.openyt.core.application.StudyService
import com.openyt.core.domain.AbRepeatResult
import com.openyt.core.domain.AbRepeatStatus
import com.openyt.core.domain.AppLanguage
import com.openyt.core.domain.Collection
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.ShareableMedia
import com.openyt.core.domain.StudyCaptureResult
import com.openyt.core.domain.StudyNavigationResult
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyResult
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightOfflineAssetRepository
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.createLibraryRecoveryRuntime
import com.openyt.data.createOpenYtDatabase
import com.openyt.desktop.DesktopDeepLinks
import com.openyt.desktop.downloader.ExecutableLocator
import com.openyt.desktop.player.DesktopPlaybackLauncher
import com.openyt.desktop.player.DesktopVlcRuntime
import com.openyt.desktop.share.DesktopMediaActions
import com.openyt.ui.i18n.EnStrings
import com.sun.jna.NativeLibrary
import java.awt.Frame
import java.awt.Robot
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import uk.co.caprica.vlcj.binding.lib.LibVlc
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/** Test-only alternate entry point, run with the installed launcher/runtime/jars. */
object WindowsContractHarness {
    @JvmStatic fun main(args: Array<String>) {
        try {
            check(System.getProperty("os.name").contains("Windows", true))
            val file = File(args[1])
            when (args[0]) {
                "open" -> check(DesktopMediaActions().open(ShareableMedia(file.path, "Regression", MediaKind.VIDEO)))
                "reveal" -> check(DesktopMediaActions().reveal(ShareableMedia(file.path, "Regression", MediaKind.VIDEO)))
                "play" -> play(file)
                "study" -> study(file)
                "recovery" -> recovery(file)
                else -> error("Unknown contract")
            }
            println("PASS ${args[0]} with the installed application runtime")
            exitProcess(0)
        } catch (e: Throwable) {
            e.printStackTrace()
            exitProcess(1)
        }
    }

    private fun play(file: File) {
        val directory = checkNotNull(DesktopVlcRuntime.bundledDirectory()) { "Packaged VLC runtime missing" }
        val launcher = DesktopPlaybackLauncher()
        try {
            // Capture Java's inherited environment before native discovery clears it.
            check(System.getenv("VLC_PLUGIN_PATH")?.contains("incompatible-vlc-plugins") == true) { "Conflicting inherited VLC environment was not tested" }
            check(launcher.isVlcAvailable()) { "Bundled VLC did not load" }
            val pluginPath = NativeLibrary.getInstance("msvcrt").getFunction("getenv").invokePointer(arrayOf("VLC_PLUGIN_PATH"))
            check(pluginPath == null || pluginPath.getString(0).isEmpty()) { "External plugins remain in VLC's native environment" }
            check(NativeLibrary.getInstance("libvlc").file.canonicalFile.parentFile == directory.canonicalFile) { "Loaded a machine VLC instead of the package" }
            check(LibVlc.libvlc_get_version().startsWith("3.0.24")) { "Unexpected native VLC version" }
            println("Loaded bundled VLC ${LibVlc.libvlc_get_version()}; inherited external plugins excluded")
            val process = ProcessBuilder(checkNotNull(ExecutableLocator().findFfmpeg()),
                "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=60:size=320x180:rate=10",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", file.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            launcher.play(listOf(PlayableMedia(file.path, "Windows playback contract", MediaKind.VIDEO)), 0)
            var frame: JFrame? = null
            val deadline = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait {
                    frame = Frame.getFrames().filterIsInstance<JFrame>().firstOrNull { it.title == "OpenYT — Windows playback contract" && it.isVisible }
                }
                val player = frame?.contentPane?.components?.filterIsInstance<EmbeddedMediaPlayerComponent>()?.firstOrNull()?.mediaPlayer()
                if (player?.status()?.isPlaying == true && player.status().time() >= 500) break
                Thread.sleep(100)
            }
            val window = checkNotNull(frame) { "Playback window never opened" }
            val player = window.contentPane.components.filterIsInstance<EmbeddedMediaPlayerComponent>().single().mediaPlayer()
            check(player.status().isPlaying && player.status().time() >= 500) {
                "Video did not advance: state=${player.status().state()}, time=${player.status().time()}, length=${player.status().length()}"
            }
            launcher.toggleMiniPlayer()
            SwingUtilities.invokeAndWait {}
            check(window.isAlwaysOnTop && window.width == 420)
            SwingUtilities.invokeAndWait { window.toFront(); window.requestFocus() }
            val surface = window.contentPane.components.filterIsInstance<EmbeddedMediaPlayerComponent>().single().videoSurfaceComponent()
            val robot = Robot()
            val renderDeadline = System.nanoTime() + 15_000_000_000L
            var rendered = false
            var image: BufferedImage? = null
            var bounds = Rectangle()
            do {
                SwingUtilities.invokeAndWait { bounds = Rectangle(surface.locationOnScreen, surface.size) }
                image = robot.createScreenCapture(bounds)
                rendered = hasTestPattern(image)
                if (!rendered) Thread.sleep(200)
            } while (!rendered && System.nanoTime() < renderDeadline)
            ImageIO.write(image, "png", File(file.parentFile, "windows-video-surface.png"))
            ImageIO.write(robot.createScreenCapture(window.bounds), "png", File(file.parentFile, "windows-playback.png"))
            check(rendered) { "Visible video surface at $bounds did not display the decoded RGB test pattern" }
            println("Visible decoded RGB test pattern at $bounds; mini-player is 420px and always on top")
            check(file.isFile && file.length() > 0)
        } finally { launcher.close() }
    }

    private fun hasTestPattern(image: BufferedImage): Boolean {
        var samples = 0
        var red = 0; var green = 0; var blue = 0
        for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
            val rgb = image.getRGB(x, y)
            val r = rgb shr 16 and 255; val g = rgb shr 8 and 255; val b = rgb and 255
            samples++
            if (r > 180 && g < 80 && b < 80) red++
            if (g > 180 && r < 80 && b < 80) green++
            if (b > 180 && r < 80 && g < 80) blue++
        }
        // Explorer icons or the blue Windows wallpaper must not count as video.
        return samples > 0 && minOf(red, green, blue) > samples * 0.03
    }

    private fun study(file: File) = runBlocking<Unit> {
        val studyDir = File(file.parentFile, "study-contract").apply { mkdirs() }
        val dbFile = File(studyDir, "windows-study.db").apply { if (exists()) delete() }
        if (!file.isFile || file.length() == 0L) {
            val process = ProcessBuilder(
                checkNotNull(ExecutableLocator().findFfmpeg()),
                "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=16:size=320x180:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=16",
                "-g", "5", "-keyint_min", "5", "-sc_threshold", "0",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", "-y", file.absolutePath,
            ).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { out }
        }
        val initialHash = sha256Hex(file)
        val installedAppExe = System.getProperty("jpackage.app-path")
            ?.let { File(it).parentFile?.resolve("OpenYT.exe") }
            ?.takeIf { it.isFile }
            ?: file

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db1 = createOpenYtDatabase(dbFile.absolutePath)
        val downloadRepo1 = SqlDelightDownloadRepository(db1)
        val playbackStore1 = SqlDelightPlaybackStore(db1)
        val studyRepo1 = SqlDelightStudyRepository(db1)
        val launcher1 = DesktopPlaybackLauncher(playbackStore1, { EnStrings }, scope1)
        check(launcher1.isVlcAvailable()) { "Bundled VLC unavailable for Windows study contract" }
        val ab1 = StudyAbRepeatController(launcher1, scope1)
        var launcher2: DesktopPlaybackLauncher? = null
        var ab2: StudyAbRepeatController? = null
        var clock = 1_700_000_100_000L
        var seq = 0
        val studyService1 = StudyService(studyRepo1, nowEpochMillis = { ++clock }, nextId = { "win-study-${++seq}" })

        val entry = LibraryEntry(
            id = "win-study & item 01",
            title = "Windows <Study> #1",
            kind = MediaKind.VIDEO,
            filePath = file.absolutePath,
            sizeBytes = file.length(),
            mediaRevision = MediaRevision(file.length(), file.lastModified(), initialHash),
        )
        try {
            downloadRepo1.insertLibraryEntry(entry)
            downloadRepo1.insertCollection(Collection("win-col", "Windows M2"))
            downloadRepo1.addEntryToCollection("win-col", entry.id)
            val nav1 = StudyCaptureAndNavigationService(
                studyService = studyService1,
                playbackLauncher = launcher1,
                playbackStore = playbackStore1,
                entryProvider = { downloadRepo1.loadLibrary() },
                fileAvailability = FileAvailability { File(it).isFile },
                nowEpochMillis = { ++clock },
            )

            val media = PlayableMedia(entry.filePath, entry.title, entry.kind, entry.id)
            check(launcher1.openAt(listOf(media), 0, startPositionMillis = 3_000L)) { "Failed to open media at 3000ms" }
            var ready = false
            repeat(150) {
                val s = launcher1.captureSnapshot()
                if (s != null && s.state == PlaybackSessionState.PLAYING && (s.knownDurationMillis ?: 0L) >= 14_000L) {
                    ready = true
                    return@repeat
                }
                delay(100)
            }
            check(ready) { "Windows VLC player did not become ready with known duration" }

            val captured = nav1.captureAndCreateNote("Windows note [safe] & <tag>", expectedEntryId = entry.id)
            check(captured is StudyCaptureResult.Success<StudyNote>) { "Note capture failed: $captured" }
            val note = captured.value

            val savedRange = (studyService1.saveRange(entry.id, 2_000L, 4_000L, "Win loop", 60_000L)
                as StudyResult.Success<SavedStudyRange>).value

            val positions = CopyOnWriteArrayList<Pair<Int, Long>>()
            val unreg = launcher1.registerSessionListener(PlaybackSessionListener { s ->
                if (s != null && s.state == PlaybackSessionState.PLAYING) {
                    positions += ab1.state.value.loopCount to s.positionMillis
                }
            })
            check(ab1.activateSavedRange(savedRange, startImmediately = true) is AbRepeatResult.Success)
            val deadline = System.currentTimeMillis() + 35_000L
            var checkedMini = false
            while (ab1.state.value.loopCount < 5 && System.currentTimeMillis() < deadline) {
                if (ab1.state.value.loopCount >= 1 && !checkedMini) {
                    checkedMini = true
                    launcher1.toggleMiniPlayer()
                    var win: JFrame? = null
                    SwingUtilities.invokeAndWait {
                        win = Frame.getFrames().filterIsInstance<JFrame>()
                            .firstOrNull { it.title.startsWith("OpenYT") && it.isVisible }
                    }
                    check(win != null && win!!.isAlwaysOnTop && win!!.width == 420) {
                        "Mini-player invariant failed during A/B loop"
                    }
                }
                delay(80)
            }
            check(ab1.state.value.loopCount >= 5) { "Expected >= 5 Windows A/B loops, got ${ab1.state.value.loopCount}" }
            repeat(25) {
                if (positions.any { it.first == 5 && it.second < 3_000L }) return@repeat
                delay(100)
            }
            var maxOvershoot = 0L
            var maxReturnDelta = 0L
            for (loopNum in 0..4) {
                val loopPositions = positions.filter { it.first == loopNum }.map { it.second }
                val over = ((loopPositions.maxOrNull() ?: 4_000L) - 4_000L).coerceAtLeast(0L)
                maxOvershoot = maxOf(maxOvershoot, over)
                check(over <= 1_000L) { "Windows loop $loopNum overshot by ${over}ms" }
            }
            for (loopNum in 1..5) {
                val loopPositions = positions.filter { it.first == loopNum }.map { it.second }
                check(loopPositions.isNotEmpty()) { "Missing Windows telemetry for loop $loopNum" }
                val delta = abs(loopPositions.minOrNull()!! - 2_000L)
                maxReturnDelta = maxOf(maxReturnDelta, delta)
                check(delta <= 1_000L) { "Windows loop $loopNum returned off target by ${delta}ms" }
            }
            println("NX-T06 Windows VLCJ 5-loop maxOvershootMs=$maxOvershoot maxReturnDeltaMs=$maxReturnDelta")
            unreg()
            launcher1.stop()
            delay(200)
            check(launcher1.state == PlaybackSessionState.IDLE && ab1.state.value.status == AbRepeatStatus.INACTIVE)

            check(studyService1.setEntryStudied(entry.id, true) is StudyResult.Success)
            playbackStore1.savePosition(
                entry.filePath,
                PlaybackCheckpoint(positionMillis = 59_000L, durationMillis = 60_000L, entryId = entry.id),
                entry.id,
            )
            check(playbackStore1.position(entry.filePath, entry.id)?.resumeMillis == 0L)
            ab1.close()
            launcher1.close()

            // Reopen database & playback on Windows cleanly
            val db2 = createOpenYtDatabase(dbFile.absolutePath)
            val downloadRepo2 = SqlDelightDownloadRepository(db2)
            val playbackStore2 = SqlDelightPlaybackStore(db2)
            val studyService2 = StudyService(SqlDelightStudyRepository(db2), nowEpochMillis = { ++clock })
            launcher2 = DesktopPlaybackLauncher(playbackStore2, { EnStrings }, scope2)
            ab2 = StudyAbRepeatController(launcher2, scope2)
            val nav2 = StudyCaptureAndNavigationService(
                studyService = studyService2,
                playbackLauncher = launcher2,
                playbackStore = playbackStore2,
                entryProvider = { downloadRepo2.loadLibrary() },
                fileAvailability = FileAvailability { File(it).isFile },
                nowEpochMillis = { ++clock },
            )
            delay(200)
            check(launcher2.state == PlaybackSessionState.IDLE && ab2.state.value.status == AbRepeatStatus.INACTIVE) {
                "Windows reopen must never auto-play or auto-loop"
            }
            val reopenedBundle = studyService2.loadEntryStudyBundle(entry.id)
            check(reopenedBundle.studyState.isStudied && reopenedBundle.notes.size == 1 && reopenedBundle.savedRanges.size == 1)
            check(studyService2.collectionProgress("win-col")?.studiedCount == 1)
            check(nav2.openNoteTimestamp(note.id) is StudyNavigationResult.Opened)

            var seekMatched = false
            repeat(80) {
                val s = launcher2.captureSnapshot()
                if (s != null && (s.state == PlaybackSessionState.PLAYING || s.state == PlaybackSessionState.PAUSED) &&
                    abs(s.positionMillis - note.timestampMillis) <= 1_000L
                ) {
                    seekMatched = true
                    return@repeat
                }
                delay(100)
            }
            check(seekMatched) { "Windows reopened note seek missed saved timestamp ${note.timestampMillis}" }

            // Protocol registration + Markdown export + link dispatch check
            val registered = DesktopDeepLinks.register(launcher = installedAppExe.absolutePath)
            val linkSupported = DesktopDeepLinks.isEntryLinkDispatchSupported(launcher = installedAppExe.absolutePath)
            check(registered && linkSupported) {
                "Windows openyt:// HKCU protocol handler verification failed (registered=$registered, linkSupported=$linkSupported, launcher=${installedAppExe.absolutePath})"
            }
            try {
                val md = StudyMarkdownExporter.exportEntryBundle(
                    entry = entry,
                    bundle = reopenedBundle,
                    options = StudyExportOptions(
                        includeLocalLinks = true,
                        language = AppLanguage.EN,
                    ),
                    hostCapability = StudyHostLinkCapability(true, "windows"),
                )
                check(!md.contains(file.absolutePath) && !md.contains("<tag>"))
                val exportFile = File(file.parentFile, "windows-study-export.md").apply { writeText(md) }
                check(exportFile.length() > 0L)
                val link = OpenYtEntryLink.create(entry.id, note.timestampMillis)!!
                check(link in md)
                val parsed = OpenYtLink.parseRoute(link) as OpenYtRoute.LibraryEntry
                check(parsed.entryId == entry.id && parsed.timestampMillis == note.timestampMillis)
                launcher2.stop()
                delay(150)
                check(nav2.openEntryAtTimestamp(parsed.entryId, parsed.timestampMillis ?: 0L) is StudyNavigationResult.Opened)
                launcher2.stop()
                launcher2.close()
            } finally {
                runCatching {
                    ProcessBuilder("reg", "delete", "HKCU\\Software\\Classes\\openyt", "/f")
                        .redirectErrorStream(true).start().waitFor()
                }
            }

            // Original media preservation across removal preview & study delete
            check(studyService2.previewEntryRemoval(entry.id).requiresConfirmationPrompt)
            downloadRepo2.deleteLibraryEntry(entry.id)
            check(studyService2.loadEntryStudyBundle(entry.id).notes.size == 1)
            studyService2.deleteAllStudyDataForEntry(entry.id)
            check(file.isFile && sha256Hex(file) == initialHash) {
                "Original Windows media file must stay unmodified after removal"
            }
        } finally {
            ab1.close()
            ab2?.close()
            launcher1.close()
            launcher2?.close()
            scope1.cancel()
            scope2.cancel()
        }
    }

    private fun recovery(sourceVideo: File) {
        check(DesktopVlcRuntime.bundledDirectory() != null) { "Packaged VLC runtime missing" }
        check(sourceVideo.isFile) { "Missing Windows recovery source video: ${sourceVideo.absolutePath}" }
        val workDir = java.nio.file.Files.createTempDirectory("openyt-win-recovery-").toFile()
        val origDir = File(workDir, "downloads").apply { mkdirs() }
        val movedDir = File(workDir, "moved/Subfolder & Notas").apply { mkdirs() }
        val origFile = File(origDir, sourceVideo.name)
        sourceVideo.copyTo(origFile, overwrite = true)
        val initialHash = sha256Hex(origFile)
        val revision = MediaRevision(origFile.length(), origFile.lastModified(), initialHash)

        val dbFile = File(workDir, "recovery-contract.db")
        val db = createOpenYtDatabase(dbFile.absolutePath)
        val repo = SqlDelightDownloadRepository(db)
        val offlineRepo = SqlDelightOfflineAssetRepository(db)
        val subIndexRepo = SqlDelightSubtitleIndexRepository(db)
        val studyRepo = SqlDelightStudyRepository(db)
        val playbackStore = SqlDelightPlaybackStore(db)

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val launcher1 = DesktopPlaybackLauncher(playbackStore, { EnStrings }, scope1)
        var launcher2: DesktopPlaybackLauncher? = null

        try {
            val entry = LibraryEntry(
                id = "win-rec-1",
                title = "Windows Recovery Fixture",
                kind = MediaKind.VIDEO,
                filePath = origFile.absolutePath,
                sizeBytes = origFile.length(),
                createdAtEpochMillis = System.currentTimeMillis(),
                mediaRevision = revision,
            )
            runBlocking {
                repo.insertLibraryEntry(entry)
                repo.insertCollection(Collection("col-win-rec", "Windows Study"))
                repo.addEntryToCollection("col-win-rec", entry.id)
                playbackStore.savePosition(origFile.absolutePath, PlaybackCheckpoint(3_000L, 60_000L, entry.id), entry.id)
                studyRepo.upsertNote(
                    StudyNote("win-note-rec", entry.id, 3_000L, null, "Preserved on Windows move", revision, 100L, 100L),
                )
            }

            // Open media in bundled Windows VLC, verify playing, then stop so NTFS file handle is released
            runBlocking {
                check(launcher1.openAt(listOf(PlayableMedia(origFile.absolutePath, entry.title, MediaKind.VIDEO, entryId = entry.id)), 0, 1_000L))
                var started = false
                repeat(120) {
                    val snap = launcher1.captureSnapshot()
                    if (snap?.state == PlaybackSessionState.PLAYING) {
                        started = true
                        return@repeat
                    }
                    delay(100)
                }
                check(started) { "Windows VLC did not reach PLAYING before recovery stop" }
                launcher1.stop()
                delay(350)
            }

            val movedFile = File(movedDir, "Moved-${origFile.name}")
            java.nio.file.Files.move(origFile.toPath(), movedFile.toPath())

            val runtime = createLibraryRecoveryRuntime(
                database = db,
                downloadRepository = repo,
                offlineAssetRepository = offlineRepo,
                subtitleIndexRepository = subIndexRepo,
                studyRepository = studyRepo,
                stagingRoot = File(workDir, "staging"),
                ownedRecoveryRootProvider = { origDir.absolutePath },
                playbackSessionControl = launcher1,
            )

            runBlocking {
                val health = runtime.controller.analyzeHealth()
                check(health.entries.single().status == MediaHealthStatus.MISSING) {
                    "Expected MISSING after Windows move, got ${health.entries.single().status}"
                }
                val scan = runtime.controller.scanCandidates(entry, listOf(movedDir.absolutePath), emptyList())
                check(scan.exactMatches.size == 1) { "Expected 1 exact match after Windows move, got ${scan.exactMatches.size}" }
                val plan = runtime.controller.buildRelinkPlan(entry, scan.exactMatches.single()).getOrThrow()
                check(plan.contentMode == RelinkContentMode.IDENTICAL_CONTENT && !plan.requiresManualConfirmation)
                val receipt = runtime.controller.executeRelink(plan, confirmedContentChange = false).getOrThrow()
                check(receipt.finalPath == movedFile.absolutePath)
            }

            // Reopen DB & verify context + playback + Windows Explorer reveal on moved file
            val reopenedDb = createOpenYtDatabase(dbFile.absolutePath)
            val reopenedRepo = SqlDelightDownloadRepository(reopenedDb)
            val reopenedEntry = runBlocking { reopenedRepo.loadLibrary().single() }
            check(reopenedEntry.id == entry.id && reopenedEntry.filePath == movedFile.absolutePath)
            val reopenedCheckpoint = runBlocking { SqlDelightPlaybackStore(reopenedDb).position(movedFile.absolutePath, entry.id) }
            check(reopenedCheckpoint?.positionMillis == 3_000L) { "Windows relink checkpoint lost: $reopenedCheckpoint" }
            val reopenedBundle = runBlocking { SqlDelightStudyRepository(reopenedDb).loadEntryStudyBundle(entry.id) }
            check(reopenedBundle.notes.single().body == "Preserved on Windows move" && !reopenedBundle.hasStaleAnchors)
            check(sha256Hex(movedFile) == initialHash)

            launcher2 = DesktopPlaybackLauncher(SqlDelightPlaybackStore(reopenedDb), { EnStrings }, scope2)
            runBlocking {
                check(launcher2.openAt(listOf(PlayableMedia(movedFile.absolutePath, entry.title, MediaKind.VIDEO, entryId = entry.id)), 0, 3_000L))
                var reopenedPlay = false
                repeat(120) {
                    val snap = launcher2.captureSnapshot()
                    if (snap?.state == PlaybackSessionState.PLAYING) {
                        reopenedPlay = true
                        return@repeat
                    }
                    delay(100)
                }
                check(reopenedPlay) { "Windows VLC failed to play relinked file at ${movedFile.absolutePath}" }
                launcher2.stop()
                delay(200)
            }

            check(DesktopMediaActions().reveal(ShareableMedia(movedFile.absolutePath, entry.title, MediaKind.VIDEO))) {
                "Windows Explorer reveal failed on moved recovered file"
            }
            println("PASS NX-R06 Windows VLC handle release, NTFS move, SQLite relink reopen, and Explorer reveal")
        } finally {
            launcher1.close()
            launcher2?.close()
            scope1.cancel()
            scope2.cancel()
            workDir.deleteRecursively()
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
