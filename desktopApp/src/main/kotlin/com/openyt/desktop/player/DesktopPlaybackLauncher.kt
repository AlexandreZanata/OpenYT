package com.openyt.desktop.player

import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackLauncher
import com.openyt.core.application.PlaybackQueue
import java.awt.BorderLayout
import java.awt.Desktop
import java.io.File
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JToolBar
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaListPlayerComponent

/**
 * Uses an embedded VLCJ media-list player when libvlc is installed, otherwise
 * opens the file with the operating system's default player. The queue provides
 * next/previous/title state; ADR 0019 documents the dependency.
 */
class DesktopPlaybackLauncher : PlaybackLauncher {
    override val canPlay: Boolean = true

    private var window: JFrame? = null
    private var component: EmbeddedMediaListPlayerComponent? = null
    private var queue = PlaybackQueue()
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
        if (!isVlcAvailable()) {
            openExternally(item)
            return
        }
        SwingUtilities.invokeLater {
            runCatching {
                if (component == null) {
                    val player = EmbeddedMediaListPlayerComponent()
                    component = player
                    window = JFrame("OpenYT").apply {
                        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                        layout = BorderLayout()
                        add(buildToolbar(), BorderLayout.NORTH)
                        add(player, BorderLayout.CENTER)
                        setSize(960, 580)
                        setLocationRelativeTo(null)
                    }
                }
                queue = PlaybackQueue.of(items, startIndex)
                val mediaList = component!!.mediaListPlayer().list().media()
                mediaList.clear()
                items.forEach { media -> mediaList.add(File(media.path).toURI().toString()) }
                component!!.mediaListPlayer().controls().play(queue.currentIndex)
                updateTitle()
            }.onFailure {
                openExternally(item)
            }
        }
    }

    private fun buildToolbar(): JToolBar = JToolBar().apply {
        isFloatable = false
        add(JButton("⏮").apply {
            addActionListener {
                if (queue.hasPrevious) {
                    queue = queue.previous()
                    component?.mediaListPlayer()?.controls()?.play(queue.currentIndex)
                    updateTitle()
                }
            }
        })
        add(JButton("⏯").apply {
            addActionListener {
                val player = component?.mediaListPlayer() ?: return@addActionListener
                if (player.status().isPlaying) player.controls().pause() else player.controls().play()
            }
        })
        add(JButton("⏭").apply {
            addActionListener {
                if (queue.hasNext) {
                    queue = queue.next()
                    component?.mediaListPlayer()?.controls()?.play(queue.currentIndex)
                    updateTitle()
                }
            }
        })
        add(JButton("⏹").apply {
            addActionListener { component?.mediaListPlayer()?.controls()?.stop() }
        })
    }

    private fun updateTitle() {
        window?.title = "OpenYT — ${queue.current?.title.orEmpty()}"
    }

    private fun openExternally(item: PlayableMedia) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(File(item.path))
            }
        }
    }
}
