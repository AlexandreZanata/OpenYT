package com.openyt.desktop.share

import com.openyt.core.application.MediaActions
import com.openyt.core.application.PlayableMedia
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ShareableMedia
import com.openyt.desktop.player.DesktopPlaybackLauncher
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalPlaybackTest {
    @Test fun unavailableEmbeddedRuntimeUsesTheSharedFileActionAdapter() {
        val file = Files.createTempFile("Vídeo & ação ", ".mp4").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val invoked = CountDownLatch(1)
        var opened: ShareableMedia? = null
        val actions = object : MediaActions {
            override val canOpen = true
            override val canShare = false
            override val canReveal = false
            override fun share(media: List<ShareableMedia>, chooserTitle: String) = Unit
            override fun reveal(media: ShareableMedia) = false
            override fun open(media: ShareableMedia): Boolean { opened = media; invoked.countDown(); return true }
        }
        val launcher = DesktopPlaybackLauncher(scope = scope, mediaActions = actions, runtimeAvailable = { false })
        try {
            launcher.play(listOf(PlayableMedia(file.path, "Video", MediaKind.VIDEO)), 0)
            assertTrue(invoked.await(5, TimeUnit.SECONDS))
            assertEquals(ShareableMedia(file.path, "Video", MediaKind.VIDEO), opened)
        } finally { scope.cancel(); file.delete() }
    }
}
