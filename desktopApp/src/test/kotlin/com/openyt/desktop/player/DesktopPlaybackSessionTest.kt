package com.openyt.desktop.player

import com.openyt.core.application.MediaActions
import com.openyt.core.application.MemoryPlaybackStore
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ShareableMedia
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
            launcher.openAt(listOf(item), 0)
            assertEquals(item, launcher.loadedMedia)

            launcher.close()
            assertEquals(PlaybackSessionState.IDLE, launcher.state)
            assertNull(launcher.loadedMedia)
            assertEquals(0L, launcher.currentPositionMillis)
        } finally {
            launcher.close()
            scope.cancel()
            file.delete()
        }
    }
}
