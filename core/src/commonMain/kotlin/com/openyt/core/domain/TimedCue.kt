package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class TimedCue(
    val cueId: String,
    val trackId: String = "",
    val entryId: String = "",
    val startMs: Long,
    val endMs: Long,
    val displayText: String,
    val plainText: String,
    val originalIndex: Int = 0,
    val speaker: String? = null,
) {
    init {
        require(startMs >= 0L) { "Cue startMs cannot be negative: $startMs" }
        require(endMs >= startMs) { "Cue endMs ($endMs) cannot precede startMs ($startMs)" }
    }

    val durationMs: Long
        get() = endMs - startMs
}
