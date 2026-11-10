package com.openyt.core.application

import com.openyt.core.domain.MediaKind

data class PlayableMedia(
    val path: String,
    val title: String,
    val kind: MediaKind,
)

interface PlaybackLauncher {
    val canPlay: Boolean

    fun play(items: List<PlayableMedia>, startIndex: Int)
}
