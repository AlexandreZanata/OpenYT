package com.openyt.desktop.player

import com.openyt.core.application.MemoryPlaybackStore
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackLauncher
import com.openyt.core.application.PlaybackQueue
import com.openyt.core.application.PlaybackStore
import com.openyt.core.application.miniPlayerSize
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.FeatureText
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JToolBar
import javax.swing.JSlider
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.Color
import java.awt.Font
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/** VLC renders media. OpenYT owns the queue, checkpoint, shortcuts, and window. */
class DesktopPlaybackLauncher(
    private val store: PlaybackStore = MemoryPlaybackStore(),
    private val strings: () -> AppStrings = { EnStrings },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PlaybackLauncher {
    override val canPlay: Boolean = true
    private var window: JFrame? = null
    private var component: EmbeddedMediaPlayerComponent? = null
    @Volatile private var queue = PlaybackQueue()
    private var seekSlider: JSlider? = null
    private var timeLabel: JLabel? = null
    private var settingSeek = false
    private var lastSavedTime = 0L
    private var loadedMedia: PlayableMedia? = null
    private var fullBounds: Rectangle? = null
    private var mini = false
    private var vlcChecked = false
    private var vlcAvailable = false
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (write in writes) runCatching { write() } }
        scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(500)
                SwingUtilities.invokeLater {
                    val item = loadedMedia ?: return@invokeLater
                    val player = component?.mediaPlayer() ?: return@invokeLater
                    val position = PlaybackCheckpoint(player.status().time().coerceAtLeast(0), player.status().length())
                    settingSeek = true
                    seekSlider?.value = if (position.durationMillis > 0) ((position.positionMillis * 1000) / position.durationMillis).toInt().coerceIn(0, 1000) else 0
                    settingSeek = false
                    fun time(value: Long): String { val seconds = value.coerceAtLeast(0) / 1000; return "%d:%02d".format(seconds / 60, seconds % 60) }
                    timeLabel?.text = "${time(position.positionMillis)} / ${time(position.durationMillis)}"
                    if (kotlin.math.abs(position.positionMillis - lastSavedTime) >= 5_000) {
                        lastSavedTime = position.positionMillis
                        writes.trySend { store.savePosition(item.path, position) }
                    }
                }
            }
        }
    }

    fun isVlcAvailable(): Boolean {
        if (!vlcChecked) {
            vlcChecked = true
            vlcAvailable = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
        }
        return vlcAvailable
    }

    override suspend fun resumeLastSession(): Boolean {
        val saved = store.loadQueue() ?: return false
        val items = saved.items.filter { File(it.path).isFile }
        if (items.isEmpty()) return false
        play(items, items.indexOfFirst { it.path == saved.current?.path }.coerceAtLeast(0))
        return true
    }

    override fun play(items: List<PlayableMedia>, startIndex: Int) {
        val item = items.getOrNull(startIndex) ?: return
        if (!isVlcAvailable()) {
            openExternally(item)
            return
        }
        SwingUtilities.invokeLater {
            runCatching {
                ensureWindow()
                checkpoint()
                loadedMedia = null
                component?.mediaPlayer()?.controls()?.stop()
                queue = PlaybackQueue.of(items, startIndex)
                playCurrent()
                window?.isVisible = true
                window?.toFront()
            }.onFailure { openExternally(item) }
        }
    }

    fun toggleMiniPlayer() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(::toggleMiniPlayer)
            return
        }
        val frame = window ?: return
        mini = !mini
        if (mini) {
            fullBounds = frame.bounds
            val size = miniPlayerSize()
            frame.isAlwaysOnTop = true
            frame.setSize(size.width, size.height)
        } else {
            frame.isAlwaysOnTop = false
            fullBounds?.let { frame.bounds = it }
        }
    }

    fun close() {
        val finish = {
            checkpoint()
            window?.dispose()
            Unit
        }
        if (SwingUtilities.isEventDispatchThread()) finish() else SwingUtilities.invokeAndWait(finish)
    }

    private fun ensureWindow() {
        if (component != null) return
        // The embedded AWT surface uses X11 on Linux. Avoid GL/VA driver crashes
        // by using VLC's XCB renderer there; other hosts retain VLC defaults.
        val player = if (System.getProperty("os.name").contains("Linux", true))
            EmbeddedMediaPlayerComponent("--ignore-config", "--vout=xcb_x11", "--avcodec-hw=none", "--no-video-title-show")
        else EmbeddedMediaPlayerComponent()
        component = player
        player.mediaPlayer().events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater {
                    if (queue.hasNext) select(queue.next()) else checkpoint()
                }
            }
            override fun error(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater { showPlaybackError() }
            }
        })
        window = JFrame("OpenYT").apply {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            layout = BorderLayout()
            add(buildToolbar(), BorderLayout.NORTH)
            add(player, BorderLayout.CENTER)
            add(JPanel(BorderLayout(8, 0)).apply {
                background = Color(0x10, 0x10, 0x10)
                border = javax.swing.BorderFactory.createEmptyBorder(6, 12, 6, 12)
                timeLabel = JLabel("0:00 / 0:00").apply { foreground = Color(0xF7, 0xF7, 0xF5) }
                seekSlider = JSlider(0, 1000, 0).apply {
                    isOpaque = false
                    accessibleContext.accessibleName = strings().features[FeatureText.RESUME_PLAYBACK]
                    addChangeListener {
                        if (!settingSeek && !valueIsAdjusting) component?.mediaPlayer()?.let { media ->
                            val length = media.status().length()
                            if (length > 0) media.controls().setTime(length * value / 1000)
                        }
                    }
                }
                add(seekSlider, BorderLayout.CENTER)
                add(timeLabel, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
            minimumSize = Dimension(320, 220)
            setSize(960, 580)
            setLocationRelativeTo(null)
            addWindowListener(object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) { checkpoint() }
                override fun windowClosed(event: WindowEvent) {
                    player.release()
                    loadedMedia = null
                    component = null
                    window = null
                    mini = false
                }
            })
            bind("SPACE") { togglePlayback() }
            bind("LEFT") { jump(-10_000) }
            bind("RIGHT") { jump(10_000) }
            bind("M") { toggleMiniPlayer() }
            bind("ESCAPE") { if (mini) toggleMiniPlayer() }
        }
    }

    private fun JFrame.bind(key: String, action: () -> Unit) {
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key)
        rootPane.actionMap.put(key, object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent) = action()
        })
    }

    private fun buildToolbar(): JToolBar = JToolBar().apply {
        isFloatable = false
        background = Color(0x10, 0x10, 0x10)
        border = javax.swing.BorderFactory.createEmptyBorder(4, 4, 4, 4)
        fun button(symbol: String, text: FeatureText, action: () -> Unit) {
            add(JButton(symbol).apply {
                background = Color(0x1D, 0x24, 0x23)
                foreground = Color(0x5E, 0xEA, 0xD4)
                font = Font("Dialog", Font.BOLD, 12)
                preferredSize = Dimension(56, 40)
                minimumSize = preferredSize
                maximumSize = preferredSize
                isFocusPainted = true
                border = javax.swing.BorderFactory.createLineBorder(Color(0x0F, 0x76, 0x6E))
                toolTipText = strings().features[text]
                accessibleContext.accessibleName = toolTipText
                addActionListener { action() }
            })
        }
        button("<<", FeatureText.PREVIOUS) { if (queue.hasPrevious) select(queue.previous()) }
        button("-10", FeatureText.BACK_TEN) { jump(-10_000) }
        button("> / ||", FeatureText.PLAY_PAUSE, ::togglePlayback)
        button("+10", FeatureText.FORWARD_TEN) { jump(10_000) }
        button(">>", FeatureText.NEXT) { if (queue.hasNext) select(queue.next()) }
        button("[]", FeatureText.STOP) {
            checkpoint()
            component?.mediaPlayer()?.controls()?.stop()
        }
        button("[ ]", FeatureText.MINI_PLAYER, ::toggleMiniPlayer)
    }

    private fun select(next: PlaybackQueue) {
        checkpoint()
        loadedMedia = null
        component?.mediaPlayer()?.controls()?.stop()
        queue = next
        playCurrent()
    }

    private fun playCurrent() {
        val item = queue.current ?: return
        if (!File(item.path).isFile) {
            showPlaybackError()
            return
        }
        val selected = queue
        writes.trySend { store.saveQueue(selected) }
        scope.launch {
            val position = store.position(item.path)?.resumeMillis ?: 0
            SwingUtilities.invokeLater {
                if (queue != selected) return@invokeLater
                val started = component?.mediaPlayer()?.media()?.play(File(item.path).absolutePath, ":start-time=${position / 1000.0}") == true
                if (!started) { showPlaybackError(); return@invokeLater }
                loadedMedia = item
                lastSavedTime = position
                window?.title = "OpenYT — ${item.title}"
            }
        }
    }

    private fun checkpoint() {
        val item = loadedMedia ?: return
        val player = component?.mediaPlayer() ?: return
        val position = PlaybackCheckpoint(player.status().time().coerceAtLeast(0), player.status().length())
        // Flush pending checkpoints before the final one so a close cannot reorder them.
        runBlocking {
            val barrier = kotlinx.coroutines.CompletableDeferred<Unit>()
            writes.send { barrier.complete(Unit) }
            kotlinx.coroutines.withTimeoutOrNull(2_000) { barrier.await() }
            store.savePosition(item.path, position)
            store.saveQueue(queue)
        }
    }

    private fun jump(delta: Long) {
        val player = component?.mediaPlayer() ?: return
        val target = (player.status().time() + delta).coerceAtLeast(0)
        player.controls().setTime(target)
    }

    private fun togglePlayback() {
        val player = component?.mediaPlayer() ?: return
        if (player.status().isPlaying) player.controls().pause() else player.controls().play()
    }

    private fun showPlaybackError() {
        JOptionPane.showMessageDialog(window, strings().features[FeatureText.PLAYBACK_FAILED], "OpenYT", JOptionPane.ERROR_MESSAGE)
    }

    private fun openExternally(item: PlayableMedia) {
        SwingUtilities.invokeLater {
            val opened = runCatching {
                check(File(item.path).isFile)
                check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                Desktop.getDesktop().open(File(item.path))
            }.isSuccess
            if (!opened) showPlaybackError()
        }
    }
}
