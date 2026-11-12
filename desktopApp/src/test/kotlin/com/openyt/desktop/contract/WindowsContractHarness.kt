package com.openyt.desktop.contract

import com.openyt.core.application.PlayableMedia
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ShareableMedia
import com.openyt.desktop.downloader.ExecutableLocator
import com.openyt.desktop.player.DesktopPlaybackLauncher
import com.openyt.desktop.player.DesktopVlcRuntime
import com.openyt.desktop.share.DesktopMediaActions
import com.sun.jna.NativeLibrary
import java.awt.Frame
import java.awt.Robot
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
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
}
