package com.openyt.desktop.player

import com.openyt.core.application.MemoryPlaybackStore
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCapabilities
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackLauncher
import com.openyt.core.application.PlaybackQueue
import com.openyt.core.application.PlaybackSessionControl
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.PlaybackStore
import com.openyt.core.application.clampSeekPosition
import com.openyt.core.application.miniPlayerSize
import com.openyt.ui.i18n.AppStrings
import com.openyt.ui.i18n.EnStrings
import com.openyt.ui.i18n.FeatureText
import java.awt.BorderLayout
import com.openyt.core.application.MediaActions
import com.openyt.core.domain.ShareableMedia
import com.openyt.desktop.share.DesktopMediaActions
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/** VLC renders media. OpenYT owns the queue, checkpoint, shortcuts, and window. */
class DesktopPlaybackLauncher(
    private val store: PlaybackStore = MemoryPlaybackStore(),
    private val strings: () -> AppStrings = { EnStrings },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val mediaActions: MediaActions = DesktopMediaActions(),
    private val runtimeAvailable: () -> Boolean = DesktopVlcRuntime::discover,
) : PlaybackLauncher, PlaybackSessionControl {
    override val canPlay: Boolean = true
    private var window: JFrame? = null
    private var component: EmbeddedMediaPlayerComponent? = null
    @Volatile private var queue = PlaybackQueue()
    private var seekSlider: JSlider? = null
    private var timeLabel: JLabel? = null
    private var settingSeek = false
    private var lastSavedTime = 0L
    override var loadedMedia: PlayableMedia? = null
        private set
    override var state: PlaybackSessionState = PlaybackSessionState.IDLE
        private set
    private var fullBounds: Rectangle? = null
    private var mini = false
    private var vlcChecked = false
    private var vlcAvailable = false
    private val sessionGeneration = AtomicLong(0L)
    @Volatile private var pendingSeekMillis: Long? = null
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    override val capabilities: PlaybackCapabilities
        get() = if (isVlcAvailable()) {
            PlaybackCapabilities(
                canSeek = true,
                canMiniPlayer = true,
                canPause = true,
                isInternalPlayer = true,
                limitationDescription = null,
            )
        } else {
            PlaybackCapabilities(
                canSeek = false,
                canMiniPlayer = false,
                canPause = false,
                isInternalPlayer = false,
                limitationDescription = "External system player does not support in-app seek or session control",
            )
        }

    override val currentPositionMillis: Long
        get() = component?.mediaPlayer()?.status()?.time()?.coerceAtLeast(0L) ?: 0L

    override val durationMillis: Long
        get() = component?.mediaPlayer()?.status()?.length()?.coerceAtLeast(0L) ?: 0L

    init {
        scope.launch { for (write in writes) runCatching { write() } }
        scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(500)
                SwingUtilities.invokeLater {
                    val item = loadedMedia ?: return@invokeLater
                    val player = component?.mediaPlayer() ?: return@invokeLater
                    val position = PlaybackCheckpoint(
                        player.status().time().coerceAtLeast(0),
                        player.status().length(),
                        item.entryId,
                    )
                    settingSeek = true
                    seekSlider?.value = if (position.durationMillis > 0) {
                        ((position.positionMillis * 1000) / position.durationMillis).toInt().coerceIn(0, 1000)
                    } else 0
                    settingSeek = false
                    fun time(value: Long): String {
                        val seconds = value.coerceAtLeast(0) / 1000
                        return "%d:%02d".format(seconds / 60, seconds % 60)
                    }
                    timeLabel?.text = "${time(position.positionMillis)} / ${time(position.durationMillis)}"
                    if (kotlin.math.abs(position.positionMillis - lastSavedTime) >= 5_000) {
                        lastSavedTime = position.positionMillis
                        writes.trySend { store.savePosition(item.path, position, item.entryId) }
                    }
                }
            }
        }
    }

    fun isVlcAvailable(): Boolean {
        if (!vlcChecked) {
            vlcChecked = true
            vlcAvailable = runCatching { runtimeAvailable() }.getOrDefault(false)
        }
        return vlcAvailable
    }

    override suspend fun resumeLastSession(): Boolean {
        val saved = store.loadQueue() ?: return false
        val items = saved.items.filter { File(it.path).isFile }
        if (items.isEmpty()) return false
        val index = items.indexOfFirst { it.path == saved.current?.path }.coerceAtLeast(0)
        return openAt(items, index, null)
    }

    override fun play(items: List<PlayableMedia>, startIndex: Int) {
        scope.launch { openAt(items, startIndex, null) }
    }

    override fun playAt(items: List<PlayableMedia>, startIndex: Int, startPositionMillis: Long) {
        scope.launch { openAt(items, startIndex, startPositionMillis) }
    }

    override suspend fun openAt(
        items: List<PlayableMedia>,
        startIndex: Int,
        startPositionMillis: Long?,
    ): Boolean {
        val item = items.getOrNull(startIndex) ?: return false
        if (!File(item.path).isFile) {
            state = PlaybackSessionState.ERROR
            loadedMedia = null
            SwingUtilities.invokeLater { showPlaybackError() }
            return false
        }
        if (!isVlcAvailable()) {
            return openExternally(item)
        }

        return runCatching {
            runOnEdt {
                ensureWindow()
                checkpoint()
                loadedMedia = null
                component?.mediaPlayer()?.controls()?.stop()
                queue = PlaybackQueue.of(items, startIndex)
                playCurrent(startPositionMillis)
                window?.isVisible = true
                window?.toFront()
            }
            true
        }.getOrElse {
            openExternally(item)
        }
    }

    override suspend fun seekTo(positionMillis: Long): Boolean {
        if (!capabilities.canSeek) return false
        if (loadedMedia == null && state != PlaybackSessionState.PREPARING) return false
        val player = component?.mediaPlayer() ?: return false
        return runOnEdt {
            val duration = player.status().length()
            val clamped = clampSeekPosition(positionMillis, duration)
            if (state == PlaybackSessionState.PREPARING) {
                pendingSeekMillis = clamped
                true
            } else {
                player.controls().setTime(clamped)
                true
            }
        }
    }

    override suspend fun pause() {
        runOnEdt {
            val player = component?.mediaPlayer() ?: return@runOnEdt
            if (player.status().isPlaying) {
                player.controls().pause()
                state = PlaybackSessionState.PAUSED
            }
        }
    }

    override suspend fun play() {
        runOnEdt {
            if (loadedMedia == null) {
                playCurrent()
                return@runOnEdt
            }
            val player = component?.mediaPlayer() ?: return@runOnEdt
            if (!player.status().isPlaying) {
                player.controls().play()
                state = PlaybackSessionState.PLAYING
            }
        }
    }

    override suspend fun stop() {
        runOnEdt {
            checkpoint()
            loadedMedia = null
            state = PlaybackSessionState.IDLE
            pendingSeekMillis = null
            sessionGeneration.incrementAndGet()
            component?.mediaPlayer()?.controls()?.stop()
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

    override fun close() {
        val finish = {
            checkpoint()
            val frame = window
            val player = component
            component = null
            window = null
            loadedMedia = null
            state = PlaybackSessionState.IDLE
            pendingSeekMillis = null
            sessionGeneration.incrementAndGet()
            mini = false
            fullBounds = null
            seekSlider = null
            timeLabel = null
            // Native teardown must finish before the surface/JVM can disappear.
            // windowClosed is queued after dispose() and can race JVM shutdown.
            try { player?.release() } finally { frame?.dispose() }
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
            override fun playing(mediaPlayer: MediaPlayer) {
                val currentGen = sessionGeneration.get()
                SwingUtilities.invokeLater {
                    if (component !== player || sessionGeneration.get() != currentGen) return@invokeLater
                    state = PlaybackSessionState.PLAYING
                    val pending = pendingSeekMillis
                    if (pending != null) {
                        pendingSeekMillis = null
                        val duration = mediaPlayer.status().length()
                        val clamped = clampSeekPosition(pending, duration)
                        mediaPlayer.controls().setTime(clamped)
                    }
                }
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                val currentGen = sessionGeneration.get()
                SwingUtilities.invokeLater {
                    if (component !== player || sessionGeneration.get() != currentGen) return@invokeLater
                    state = PlaybackSessionState.PAUSED
                }
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                val currentGen = sessionGeneration.get()
                SwingUtilities.invokeLater {
                    if (component !== player || sessionGeneration.get() != currentGen) return@invokeLater
                    state = PlaybackSessionState.COMPLETED
                    if (queue.hasNext) select(queue.next()) else checkpoint()
                }
            }

            override fun error(mediaPlayer: MediaPlayer) {
                val currentGen = sessionGeneration.get()
                SwingUtilities.invokeLater {
                    if (component !== player || sessionGeneration.get() != currentGen) return@invokeLater
                    state = PlaybackSessionState.ERROR
                    loadedMedia = null
                    pendingSeekMillis = null
                    showPlaybackError()
                }
            }
        })
        window = JFrame("OpenYT").apply {
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
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
                override fun windowClosing(event: WindowEvent) { close() }
                override fun windowClosed(event: WindowEvent) {
                    if (window === event.window) close()
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
            loadedMedia = null
            state = PlaybackSessionState.IDLE
            pendingSeekMillis = null
            sessionGeneration.incrementAndGet()
            component?.mediaPlayer()?.controls()?.stop()
        }
        button("[ ]", FeatureText.MINI_PLAYER, ::toggleMiniPlayer)
    }

    private fun select(next: PlaybackQueue) {
        checkpoint()
        loadedMedia = null
        pendingSeekMillis = null
        sessionGeneration.incrementAndGet()
        component?.mediaPlayer()?.controls()?.stop()
        queue = next
        playCurrent()
    }

    private fun playCurrent(explicitPositionMillis: Long? = null) {
        val item = queue.current ?: return
        if (!File(item.path).isFile) {
            state = PlaybackSessionState.ERROR
            loadedMedia = null
            pendingSeekMillis = null
            showPlaybackError()
            return
        }
        state = PlaybackSessionState.PREPARING
        val currentGen = sessionGeneration.incrementAndGet()
        pendingSeekMillis = explicitPositionMillis?.let { clampSeekPosition(it, 0L) }
        val selectedQueue = queue
        writes.trySend { store.saveQueue(selectedQueue) }
        scope.launch {
            val position = explicitPositionMillis ?: store.position(item.path, item.entryId)?.resumeMillis ?: 0L
            val clampedPosition = clampSeekPosition(position, 0L)
            SwingUtilities.invokeLater {
                if (sessionGeneration.get() != currentGen || queue != selectedQueue) return@invokeLater
                val player = component?.mediaPlayer()
                val startTimeArg = if (clampedPosition > 0) ":start-time=${clampedPosition / 1000.0}" else ":start-time=0"
                val started = player?.media()?.play(File(item.path).absolutePath, startTimeArg) == true
                if (!started) {
                    state = PlaybackSessionState.ERROR
                    loadedMedia = null
                    pendingSeekMillis = null
                    showPlaybackError()
                    return@invokeLater
                }
                loadedMedia = item
                if (clampedPosition > 0) {
                    pendingSeekMillis = clampedPosition
                }
                lastSavedTime = clampedPosition
                window?.title = "OpenYT — ${item.title}"
            }
        }
    }

    private fun checkpoint() {
        val item = loadedMedia ?: return
        val player = component?.mediaPlayer() ?: return
        val position = PlaybackCheckpoint(
            player.status().time().coerceAtLeast(0),
            player.status().length(),
            item.entryId,
        )
        // Flush pending checkpoints before the final one so a close cannot reorder them.
        runBlocking {
            val barrier = kotlinx.coroutines.CompletableDeferred<Unit>()
            writes.send { barrier.complete(Unit) }
            kotlinx.coroutines.withTimeoutOrNull(2_000) { barrier.await() }
            store.savePosition(item.path, position, item.entryId)
            store.saveQueue(queue)
        }
    }

    private fun jump(delta: Long) {
        val player = component?.mediaPlayer() ?: return
        val target = (player.status().time() + delta).coerceAtLeast(0)
        player.controls().setTime(target)
    }

    private fun togglePlayback() {
        if (loadedMedia == null) { playCurrent(); return }
        val player = component?.mediaPlayer() ?: return
        if (player.status().isPlaying) {
            player.controls().pause()
            state = PlaybackSessionState.PAUSED
        } else {
            player.controls().play()
            state = PlaybackSessionState.PLAYING
        }
    }

    private fun showPlaybackError() {
        JOptionPane.showMessageDialog(window, strings().features[FeatureText.PLAYBACK_FAILED], "OpenYT", JOptionPane.ERROR_MESSAGE)
    }

    private suspend fun openExternally(item: PlayableMedia): Boolean {
        val opened = withContext(Dispatchers.IO) {
            runCatching { mediaActions.open(ShareableMedia(item.path, item.title, item.kind)) }.getOrDefault(false)
        }
        if (!opened) {
            state = PlaybackSessionState.ERROR
            loadedMedia = null
            SwingUtilities.invokeLater { showPlaybackError() }
            return false
        } else {
            state = PlaybackSessionState.PLAYING
            loadedMedia = item
            return true
        }
    }

    private fun <T> runOnEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            return block()
        }
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait {
            result = runCatching(block)
        }
        return result!!.getOrThrow()
    }
}
