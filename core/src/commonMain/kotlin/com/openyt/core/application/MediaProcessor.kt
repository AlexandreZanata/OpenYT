package com.openyt.core.application

import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaSegment

enum class MediaOperation { TRIM, CONVERT_AUDIO }

data class MediaProcessingRequest(
    val sourcePath: String,
    val title: String,
    val kind: MediaKind,
    val operation: MediaOperation,
    val segment: MediaSegment? = null,
    val audioPreset: AudioPreset = AudioPreset.M4A,
    val normalize: Boolean = false,
)

data class ProcessedMedia(val id: String, val title: String, val path: String, val kind: MediaKind, val sizeBytes: Long)

interface MediaProcessor {
    suspend fun process(request: MediaProcessingRequest): EngineResult<ProcessedMedia>
}

fun parseMediaSegment(startSeconds: String, endSeconds: String): MediaSegment? {
    fun millis(text: String): Long? = text.replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE / 1000.0 }
        ?.let { (it * 1000).toLong() }
    val start = millis(startSeconds) ?: return null
    val end = millis(endSeconds) ?: return null
    return if (end > start) MediaSegment(start, end) else null
}
