package com.openyt.desktop.player

import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackLauncher
import java.awt.Desktop
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/**
 * Uses an embedded VLCJ window when libvlc is installed, otherwise opens the
 * file with the operating system's default player. The app remains usable when
 * VLC is absent; the dependency is documented in ADR 0019.
 */
class DesktopPlaybackLauncher : PlaybackLauncher {
    override val canPlay: Boolean = true

    private var window: JFrame? = null
    private var component: EmbeddedMediaPlayerComponent? = null
    private var vlcChecked = false
    private var vlcAvailable = false

    fun isVlcAvailable(): Boolean {
        if (!vlcChecked) {
            vlcChecked = true
            vlcAvailable = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
        }
        return vlcAvailable
    }

    override fun play(items: List<PlayableMedia>, startIndex: Int) {
        val item = items.getOrNull(startIndex) ?: return
        if (isVlcAvailable()) {
            SwingUtilities.invokeLater {
                runCatching {
                    if (component == null) {
                        val player = EmbeddedMediaPlayerComponent()
                        component = player
                        window = JFrame("OpenYT").apply {
                            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                            add(player)
                            setSize(960, 540)
                            setLocationRelativeTo(null)
                        }
                    }
                    window?.title = "OpenYT — ${item.title}"
                    window?.isVisible = true
                    component?.mediaPlayer()?.media()?.play(File(item.path).toURI().toString())
                }.onFailure {
                    openExternally(item)
                }
            }
        } else {
            openExternally(item)
        }
    }

    private fun openExternally(item: PlayableMedia) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(File(item.path))
            }
        }
    }
}
