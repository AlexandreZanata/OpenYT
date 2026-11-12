package com.openyt.android.player

import com.openyt.core.application.ActiveAbRepeatBounds
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCapabilities
import com.openyt.core.application.PlaybackPositionSnapshot
import com.openyt.core.application.PlaybackSessionListener
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.clampSeekPosition
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe bridge publishing Media3's active session state from [PlaybackService]
 * to [AndroidPlaybackLauncher] so shared core callers can atomically capture timestamps
 * and control seek/play/pause/stop without tearing across item transitions.
 */
internal object AndroidPlaybackSessionBridge {

    internal data class BridgeState(
        val media: PlayableMedia? = null,
        val snapshot: PlaybackPositionSnapshot? = null,
        val state: PlaybackSessionState = PlaybackSessionState.IDLE,
    )

    internal interface ActiveSessionDelegate {
        fun captureBridgeState(): BridgeState
        fun seekTo(positionMillis: Long): Boolean
        fun pause()
        fun play()
        fun stop()
    }

    val defaultCapabilities = PlaybackCapabilities(
        canSeek = true,
        canMiniPlayer = true,
        canPause = true,
        isInternalPlayer = true,
        limitationDescription = null,
    )

    private val generation = AtomicLong(0L)
    @Volatile
    private var delegate: ActiveSessionDelegate? = null
    @Volatile
    private var state = BridgeState()
    @Volatile
    private var activeRepeatBounds: ActiveAbRepeatBounds? = null
    private val listeners = CopyOnWriteArrayList<PlaybackSessionListener>()

    fun nextGeneration(): Long {
        activeRepeatBounds = null
        return generation.incrementAndGet()
    }

    fun currentGeneration(): Long = generation.get()

    fun updateActiveRepeatBounds(bounds: ActiveAbRepeatBounds?) {
        activeRepeatBounds = bounds
    }

    fun hasActiveRepeatBounds(): Boolean = activeRepeatBounds != null

    fun registerListener(listener: PlaybackSessionListener): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    private fun notifyListeners(snapshot: PlaybackPositionSnapshot?) {
        for (listener in listeners) {
            listener.onSessionSnapshot(snapshot)
        }
    }

    fun attachService(sessionDelegate: ActiveSessionDelegate, initialState: BridgeState) {
        delegate = sessionDelegate
        state = initialState
        notifyListeners(initialState.snapshot)
    }

    fun detachService(sessionDelegate: ActiveSessionDelegate) {
        if (delegate === sessionDelegate) {
            delegate = null
            activeRepeatBounds = null
            generation.incrementAndGet()
            state = BridgeState(media = null, snapshot = null, state = PlaybackSessionState.IDLE)
            notifyListeners(null)
        }
    }

    fun publishState(nextState: BridgeState) {
        state = nextState
        notifyListeners(nextState.snapshot)
    }

    fun markPreparing(item: PlayableMedia, requestedPositionMillis: Long?) {
        activeRepeatBounds = null
        val gen = generation.incrementAndGet()
        val pos = requestedPositionMillis?.let { clampSeekPosition(it, 0L) } ?: 0L
        val next = BridgeState(
            media = item,
            snapshot = PlaybackPositionSnapshot(
                entryId = item.entryId,
                mediaPath = item.path,
                title = item.title,
                state = PlaybackSessionState.PREPARING,
                capabilities = defaultCapabilities,
                positionMillis = pos,
                durationMillis = 0L,
                sessionGeneration = gen,
            ),
            state = PlaybackSessionState.PREPARING,
        )
        state = next
        notifyListeners(next.snapshot)
    }

    fun markError() {
        activeRepeatBounds = null
        val gen = generation.incrementAndGet()
        state = BridgeState(
            media = null,
            snapshot = null,
            state = PlaybackSessionState.ERROR,
        )
        notifyListeners(
            PlaybackPositionSnapshot(
                entryId = null,
                mediaPath = "",
                title = "",
                state = PlaybackSessionState.ERROR,
                capabilities = defaultCapabilities,
                positionMillis = 0L,
                durationMillis = 0L,
                sessionGeneration = gen,
            ),
        )
    }

    fun markIdle() {
        activeRepeatBounds = null
        generation.incrementAndGet()
        state = BridgeState(
            media = null,
            snapshot = null,
            state = PlaybackSessionState.IDLE,
        )
        notifyListeners(null)
    }

    fun currentBridgeState(): BridgeState = delegate?.captureBridgeState() ?: state

    fun seekTo(positionMillis: Long): Boolean = delegate?.seekTo(positionMillis) == true

    fun pause() {
        delegate?.pause()
    }

    fun play() {
        delegate?.play()
    }

    fun stop() {
        val active = delegate
        if (active != null) {
            active.stop()
        } else {
            markIdle()
        }
    }
}
