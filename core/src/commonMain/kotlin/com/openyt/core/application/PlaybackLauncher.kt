package com.openyt.core.application

import kotlinx.serialization.Serializable

import com.openyt.core.domain.MediaKind

@Serializable
data class PlayableMedia(
    val path: String,
    val title: String,
    val kind: MediaKind,
)

interface PlaybackLauncher {
    val canPlay: Boolean

    suspend fun resumeLastSession(): Boolean = false

    fun play(items: List<PlayableMedia>, startIndex: Int)
}
