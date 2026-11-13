package com.openyt.desktop.downloader

import com.openyt.core.application.MemoryPlaybackStore
import com.openyt.core.application.MemoryStudyRepository
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackSessionListener
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.StudyAbRepeatController
import com.openyt.core.application.StudyService
import com.openyt.core.domain.AbRepeatResult
import com.openyt.core.domain.AbRepeatStatus
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyResult
import com.openyt.desktop.player.DesktopPlaybackLauncher
import com.openyt.ui.i18n.PtBrStrings
import java.awt.Frame
import java.awt.Robot
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
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
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopPlaybackLocalTest {
    @Test
    fun floatingWindowPlaysAndSavesAResumePosition() = runBlocking {
        assumeTrue(System.getenv("OPENYT_RUN_PLAYBACK_TESTS") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryPlaybackStore()
        val launcher = DesktopPlaybackLauncher(store, { PtBrStrings }, scope)
        assertTrue(launcher.isVlcAvailable(), "libvlc must be installed for this contract")
        val directory = Files.createTempDirectory("openyt-playback-").toFile()
        try {
            val file = File(directory, "Vídeo de teste.mp4")
            val process = ProcessBuilder(
                assertNotNull(ExecutableLocator().findFfmpeg(), "Real playback requires a prepared FFmpeg executable"),
                "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=30:size=320x180:rate=10",
                "-pix_fmt", "yuv420p", "-c:v", "libx264", file.absolutePath,
            ).redirectErrorStream(true).start()
            val diagnostic = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), diagnostic)
            launcher.play(listOf(PlayableMedia(file.absolutePath, "Playback regression", MediaKind.VIDEO)), 0)
            var frame: JFrame? = null
            repeat(100) {
                SwingUtilities.invokeAndWait {
                    frame = Frame.getFrames().filterIsInstance<JFrame>().firstOrNull { it.title.startsWith("OpenYT") && it.isVisible }
                }
                if (frame != null) return@repeat
                Thread.sleep(100)
            }
            val window = assertNotNull(frame)
            val player = window.contentPane.components.filterIsInstance<EmbeddedMediaPlayerComponent>().single().mediaPlayer()
            repeat(100) {
                if (player.status().isPlaying) return@repeat
                Thread.sleep(100)
            }
            assertTrue(player.status().isPlaying, "state=${player.status().state()}, length=${player.status().length()}, time=${player.status().time()}, media=${player.media().info().mrl() != null}")
            launcher.toggleMiniPlayer()
            SwingUtilities.invokeAndWait {}
            assertTrue(window.isAlwaysOnTop)
            assertEquals(420, window.width)
            Robot().waitForIdle()
            Thread.sleep(750)
            val screenshot = File("build/validation/mini-player.png").apply { parentFile.mkdirs() }
            ImageIO.write(Robot().createScreenCapture(window.bounds), "png", screenshot)
            player.controls().setTime(3_000)
            Thread.sleep(250)
            SwingUtilities.invokeAndWait {
                window.contentPane.components.filterIsInstance<javax.swing.JToolBar>().single().components
                    .filterIsInstance<javax.swing.JButton>().single { it.toolTipText == PtBrStrings.features[com.openyt.ui.i18n.FeatureText.STOP] }.doClick()
            }
            Thread.sleep(750)
            assertTrue(assertNotNull(store.position(file.absolutePath)).resumeMillis in 2_000..5_000, "Stop must preserve the checkpoint")
            launcher.close()
            assertTrue(assertNotNull(store.position(file.absolutePath)).resumeMillis in 2_000..5_000)
            assertEquals(file.absolutePath, store.loadQueue()?.current?.path)
            assertTrue(launcher.resumeLastSession())
            Thread.sleep(1_500)
            launcher.close()
            assertTrue(assertNotNull(store.position(file.absolutePath)).resumeMillis >= 2_000, "Reopening must not reset the checkpoint")
        } finally {
            launcher.close()
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun repeatsAbRangeFiveTimesWithinToleranceAndClearsOnStopWithoutRestartAutoPlay() = runBlocking<Unit> {
        assumeTrue(System.getenv("OPENYT_RUN_PLAYBACK_TESTS") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryPlaybackStore()
        val launcher = DesktopPlaybackLauncher(store, { PtBrStrings }, scope)
        assertTrue(launcher.isVlcAvailable(), "libvlc must be installed for NX-T03 contract")

        val directory = Files.createTempDirectory("openyt-ab-repeat-").toFile()
        val studyRepo = MemoryStudyRepository()
        val studyService = StudyService(studyRepo, nowEpochMillis = { 10_000L }, nextId = { "desk-range-1" })
        val controller = StudyAbRepeatController(launcher, scope)

        try {
            val file = File(directory, "ab_fixture.mp4")
            val process = ProcessBuilder(
                assertNotNull(ExecutableLocator().findFfmpeg(), "Real playback requires a prepared FFmpeg executable"),
                "-v", "error",
                "-f", "lavfi", "-i", "testsrc=duration=16:size=320x180:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=16",
                "-g", "5", "-keyint_min", "5", "-sc_threshold", "0",
                "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast",
                "-c:a", "aac", "-shortest",
                file.absolutePath,
            ).redirectErrorStream(true).start()
            val diagnostic = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), diagnostic)

            val entry = LibraryEntry(
                id = "entry-desk-ab-1",
                title = "Desktop AB Fixture",
                kind = MediaKind.VIDEO,
                filePath = file.absolutePath,
                sizeBytes = file.length(),
            )
            studyRepo.putLibraryEntry(entry)
            val savedRange = (studyService.saveRange(entry.id, 2_000L, 4_000L, "Loop segment", 16_000L)
                as StudyResult.Success<SavedStudyRange>).value

            val positions = CopyOnWriteArrayList<Pair<Int, Long>>()
            val unregister = launcher.registerSessionListener(PlaybackSessionListener { snap ->
                if (snap != null && snap.state == PlaybackSessionState.PLAYING) {
                    positions += controller.state.value.loopCount to snap.positionMillis
                }
            })

            val media = PlayableMedia(file.absolutePath, entry.title, MediaKind.VIDEO, entry.id)
            assertTrue(launcher.openAt(listOf(media), 0, startPositionMillis = 2_000L))

            // Wait until PLAYING with known positive duration
            var ready = false
            repeat(150) {
                val snap = launcher.captureSnapshot()
                if (snap != null && snap.state == PlaybackSessionState.PLAYING && (snap.knownDurationMillis ?: 0L) >= 14_000L) {
                    ready = true
                    return@repeat
                }
                delay(100)
            }
            assertTrue(ready, "Player did not reach READY/PLAYING with known duration")

            // Saved range never auto-started on load
            assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)

            // Explicitly activate [2_000 ms, 4_000 ms]
            val activated = controller.activateSavedRange(savedRange, startImmediately = true)
            assertTrue(activated is AbRepeatResult.Success, "activateSavedRange failed: $activated")
            assertEquals(AbRepeatStatus.ACTIVE, controller.state.value.status)

            // Wait for loop 2, toggle mini-player, pause/resume preservation, then finish 5 loops
            val deadline = System.currentTimeMillis() + 30_000L
            var toggledMini = false
            var checkedPause = false
            while (controller.state.value.loopCount < 5 && System.currentTimeMillis() < deadline) {
                val loops = controller.state.value.loopCount
                if (loops >= 1 && !toggledMini) {
                    launcher.toggleMiniPlayer()
                    toggledMini = true
                }
                if (loops >= 2 && !checkedPause) {
                    checkedPause = true
                    launcher.pause()
                    delay(350)
                    assertEquals(AbRepeatStatus.PAUSED, controller.state.value.status)
                    val pausedPos = launcher.captureSnapshot()?.positionMillis ?: 0L
                    delay(400)
                    val pausedPosAfter = launcher.captureSnapshot()?.positionMillis ?: 0L
                    assertTrue(abs(pausedPosAfter - pausedPos) <= 250L, "Pause must not advance playback")
                    launcher.play()
                    delay(150)
                    assertEquals(AbRepeatStatus.ACTIVE, controller.state.value.status)
                }
                delay(80)
            }

            assertTrue(
                controller.state.value.loopCount >= 5,
                "Expected >= 5 A/B loops, got ${controller.state.value.loopCount}, state=${controller.state.value}",
            )

            // Wait briefly for the 5th loop's return-to-A telemetry snapshot to settle
            repeat(30) {
                if (positions.any { it.first == 5 && it.second < 3_000L }) return@repeat
                delay(100)
            }

            // Measure per-loop return-to-A and overshoot past B (1000 ms budget)
            for (loopNum in 0..4) {
                val loopPositions = positions.filter { it.first == loopNum }.map { it.second }
                val maxBeforeReset = loopPositions.maxOrNull() ?: 4_000L
                val overshoot = (maxBeforeReset - 4_000L).coerceAtLeast(0L)
                assertTrue(
                    overshoot <= 1_000L,
                    "Loop $loopNum overshot B=4000ms by ${overshoot}ms (max=$maxBeforeReset)",
                )
            }
            for (loopNum in 1..5) {
                val nextLoopPositions = positions.filter { it.first == loopNum }.map { it.second }
                assertTrue(nextLoopPositions.isNotEmpty(), "Missing telemetry after loop $loopNum")
                val minAfterReset = nextLoopPositions.minOrNull()!!
                assertTrue(
                    abs(minAfterReset - 2_000L) <= 1_000L,
                    "Loop $loopNum returned to ${minAfterReset}ms instead of 2000ms",
                )
            }

            // Explicit stop clears repeat & guarantees no background playback
            unregister()
            launcher.stop()
            delay(250)
            assertEquals(PlaybackSessionState.IDLE, launcher.state)
            assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)
            delay(500)
            assertEquals(PlaybackSessionState.IDLE, launcher.state)

            // Simulated restart with persisted SavedStudyRange must NOT auto-start playback or repeat
            controller.close()
            launcher.close()
            val restartedLauncher = DesktopPlaybackLauncher(store, { PtBrStrings }, scope)
            val restartedController = StudyAbRepeatController(restartedLauncher, scope)
            try {
                delay(200)
                assertEquals(PlaybackSessionState.IDLE, restartedLauncher.state)
                assertEquals(AbRepeatStatus.INACTIVE, restartedController.state.value.status)
            } finally {
                restartedController.close()
                restartedLauncher.close()
            }
        } finally {
            controller.close()
            launcher.close()
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
