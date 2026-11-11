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
            check(launcher.isVlcAvailable()) { "Bundled VLC did not load" }
            check(System.getenv("VLC_PLUGIN_PATH")?.contains("incompatible-vlc-plugins") == true) { "Conflicting inherited VLC environment was not tested" }
            val pluginPath = NativeLibrary.getInstance("msvcrt").getFunction("getenv").invokePointer(arrayOf("VLC_PLUGIN_PATH"))
            check(pluginPath == null || pluginPath.getString(0).isEmpty()) { "External plugins remain in VLC's native environment" }
            check(NativeLibrary.getInstance("libvlc").file.canonicalFile.parentFile == directory.canonicalFile) { "Loaded a machine VLC instead of the package" }
            check(LibVlc.libvlc_get_version().startsWith("3.0.24")) { "Unexpected native VLC version" }
            println("Loaded bundled VLC ${LibVlc.libvlc_get_version()}; inherited external plugins excluded")
            val process = ProcessBuilder(checkNotNull(ExecutableLocator().findFfmpeg()),
                "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=20:size=320x180:rate=10",
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
            Thread.sleep(500)
            val image = Robot().createScreenCapture(window.bounds)
            val colors = mutableSetOf<Int>()
            for (y in 55 until image.height - 35 step 3) for (x in 5 until image.width - 5 step 3) {
                val rgb = image.getRGB(x, y)
                val red = rgb shr 16 and 255; val green = rgb shr 8 and 255; val blue = rgb and 255
                if (maxOf(red, green, blue) - minOf(red, green, blue) > 80) colors += rgb
            }
            check(colors.size >= 12) { "Playback surface did not display decoded test-video colors" }
            ImageIO.write(image, "png", File(file.parentFile, "windows-playback.png"))
            check(file.isFile && file.length() > 0)
        } finally { launcher.close() }
    }
}
