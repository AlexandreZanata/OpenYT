package com.openyt.desktop.downloader

import com.openyt.core.application.MemoryPlaybackStore
import com.openyt.core.application.PlayableMedia
import com.openyt.core.domain.MediaKind
import com.openyt.desktop.player.DesktopPlaybackLauncher
import com.openyt.ui.i18n.PtBrStrings
import java.awt.Frame
import java.awt.Robot
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
                ExecutableLocator().findFfmpeg()!!,
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
}
