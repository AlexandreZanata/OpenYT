package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class ParsedSubtitleTrack(
    val trackId: String,
    val format: SubtitleFormat,
    val cues: List<TimedCue>,
    val warnings: List<String> = emptyList(),
    val timebaseOffsetMs: Long = 0L,
    val entryId: String = "",
    val language: String? = null,
) {
    val totalCues: Int
        get() = cues.size
}
