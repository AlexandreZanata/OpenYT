package com.openyt.desktop.player

import com.openyt.core.application.ActiveAbRepeatBounds
import com.openyt.core.application.MemoryPlaybackStore
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCapabilities
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackLauncher
import com.openyt.core.application.PlaybackPositionSnapshot
import com.openyt.core.application.PlaybackQueue
import com.openyt.core.application.PlaybackSessionControl
import com.openyt.core.application.PlaybackSessionListener
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
import java.util.concurrent.CopyOnWriteArrayList
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
    private val snapshotLock = Any()
    @Volatile private var pendingSeekMillis: Long? = null
    @Volatile private var activeRepeatBounds: ActiveAbRepeatBounds? = null
    private val sessionListeners = CopyOnWriteArrayList<PlaybackSessionListener>()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    override val currentSessionGeneration: Long
        get() = sessionGeneration.get()

    override fun updateActiveRepeatBounds(bounds: ActiveAbRepeatBounds?) {
        activeRepeatBounds = bounds
    }

    override fun registerSessionListener(listener: PlaybackSessionListener): () -> Unit {
        sessionListeners += listener
        return { sessionListeners.remove(listener) }
    }

    private fun notifySessionListeners(snapshot: PlaybackPositionSnapshot?) {
        for (listener in sessionListeners) {
            listener.onSessionSnapshot(snapshot)
        }
    }

    private fun edtSnapshot(overridePositionMillis: Long? = null): PlaybackPositionSnapshot? {
        val media = loadedMedia
            ?: queue.current.takeIf { state == PlaybackSessionState.PREPARING }
            ?: return null
        val player = component?.mediaPlayer()
        val status = player?.status()
        val rawPos = overridePositionMillis
            ?: status?.time()?.takeIf { it >= 0L }
            ?: pendingSeekMillis
            ?: lastSavedTime
        val duration = status?.length()?.coerceAtLeast(0L) ?: 0L
        return PlaybackPositionSnapshot(
            entryId = media.entryId,
            mediaPath = media.path,
            title = media.title,
            state = state,
            capabilities = capabilities,
            positionMillis = rawPos.coerceAtLeast(0L),
            durationMillis = duration,
            sessionGeneration = sessionGeneration.get(),
        )
    }

    private fun errorSnapshot(generation: Long): PlaybackPositionSnapshot =
        PlaybackPositionSnapshot(
            entryId = null,
            mediaPath = "",
            title = "",
            state = PlaybackSessionState.ERROR,
            capabilities = capabilities,
            positionMillis = 0L,
            durationMillis = 0L,
            sessionGeneration = generation,
        )

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
        get() = component?.mediaPlayer()?.status()?.time()?.takeIf { it >= 0 } ?: lastSavedTime

    override val durationMillis: Long
        get() = component?.mediaPlayer()?.status()?.length()?.coerceAtLeast(0L) ?: 0L

    override fun captureSnapshot(): PlaybackPositionSnapshot? {
        val activeComponent = component
        if (activeComponent != null) {
            return runOnEdt { edtSnapshot() }
        }
        return synchronized(snapshotLock) {
            val media = loadedMedia
                ?: queue.current.takeIf { state == PlaybackSessionState.PREPARING }
                ?: return@synchronized null
            PlaybackPositionSnapshot(
                entryId = media.entryId,
                mediaPath = media.path,
                title = media.title,
                state = state,
                capabilities = capabilities,
                positionMillis = (pendingSeekMillis ?: lastSavedTime).coerceAtLeast(0L),
                durationMillis = 0L,
                sessionGeneration = sessionGeneration.get(),
            )
        }
    }

    init {
        scope.launch { for (write in writes) runCatching { write() } }
        scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val fastMonitoring = activeRepeatBounds != null && state == PlaybackSessionState.PLAYING
                kotlinx.coroutines.delay(if (fastMonitoring) 100 else 500)
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
                    notifySessionListeners(edtSnapshot(position.positionMillis))
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
            val gen = synchronized(snapshotLock) {
                state = PlaybackSessionState.ERROR
                loadedMedia = null
                activeRepeatBounds = null
                sessionGeneration.incrementAndGet()
            }
            notifySessionListeners(errorSnapshot(gen))
            SwingUtilities.invokeLater { showPlaybackError() }
            return false
        }
        if (!isVlcAvailable()) {
            val position = startPositionMillis ?: store.position(item.path, item.entryId)?.resumeMillis ?: 0L
            synchronized(snapshotLock) {
                lastSavedTime = clampSeekPosition(position, 0L)
                queue = PlaybackQueue.of(items, startIndex)
                activeRepeatBounds = null
                sessionGeneration.incrementAndGet()
            }
            val checkpoint = PlaybackCheckpoint(
                positionMillis = lastSavedTime,
                durationMillis = 0L,
                entryId = item.entryId,
            )
            writes.trySend {
                store.saveQueue(queue)
                store.savePosition(item.path, checkpoint, item.entryId)
            }
            return openExternally(item)
        }

        return runCatching {
            runOnEdt {
                ensureWindow()
                checkpoint()
                loadedMedia = null
                activeRepeatBounds = null
                component?.mediaPlayer()?.controls()?.stop()
                queue = PlaybackQueue.of(items, startIndex)
                window?.isVisible = true
                window?.toFront()
                playCurrent(startPositionMillis)
            }
            true
        }.getOrElse { error ->
            System.err.println("OpenYT internal playback fallback: ${error::class.simpleName}: ${error.message}")
            openExternally(item)
        }
    }

    override suspend fun seekTo(positionMillis: Long): Boolean {
        if (!capabilities.canSeek) return false
        val player = component?.mediaPlayer() ?: return false
        var snapshotAfterSeek: PlaybackPositionSnapshot? = null
        val ok = runOnEdt {
            val currentItem = loadedMedia
            if (currentItem == null && state != PlaybackSessionState.PREPARING) return@runOnEdt false
            val duration = player.status().length()
            val clamped = clampSeekPosition(positionMillis, duration)
            lastSavedTime = clamped
            if (state == PlaybackSessionState.PREPARING) {
                pendingSeekMillis = clamped
                snapshotAfterSeek = edtSnapshot(clamped)
                true
            } else if (state == PlaybackSessionState.COMPLETED && currentItem != null) {
                pendingSeekMillis = clamped
                state = PlaybackSessionState.PREPARING
                val startTimeArg = if (clamped > 0L) ":start-time=${clamped / 1000.0}" else ":start-time=0"
                val restarted = player.media().play(File(currentItem.path).absolutePath, startTimeArg)
                snapshotAfterSeek = edtSnapshot(clamped)
                restarted
            } else {
                player.controls().setTime(clamped)
                snapshotAfterSeek = edtSnapshot(clamped)
                true
            }
        }
        if (ok) {
            notifySessionListeners(snapshotAfterSeek)
        }
        return ok
    }

    override suspend fun pause() {
        var snap: PlaybackPositionSnapshot? = null
        runOnEdt {
            val player = component?.mediaPlayer() ?: return@runOnEdt
            if (player.status().isPlaying || state == PlaybackSessionState.PLAYING) {
                player.controls().pause()
                state = PlaybackSessionState.PAUSED
                snap = edtSnapshot()
            }
        }
        snap?.let(::notifySessionListeners)
    }

    override suspend fun play() {
        var snap: PlaybackPositionSnapshot? = null
        runOnEdt {
            if (loadedMedia == null) {
                playCurrent()
                return@runOnEdt
            }
            val player = component?.mediaPlayer() ?: return@runOnEdt
            if (state == PlaybackSessionState.COMPLETED) {
                val path = loadedMedia!!.path
                pendingSeekMillis = 0L
                state = PlaybackSessionState.PREPARING
                player.media().play(File(path).absolutePath, ":start-time=0")
                snap = edtSnapshot(0L)
            } else if (!player.status().isPlaying && state != PlaybackSessionState.PREPARING) {
                player.controls().play()
                state = PlaybackSessionState.PLAYING
                snap = edtSnapshot()
            }
        }
        snap?.let(::notifySessionListeners)
    }

    override suspend fun stop() {
        runOnEdt {
            performStopOnEdt()
        }
    }

    private fun performStopOnEdt() {
        checkpoint()
        synchronized(snapshotLock) {
            loadedMedia = null
            state = PlaybackSessionState.IDLE
            pendingSeekMillis = null
            activeRepeatBounds = null
            sessionGeneration.incrementAndGet()
        }
        component?.mediaPlayer()?.controls()?.stop()
        notifySessionListeners(null)
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
            synchronized(snapshotLock) {
                component = null
                window = null
                loadedMedia = null
                state = PlaybackSessionState.IDLE
                pendingSeekMillis = null
                activeRepeatBounds = null
                sessionGeneration.incrementAndGet()
            }
            mini = false
            fullBounds = null
            seekSlider = null
            timeLabel = null
            notifySessionListeners(null)
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
                        notifySessionListeners(edtSnapshot(clamped))
                    } else {
                        notifySessionListeners(edtSnapshot())
                    }
                }
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                val currentGen = sessionGeneration.get()
                SwingUtilities.invokeLater {
                    if (component !== player || sessionGeneration.get() != currentGen) return@invokeLater
                    state = PlaybackSessionState.PAUSED
                    notifySessionListeners(edtSnapshot())
                }
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                val currentGen = sessionGeneration.get()
                SwingUtilities.invokeLater {
                    if (component !== player || sessionGeneration.get() != currentGen) return@invokeLater
                    state = PlaybackSessionState.COMPLETED
                    val bounds = activeRepeatBounds
                    val current = loadedMedia
                    val repeatOwnsEof = bounds != null &&
                        current != null &&
                        bounds.sessionGeneration == currentGen &&
                        bounds.entryId == current.entryId &&
                        bounds.mediaPath == current.path
                    if (!repeatOwnsEof && queue.hasNext) {
                        select(queue.next())
                    } else {
                        checkpoint()
                        val eofDuration = mediaPlayer.status().length().coerceAtLeast(0L)
                        notifySessionListeners(edtSnapshot(overridePositionMillis = eofDuration))
                    }
                }
            }

            override fun error(mediaPlayer: MediaPlayer) {
                val currentGen = sessionGeneration.get()
                SwingUtilities.invokeLater {
                    if (component !== player || sessionGeneration.get() != currentGen) return@invokeLater
                    state = PlaybackSessionState.ERROR
                    loadedMedia = null
                    pendingSeekMillis = null
                    activeRepeatBounds = null
                    notifySessionListeners(errorSnapshot(currentGen))
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
            performStopOnEdt()
        }
        button("[ ]", FeatureText.MINI_PLAYER, ::toggleMiniPlayer)
    }

    private fun select(next: PlaybackQueue) {
        checkpoint()
        loadedMedia = null
        pendingSeekMillis = null
        activeRepeatBounds = null
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
            activeRepeatBounds = null
            notifySessionListeners(errorSnapshot(sessionGeneration.get()))
            showPlaybackError()
            return
        }
        state = PlaybackSessionState.PREPARING
        val currentGen = sessionGeneration.incrementAndGet()
        val initialPosition = explicitPositionMillis?.let { clampSeekPosition(it, 0L) }
        pendingSeekMillis = initialPosition
        lastSavedTime = initialPosition ?: 0L
        val selectedQueue = queue
        notifySessionListeners(
            PlaybackPositionSnapshot(
                entryId = item.entryId,
                mediaPath = item.path,
                title = item.title,
                state = PlaybackSessionState.PREPARING,
                capabilities = capabilities,
                positionMillis = initialPosition ?: 0L,
                durationMillis = 0L,
                sessionGeneration = currentGen,
            ),
        )
        writes.trySend { store.saveQueue(selectedQueue) }
        if (initialPosition != null) {
            startPlaybackOnEdt(item, selectedQueue, currentGen, initialPosition)
            return
        }
        scope.launch {
            val position = store.position(item.path, item.entryId)?.resumeMillis ?: 0L
            val clampedPosition = clampSeekPosition(position, 0L)
            SwingUtilities.invokeLater {
                startPlaybackOnEdt(item, selectedQueue, currentGen, clampedPosition)
            }
        }
    }

    private fun startPlaybackOnEdt(
        item: PlayableMedia,
        selectedQueue: PlaybackQueue,
        currentGen: Long,
        clampedPosition: Long,
    ) {
        if (sessionGeneration.get() != currentGen || queue != selectedQueue) return
        val player = component?.mediaPlayer()
        val startTimeArg = if (clampedPosition > 0) ":start-time=${clampedPosition / 1000.0}" else ":start-time=0"
        val started = player?.media()?.play(File(item.path).absolutePath, startTimeArg) == true
        if (!started) {
            state = PlaybackSessionState.ERROR
            loadedMedia = null
            pendingSeekMillis = null
            notifySessionListeners(errorSnapshot(currentGen))
            showPlaybackError()
            return
        }
        loadedMedia = item
        if (clampedPosition > 0) {
            pendingSeekMillis = clampedPosition
        }
        lastSavedTime = clampedPosition
        window?.title = "OpenYT — ${item.title}"
    }

    private fun checkpoint() {
        val item = loadedMedia ?: return
        val player = component?.mediaPlayer()
        val currentPosition = if (player != null && player.status().length() > 0) {
            player.status().time().coerceAtLeast(0)
        } else {
            lastSavedTime
        }
        val duration = player?.status()?.length() ?: 0L
        val position = PlaybackCheckpoint(
            positionMillis = currentPosition,
            durationMillis = duration,
            entryId = item.entryId,
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
            notifySessionListeners(edtSnapshot())
        } else {
            player.controls().play()
            state = PlaybackSessionState.PLAYING
            notifySessionListeners(edtSnapshot())
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
            val gen = synchronized(snapshotLock) {
                state = PlaybackSessionState.ERROR
                loadedMedia = null
                activeRepeatBounds = null
                sessionGeneration.incrementAndGet()
            }
            notifySessionListeners(errorSnapshot(gen))
            SwingUtilities.invokeLater { showPlaybackError() }
            return false
        } else {
            val gen = synchronized(snapshotLock) {
                state = PlaybackSessionState.PLAYING
                loadedMedia = item
                activeRepeatBounds = null
                sessionGeneration.incrementAndGet()
            }
            notifySessionListeners(
                PlaybackPositionSnapshot(
                    entryId = item.entryId,
                    mediaPath = item.path,
                    title = item.title,
                    state = PlaybackSessionState.PLAYING,
                    capabilities = capabilities,
                    positionMillis = lastSavedTime.coerceAtLeast(0L),
                    durationMillis = 0L,
                    sessionGeneration = gen,
                ),
            )
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
