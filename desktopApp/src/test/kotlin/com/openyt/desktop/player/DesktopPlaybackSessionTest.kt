package com.openyt.desktop.player

import com.openyt.core.application.FileAvailability
import com.openyt.core.application.MediaActions
import com.openyt.core.application.MemoryPlaybackStore
import com.openyt.core.application.MemoryStudyRepository
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.StudyAbRepeatController
import com.openyt.core.application.StudyCaptureAndNavigationService
import com.openyt.core.application.StudyService
import com.openyt.core.domain.AbRepeatFailure
import com.openyt.core.domain.AbRepeatResult
import com.openyt.core.domain.AbRepeatStatus
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ShareableMedia
import com.openyt.core.domain.StudyCaptureFailure
import com.openyt.core.domain.StudyCaptureResult
import com.openyt.core.domain.StudyNavigationResult
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyResult
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlaybackSessionTest {

    @Test
    fun externalFallbackReportsLimitationsAndRejectsInAppSeek() = runBlocking {
        val file = Files.createTempFile("openyt-external-", ".mp4").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val openedLatch = CountDownLatch(1)
        var openedMedia: ShareableMedia? = null

        val actions = object : MediaActions {
            override val canOpen = true
            override val canShare = false
            override val canReveal = false
            override fun share(media: List<ShareableMedia>, chooserTitle: String) = Unit
            override fun reveal(media: ShareableMedia) = false
            override fun open(media: ShareableMedia): Boolean {
                openedMedia = media
                openedLatch.countDown()
                return true
            }
        }

        val launcher = DesktopPlaybackLauncher(
            scope = scope,
            mediaActions = actions,
            runtimeAvailable = { false }, // VLC unavailable -> external fallback
        )

        try {
            assertFalse(launcher.isVlcAvailable())
            val caps = launcher.capabilities
            assertFalse(caps.canSeek, "External player must report canSeek = false")
            assertFalse(caps.canMiniPlayer)
            assertFalse(caps.isInternalPlayer)
            assertNotNull(caps.limitationDescription)
            assertTrue(caps.limitationDescription!!.contains("External system player does not support in-app seek"))

            val item = PlayableMedia(file.absolutePath, "External Video", MediaKind.VIDEO, entryId = "entry-ext")
            val opened = launcher.openAt(listOf(item), 0, startPositionMillis = 15_000L)
            assertTrue(opened)
            assertTrue(openedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(file.absolutePath, openedMedia?.path)

            // Seeking with external fallback must return false and not crash
            val seekResult = launcher.seekTo(30_000L)
            assertFalse(seekResult, "seekTo must return false when capabilities.canSeek is false")
        } finally {
            launcher.close()
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun missingMediaReportsErrorStateAndRejectsPlayback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryPlaybackStore()
        val launcher = DesktopPlaybackLauncher(
            store = store,
            scope = scope,
            runtimeAvailable = { false },
        )

        try {
            val missingItem = PlayableMedia("/path/does/not/exist/video.mp4", "Missing", MediaKind.VIDEO, "missing-1")
            val opened = launcher.openAt(listOf(missingItem), 0)
            assertFalse(opened, "openAt must return false for missing file")
            assertEquals(PlaybackSessionState.ERROR, launcher.state)
            assertNull(launcher.loadedMedia)
        } finally {
            launcher.close()
            scope.cancel()
        }
    }

    @Test
    fun closeResetsLauncherToIdleAndCleansUp() = runBlocking {
        val file = Files.createTempFile("openyt-close-", ".mp4").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryPlaybackStore()
        val actions = object : MediaActions {
            override val canOpen = true
            override val canShare = false
            override val canReveal = false
            override fun share(media: List<ShareableMedia>, chooserTitle: String) = Unit
            override fun reveal(media: ShareableMedia) = false
            override fun open(media: ShareableMedia): Boolean = true
        }
        val launcher = DesktopPlaybackLauncher(
            store = store,
            scope = scope,
            mediaActions = actions,
            runtimeAvailable = { false },
        )

        try {
            val item = PlayableMedia(file.absolutePath, "Close Test", MediaKind.VIDEO, "close-1")
            assertNull(launcher.captureSnapshot())
            launcher.openAt(listOf(item), 0)
            assertEquals(item, launcher.loadedMedia)
            val snap = launcher.captureSnapshot()
            assertNotNull(snap)
            assertEquals("close-1", snap.entryId)
            assertTrue(snap.sessionGeneration > 0L)

            launcher.close()
            assertEquals(PlaybackSessionState.IDLE, launcher.state)
            assertNull(launcher.loadedMedia)
            assertNull(launcher.captureSnapshot())
            assertEquals(0L, launcher.currentPositionMillis)
        } finally {
            launcher.close()
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun externalDesktopLauncherRejectsStudyCaptureAndTimestampNavigationCleanly() = runBlocking {
        val file = Files.createTempFile("openyt-study-fallback-", ".mp4").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = object : MediaActions {
            override val canOpen = true
            override val canShare = false
            override val canReveal = false
            override fun share(media: List<ShareableMedia>, chooserTitle: String) = Unit
            override fun reveal(media: ShareableMedia) = false
            override fun open(media: ShareableMedia): Boolean = true
        }
        val launcher = DesktopPlaybackLauncher(
            store = MemoryPlaybackStore(),
            scope = scope,
            mediaActions = actions,
            runtimeAvailable = { false },
        )
        val studyRepo = MemoryStudyRepository()
        val entry = LibraryEntry(
            id = "entry-desk-1",
            title = "Desktop Sample",
            kind = MediaKind.VIDEO,
            filePath = file.absolutePath,
            sizeBytes = file.length(),
        )
        studyRepo.putLibraryEntry(entry)
        val studyService = StudyService(studyRepo, nowEpochMillis = { 10_000L }, nextId = { "n-desk-1" })
        val navService = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = launcher,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry) },
            fileAvailability = FileAvailability { File(it).isFile },
        )

        try {
            // Stopped session refuses capture
            val stoppedCapture = navService.captureAndCreateNote("Stopped test")
            assertTrue(stoppedCapture is StudyCaptureResult.Failure)
            assertTrue(stoppedCapture.failure is StudyCaptureFailure.PlayerStopped)

            // Playing via external fallback still refuses live capture and timestamp navigation
            val item = PlayableMedia(entry.filePath, entry.title, entry.kind, entry.id)
            assertTrue(launcher.openAt(listOf(item), 0, startPositionMillis = 12_000L))
            val fallbackCapture = navService.captureAndCreateNote("Fallback test")
            assertTrue(fallbackCapture is StudyCaptureResult.Failure)
            assertTrue(fallbackCapture.failure is StudyCaptureFailure.CapabilityUnsupported)

            val savedNote = (studyService.createNote(entry.id, 12_000L, "Saved desktop note") as StudyResult.Success<StudyNote>).value
            val navResult = navService.openNoteTimestamp(savedNote.id)
            assertTrue(navResult is StudyNavigationResult.CapabilityUnsupported)
        } finally {
            launcher.close()
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun externalDesktopLauncherDisablesAbRepeatWithExplanationInsteadOfSimulating() = runBlocking<Unit> {
        val file = Files.createTempFile("openyt-ab-ext-", ".mp4").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = object : MediaActions {
            override val canOpen = true
            override val canShare = false
            override val canReveal = false
            override fun share(media: List<ShareableMedia>, chooserTitle: String) = Unit
            override fun reveal(media: ShareableMedia) = false
            override fun open(media: ShareableMedia): Boolean = true
        }
        val launcher = DesktopPlaybackLauncher(
            store = MemoryPlaybackStore(),
            scope = scope,
            mediaActions = actions,
            runtimeAvailable = { false },
        )
        val controller = StudyAbRepeatController(launcher, scope)

        try {
            assertFalse(controller.state.value.isSupported)
            assertEquals(AbRepeatStatus.UNSUPPORTED, controller.state.value.status)
            assertNotNull(controller.state.value.disabledReason)

            val item = PlayableMedia(file.absolutePath, "External Loop Check", MediaKind.VIDEO, "ext-ab-1")
            assertTrue(launcher.openAt(listOf(item), 0, 5_000L))

            val markAttempt = controller.markPointA()
            assertTrue(markAttempt is AbRepeatResult.Failure)
            assertTrue(markAttempt.failure is AbRepeatFailure.CapabilityUnsupported)

            val rangeAttempt = controller.setRange(1_000L, 4_000L)
            assertTrue(rangeAttempt is AbRepeatResult.Failure)
            assertTrue(rangeAttempt.failure is AbRepeatFailure.CapabilityUnsupported)
            assertEquals(AbRepeatStatus.UNSUPPORTED, controller.state.value.status)

            launcher.close()
            assertFalse(controller.state.value.isLooping)
        } finally {
            controller.close()
            launcher.close()
            scope.cancel()
            file.delete()
        }
    }
}
