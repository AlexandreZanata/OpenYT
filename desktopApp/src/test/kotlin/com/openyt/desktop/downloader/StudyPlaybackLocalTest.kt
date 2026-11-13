package com.openyt.desktop.downloader

import com.openyt.core.application.FileAvailability
import com.openyt.core.application.OpenYtEntryLink
import com.openyt.core.application.OpenYtLink
import com.openyt.core.application.OpenYtRoute
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackSessionListener
import com.openyt.core.application.PlaybackSessionState
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
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyCaptureResult
import com.openyt.core.domain.StudyNavigationResult
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyResult
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.desktop.player.DesktopPlaybackLauncher
import com.openyt.ui.i18n.PtBrStrings
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Full NX-T06 end-to-end study contract on Linux Desktop:
 * create live notes & bookmark -> run 5+ real VLCJ A/B loops (recording
 * overshoot/return tolerances) with mini-player toggle & pause preservation ->
 * mark studied & confirm collection progress survives completion reset ->
 * close & reopen SQLite database offline (confirming zero auto-play/auto-loop) ->
 * reopen saved note timestamp at exact time -> export safe Markdown ->
 * dispatch parsed openyt://library/entry/<id>?t=<ms> route -> confirm
 * removal preview + study purge never touches physical media hashes.
 */
class StudyPlaybackLocalTest {

