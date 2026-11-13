package com.openyt.core.application

import kotlinx.serialization.Serializable

import com.openyt.core.domain.MediaKind

@Serializable
data class PlayableMedia(
    val path: String,
    val title: String,
    val kind: MediaKind,
    val entryId: String? = null,
)

interface PlaybackLauncher {
    val canPlay: Boolean

    val sessionControl: PlaybackSessionControl?
        get() = this as? PlaybackSessionControl

    suspend fun resumeLastSession(): Boolean = false

    fun play(items: List<PlayableMedia>, startIndex: Int)

    fun playAt(items: List<PlayableMedia>, startIndex: Int, startPositionMillis: Long = 0L) {
        play(items, startIndex)
    }
}
