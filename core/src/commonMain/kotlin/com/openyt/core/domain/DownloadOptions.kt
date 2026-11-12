package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class AudioPreset(val extension: String, val codec: String) {
    M4A("m4a", "aac"),
    MP3("mp3", "libmp3lame"),
    OPUS("opus", "libopus"),
    FLAC("flac", "flac"),
    WAV("wav", "pcm_s16le"),
}

@Serializable
data class MediaSegment(val startMillis: Long, val endMillis: Long) {
    init {
        require(startMillis >= 0 && endMillis > startMillis) { "invalid media interval" }
    }
}

@Serializable
data class SubtitleSelection(
    val language: String,
    val automatic: Boolean = false,
    val embed: Boolean = false,
)

data class SubtitleTrack(val language: String, val name: String? = null, val automatic: Boolean = false)

@Serializable
data class DownloadOptions(
    val originKey: String? = null,
    val audioPreset: AudioPreset = AudioPreset.M4A,
    val normalizeAudio: Boolean = false,
    val subtitle: SubtitleSelection? = null,
    val preserveChapters: Boolean = true,
    val sponsorBlockMarkers: Boolean = false,
    val segment: MediaSegment? = null,
    val liveDurationSeconds: Int? = null,
    val exportTargetUri: String? = null,
    val profileProvenance: ProfileProvenance? = null,
)
