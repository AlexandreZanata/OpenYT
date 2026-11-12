package com.openyt.android.player

import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PlaybackCapabilities
import com.openyt.core.application.PlaybackPositionSnapshot
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.clampSeekPosition
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

    fun nextGeneration(): Long = generation.incrementAndGet()

    fun currentGeneration(): Long = generation.get()

    fun attachService(sessionDelegate: ActiveSessionDelegate, initialState: BridgeState) {
        delegate = sessionDelegate
        state = initialState
    }

    fun detachService(sessionDelegate: ActiveSessionDelegate) {
        if (delegate === sessionDelegate) {
            delegate = null
            generation.incrementAndGet()
            state = BridgeState(media = null, snapshot = null, state = PlaybackSessionState.IDLE)
        }
    }

    fun publishState(nextState: BridgeState) {
        state = nextState
    }

    fun markPreparing(item: PlayableMedia, requestedPositionMillis: Long?) {
        val gen = generation.incrementAndGet()
        val pos = requestedPositionMillis?.let { clampSeekPosition(it, 0L) } ?: 0L
        state = BridgeState(
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
    }

    fun markError() {
        generation.incrementAndGet()
        state = BridgeState(
            media = null,
            snapshot = null,
            state = PlaybackSessionState.ERROR,
        )
    }

    fun markIdle() {
        generation.incrementAndGet()
        state = BridgeState(
            media = null,
            snapshot = null,
            state = PlaybackSessionState.IDLE,
        )
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
