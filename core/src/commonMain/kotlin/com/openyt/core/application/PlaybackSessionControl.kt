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
 * Clamps seek positions to valid ranges: non-negative and bounded by duration if positive.
 */
fun clampSeekPosition(requestedMillis: Long, durationMillis: Long = 0L): Long {
    val nonNegative = requestedMillis.coerceAtLeast(0L)
    return if (durationMillis > 0L) nonNegative.coerceAtMost(durationMillis) else nonNegative
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
}
