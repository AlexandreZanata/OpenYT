package com.openyt.core.domain

sealed interface Inspection {
    data class Single(val media: MediaInfo) : Inspection
    data class Playlist(val playlist: PlaylistInfo) : Inspection
}

data class PlaylistInfo(
    val sourceId: String,
    val source: String,
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val webpageUrl: String,
    val entries: List<PlaylistEntry>,
)

data class PlaylistEntry(
    val index: Int,
    val title: String,
    val url: String,
    val author: String? = null,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
)