    @Test
    fun fullOfflineStudyLifecycleLoopToleranceExportDeepLinkAndRemovalPreservation() = runBlocking<Unit> {
        assumeTrue(System.getenv("OPENYT_RUN_PLAYBACK_TESTS") == "1")
        val directory = Files.createTempDirectory("openyt-study-e2e-").toFile()
        val dbFile = File(directory, "study-e2e.db")
        val fixture = File(directory, "Estudo Kotlin #1.mp4")

        val ffmpeg = assertNotNull(
            ExecutableLocator().findFfmpeg(),
            "Real study playback requires a prepared FFmpeg executable",
        )
        val process = ProcessBuilder(
            ffmpeg,
            "-v", "error",
            "-f", "lavfi", "-i", "testsrc=duration=16:size=320x180:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=16",
            "-g", "5", "-keyint_min", "5", "-sc_threshold", "0",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast",
            "-c:a", "aac", "-shortest",
            fixture.absolutePath,
        ).redirectErrorStream(true).start()
        val diag = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), diag)
        val initialHash = sha256Hex(fixture)

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db1 = createOpenYtDatabase(dbFile.absolutePath)
        val downloadRepo1 = SqlDelightDownloadRepository(db1)
        val playbackStore1 = SqlDelightPlaybackStore(db1)
        val studyRepo1 = SqlDelightStudyRepository(db1)
        val launcher1 = DesktopPlaybackLauncher(playbackStore1, { PtBrStrings }, scope1)
        assertTrue(launcher1.isVlcAvailable(), "libvlc must be installed for NX-T06")
        val abController1 = StudyAbRepeatController(launcher1, scope1)

        var clock = 1_700_000_000_000L
        var idSeq = 0
        val studyService1 = StudyService(
            repository = studyRepo1,
            nowEpochMillis = { clock },
            nextId = { "e2e-study-${++idSeq}" },
        )

        val entry1 = LibraryEntry(
            id = "study & item 01",
            title = "Clean <Study> [Session] #1",
            kind = MediaKind.VIDEO,
            filePath = fixture.absolutePath,
            sizeBytes = fixture.length(),
            mediaRevision = MediaRevision(fixture.length(), fixture.lastModified(), initialHash),
        )
        val entry2 = LibraryEntry(
            id = "study-item-02",
            title = "Companion Lesson 02",
            kind = MediaKind.AUDIO,
            filePath = fixture.absolutePath,
            sizeBytes = fixture.length(),
            mediaRevision = MediaRevision(fixture.length(), fixture.lastModified(), initialHash),
        )

        var savedNote: StudyNote
        var savedBookmark: StudyBookmark
        var savedRange: SavedStudyRange
        var launcher2: DesktopPlaybackLauncher? = null
        var abController2: StudyAbRepeatController? = null
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            downloadRepo1.insertLibraryEntry(entry1)
            downloadRepo1.insertLibraryEntry(entry2)
            downloadRepo1.insertCollection(Collection(id = "col-study", name = "M2 Study"))
            downloadRepo1.addEntryToCollection("col-study", entry1.id)
            downloadRepo1.addEntryToCollection("col-study", entry2.id)

            val nav1 = StudyCaptureAndNavigationService(
                studyService = studyService1,
                playbackLauncher = launcher1,
                playbackStore = playbackStore1,
                entryProvider = { downloadRepo1.loadLibrary() },
                fileAvailability = FileAvailability { File(it).isFile },
                nowEpochMillis = { ++clock },
            )

            val media1 = PlayableMedia(entry1.filePath, entry1.title, entry1.kind, entry1.id)
            assertTrue(launcher1.openAt(listOf(media1), 0, startPositionMillis = 3_200L))
            awaitPlayingWithDuration(launcher1, minDurationMs = 14_000L)

            val captured = nav1.captureAndCreateNote(
                body = "Key [insight] <script>alert(1)</script> & resumo",
                expectedEntryId = entry1.id,
            )
            savedNote = assertIs<StudyCaptureResult.Success<StudyNote>>(captured).value
            assertEquals(entry1.id, savedNote.entryId)
            assertTrue(savedNote.timestampMillis >= 2_500L)

            val bmRes = nav1.addOrUpdateBookmark(
                entryId = entry1.id,
                timestampMillis = 6_000L,
                label = "Checkpoint alpha",
            )
            savedBookmark = assertIs<StudyCaptureResult.Success<StudyBookmark>>(bmRes).value

            savedRange = assertIs<StudyResult.Success<SavedStudyRange>>(
                studyService1.saveRange(entry1.id, 2_000L, 4_000L, "Drill 2s-4s", 16_000L),
            ).value

            // Run >= 5 real A/B repeat loops + mini-player + pause check
            val positions = CopyOnWriteArrayList<Pair<Int, Long>>()
            val unregister1 = launcher1.registerSessionListener(PlaybackSessionListener { snap ->
                if (snap != null && snap.state == PlaybackSessionState.PLAYING) {
                    positions += abController1.state.value.loopCount to snap.positionMillis
                }
            })

            val activated = abController1.activateSavedRange(savedRange, startImmediately = true)
            assertTrue(activated is AbRepeatResult.Success)

            var checkedMiniAndPause = false
            val loopDeadline = System.currentTimeMillis() + 35_000L
            while (abController1.state.value.loopCount < 5 && System.currentTimeMillis() < loopDeadline) {
                if (abController1.state.value.loopCount >= 2 && !checkedMiniAndPause) {
                    checkedMiniAndPause = true
                    launcher1.toggleMiniPlayer()
                    var win: JFrame? = null
                    SwingUtilities.invokeAndWait {
                        win = Frame.getFrames().filterIsInstance<JFrame>()
                            .firstOrNull { it.title.startsWith("OpenYT") && it.isVisible }
                    }
                    assertNotNull(win)
                    assertTrue(win!!.isAlwaysOnTop && win!!.width == 420)

                    launcher1.pause()
                    delay(300)
                    assertEquals(AbRepeatStatus.PAUSED, abController1.state.value.status)
                    launcher1.play()
                    delay(150)
                    assertEquals(AbRepeatStatus.ACTIVE, abController1.state.value.status)
                }
                delay(80)
            }
            assertTrue(abController1.state.value.loopCount >= 5, "Expected >= 5 real A/B loops")
            repeat(25) {
                if (positions.any { it.first == 5 && it.second < 3_000L }) return@repeat
                delay(100)
            }

            var maxOvershootMs = 0L
            var maxReturnDeltaMs = 0L
            for (loopNum in 0..4) {
                val loopPositions = positions.filter { it.first == loopNum }.map { it.second }
                val overshoot = ((loopPositions.maxOrNull() ?: 4_000L) - 4_000L).coerceAtLeast(0L)
                maxOvershootMs = maxOf(maxOvershootMs, overshoot)
                assertTrue(overshoot <= 1_000L, "Loop $loopNum overshot by ${overshoot}ms")
            }
            for (loopNum in 1..5) {
                val loopPositions = positions.filter { it.first == loopNum }.map { it.second }
                assertTrue(loopPositions.isNotEmpty(), "Missing loop $loopNum return sample")
                val delta = abs(loopPositions.minOrNull()!! - 2_000L)
                maxReturnDeltaMs = maxOf(maxReturnDeltaMs, delta)
                assertTrue(delta <= 1_000L, "Loop $loopNum return delta ${delta}ms exceeded 1000ms")
            }
            println("NX-T06 Linux VLCJ measured 5-loop maxOvershootMs=$maxOvershootMs maxReturnDeltaMs=$maxReturnDeltaMs")

            unregister1()
            launcher1.stop()
            delay(200)
            assertEquals(PlaybackSessionState.IDLE, launcher1.state)
            assertEquals(AbRepeatStatus.INACTIVE, abController1.state.value.status)

            // Mark studied + simulate completion checkpoint reset (resumeMillis = 0)
            val studiedRes = studyService1.setEntryStudied(entry1.id, true)
            assertTrue(studiedRes is StudyResult.Success)
            playbackStore1.savePosition(
                entry1.filePath,
                PlaybackCheckpoint(positionMillis = 15_000L, durationMillis = 16_000L, entryId = entry1.id),
                entry1.id,
            )
            assertEquals(0L, playbackStore1.position(entry1.filePath, entry1.id)?.resumeMillis)
            val progressBeforeClose = studyService1.collectionProgress("col-study")
            assertNotNull(progressBeforeClose)
            assertEquals(1, progressBeforeClose.studiedCount)
            assertEquals(2, progressBeforeClose.totalMemberCount)

            // Close session 1 before offline reopen
            abController1.close()
            launcher1.close()

            // Session 2: Offline reopen from same SQLite DB
            val db2 = createOpenYtDatabase(dbFile.absolutePath)
            val downloadRepo2 = SqlDelightDownloadRepository(db2)
            val playbackStore2 = SqlDelightPlaybackStore(db2)
            val studyRepo2 = SqlDelightStudyRepository(db2)
            val studyService2 = StudyService(studyRepo2, nowEpochMillis = { ++clock })
            launcher2 = DesktopPlaybackLauncher(playbackStore2, { PtBrStrings }, scope2)
            abController2 = StudyAbRepeatController(launcher2, scope2)
            val nav2 = StudyCaptureAndNavigationService(
                studyService = studyService2,
                playbackLauncher = launcher2,
                playbackStore = playbackStore2,
                entryProvider = { downloadRepo2.loadLibrary() },
                fileAvailability = FileAvailability { File(it).isFile },
                nowEpochMillis = { ++clock },
            )

            delay(200)
            // Restart must NEVER auto-play or auto-loop
            assertEquals(PlaybackSessionState.IDLE, launcher2.state)
            assertEquals(AbRepeatStatus.INACTIVE, abController2.state.value.status)

            val bundleAfterReopen = studyService2.loadEntryStudyBundle(entry1.id)
            assertTrue(bundleAfterReopen.studyState.isStudied)
            assertEquals(1, bundleAfterReopen.notes.size)
            assertEquals(1, bundleAfterReopen.bookmarks.size)
            assertEquals(1, bundleAfterReopen.savedRanges.size)
            assertEquals(1, studyService2.collectionProgress("col-study")?.studiedCount)

            // Reopen note -> player jumps to exact saved note timestamp
            val openNoteRes = nav2.openNoteTimestamp(savedNote.id)
            assertIs<StudyNavigationResult.Opened>(openNoteRes)
            awaitPlayingAtTarget(launcher2, savedNote.timestampMillis, toleranceMs = 1_000L)

            // Export safe Markdown + extract & dispatch openyt://library/entry/<encoded-id>?t=<ms>
            val markdown = StudyMarkdownExporter.exportEntryBundle(
                entry = entry1,
                bundle = bundleAfterReopen,
                options = StudyExportOptions(
                    includeLocalLinks = true,
                    language = AppLanguage.EN,
                ),
                hostCapability = StudyHostLinkCapability(supportsClickableEntryLinks = true, hostLabel = "linux"),
            )
            assertFalse(fixture.absolutePath in markdown, "Markdown export must not leak private filesystem paths")
            assertFalse("<script>" in markdown, "HTML tags must be escaped in Markdown export")
            val exportedFile = File(directory, StudyMarkdownExporter.suggestedFilename(entry1.title)).apply {
                writeText(markdown)
            }
            assertTrue(exportedFile.length() > 0L)

            val expectedLink = OpenYtEntryLink.create(entry1.id, savedNote.timestampMillis)
            assertNotNull(expectedLink)
            assertTrue(expectedLink in markdown, "Exported Markdown must contain encoded local entry link")

            val parsedRoute = assertIs<OpenYtRoute.LibraryEntry>(OpenYtLink.parseRoute(expectedLink))
            assertEquals(entry1.id, parsedRoute.entryId)
            assertEquals(savedNote.timestampMillis, parsedRoute.timestampMillis)

            launcher2.stop()
            delay(150)
            val linkOpenRes = nav2.openEntryAtTimestamp(
                entryId = parsedRoute.entryId,
                timestampMillis = parsedRoute.timestampMillis ?: 0L,
            )
            assertIs<StudyNavigationResult.Opened>(linkOpenRes)
            awaitPlayingAtTarget(launcher2, savedNote.timestampMillis, toleranceMs = 1_000L)
            launcher2.stop()
            launcher2.close()

            // Removal preview + purge study data keeps original media untouched on disk
            val preview = studyService2.previewEntryRemoval(entry1.id)
            assertTrue(preview.requiresConfirmationPrompt)
            assertEquals(1, preview.noteCount)
            assertEquals(1, preview.bookmarkCount)
            assertEquals(1, preview.savedRangeCount)
            assertEquals(0, studyService2.deleteLibraryEntryWithoutCascadeTestCheck(downloadRepo2, studyService2, entry1.id))

            assertTrue(fixture.exists(), "Original media file must survive library & study removal")
            assertEquals(initialHash, sha256Hex(fixture), "Original media file bytes must remain unmodified")
            assertEquals(savedBookmark.id, bundleAfterReopen.bookmarks.single().id)
        } finally {
            abController1.close()
            abController2?.close()
            launcher1.close()
            launcher2?.close()
            scope1.cancel()
            scope2.cancel()
            directory.deleteRecursively()
        }
    }

    private suspend fun StudyService.deleteLibraryEntryWithoutCascadeTestCheck(
        downloadRepo: SqlDelightDownloadRepository,
        studyService: StudyService,
        entryId: String,
    ): Int {
        downloadRepo.deleteLibraryEntry(entryId)
        assertEquals(1, loadEntryStudyBundle(entryId).notes.size, "Deleting library_entry must not cascade study notes")
        studyService.deleteAllStudyDataForEntry(entryId)
        return loadEntryStudyBundle(entryId).notes.size
    }

    private suspend fun awaitPlayingWithDuration(
        launcher: DesktopPlaybackLauncher,
        minDurationMs: Long,
    ) {
        repeat(150) {
            val snap = launcher.captureSnapshot()
            if (snap != null &&
                snap.state == PlaybackSessionState.PLAYING &&
                (snap.knownDurationMillis ?: 0L) >= minDurationMs
            ) {
                return
            }
            delay(100)
        }
        error("Player did not enter PLAYING state with duration >= ${minDurationMs}ms")
    }

    private suspend fun awaitPlayingAtTarget(
        launcher: DesktopPlaybackLauncher,
        targetMillis: Long,
        toleranceMs: Long,
    ) {
        repeat(80) {
            val snap = launcher.captureSnapshot()
            if (snap != null &&
                (snap.state == PlaybackSessionState.PLAYING || snap.state == PlaybackSessionState.PAUSED) &&
                abs(snap.positionMillis - targetMillis) <= toleranceMs
            ) {
                return
            }
            delay(100)
        }
        val finalPos = launcher.captureSnapshot()?.positionMillis
        error("Player did not reach ${targetMillis}ms within ${toleranceMs}ms (actual=$finalPos)")
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
