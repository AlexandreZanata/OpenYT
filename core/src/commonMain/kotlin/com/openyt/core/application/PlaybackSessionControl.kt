package com.openyt.core.application

import kotlinx.serialization.Serializable

enum class PlaybackSessionState {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERROR,
}

@Serializable
data class PlaybackCapabilities(
    val canSeek: Boolean = true,
    val canMiniPlayer: Boolean = true,
    val canPause: Boolean = true,
    val isInternalPlayer: Boolean = true,
    val limitationDescription: String? = null,
)

/**
 * Immutable atomic snapshot of the currently loaded playback item and its
 * timing/capabilities, captured together to prevent torn reads across queue switches.
 */
@Serializable
data class PlaybackPositionSnapshot(
    val entryId: String?,
    val mediaPath: String,
    val title: String,
    val state: PlaybackSessionState,
    val capabilities: PlaybackCapabilities,
    val positionMillis: Long,
    val durationMillis: Long,
    val sessionGeneration: Long = 0L,
) {
    val knownDurationMillis: Long?
        get() = durationMillis.takeIf { it > 0L }
}

/**
 * Clamps seek positions to valid ranges: non-negative and bounded by duration if positive.
 */
fun clampSeekPosition(requestedMillis: Long, durationMillis: Long = 0L): Long {
    val nonNegative = requestedMillis.coerceAtLeast(0L)
    return if (durationMillis > 0L) nonNegative.coerceAtMost(durationMillis) else nonNegative
}

/**
 * Active A/B repeat loop bounds published by [StudyAbRepeatController] to platform
 * playback adapters so host loops can maintain timely position monitoring and
 * suppress unintended queue advancement at end-of-media while repeat is active.
 */
data class ActiveAbRepeatBounds(
    val entryId: String?,
    val mediaPath: String,
    val sessionGeneration: Long,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * Observer notified by platform [PlaybackSessionControl] implementations when playback
 * state, loaded media identity, generation, or position changes.
 */
fun interface PlaybackSessionListener {
    fun onSessionSnapshot(snapshot: PlaybackPositionSnapshot?)
}

/**
 * Core port controlling active playback sessions, entry identity, seeking, and lifecycle.
 */
interface PlaybackSessionControl {
    val loadedMedia: PlayableMedia?
    val state: PlaybackSessionState
    val capabilities: PlaybackCapabilities
    val currentPositionMillis: Long
    val durationMillis: Long
    val currentSessionGeneration: Long
        get() = 0L

    /**
     * Atomically captures the loaded media identity, state, capabilities, position,
     * duration, and session generation, or returns null when nothing is loaded.
     */
    fun captureSnapshot(): PlaybackPositionSnapshot? {
        val media = loadedMedia ?: return null
        return PlaybackPositionSnapshot(
            entryId = media.entryId,
            mediaPath = media.path,
            title = media.title,
            state = state,
            capabilities = capabilities,
            positionMillis = currentPositionMillis.coerceAtLeast(0L),
            durationMillis = durationMillis.coerceAtLeast(0L),
            sessionGeneration = currentSessionGeneration,
        )
    }

    suspend fun openAt(
        items: List<PlayableMedia>,
        startIndex: Int,
        startPositionMillis: Long? = null,
    ): Boolean

    suspend fun seekTo(positionMillis: Long): Boolean
    suspend fun pause()
    suspend fun play()
    suspend fun stop()
    fun close()

    /**
     * Publishes active A/B loop bounds to the host session so platform adapters can
     * keep position telemetry responsive and prevent EOF auto-advance during repeats.
     */
    fun updateActiveRepeatBounds(bounds: ActiveAbRepeatBounds?) {}

    /**
     * Registers a listener notified on session state, identity, generation, and
     * position updates. Returns an idempotent unregister callback.
     */
    fun registerSessionListener(listener: PlaybackSessionListener): () -> Unit = {}
}
