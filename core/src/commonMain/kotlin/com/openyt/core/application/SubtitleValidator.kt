package com.openyt.core.application

/**
 * Validates and classifies subtitle file contents for SRT and WebVTT formats.
 * Checks character encoding, structure, and presence of timestamp cues while
 * rejecting binary, corrupted, empty, or oversized payloads.
 */
object SubtitleValidator {

    const val DEFAULT_MAX_SUBTITLE_BYTES = 10_485_760L // 10 MiB

    typealias SubtitleFormat = com.openyt.core.domain.SubtitleFormat

    sealed interface SubtitleValidationResult {
        data class Valid(
            val format: SubtitleFormat,
            val cueCount: Int,
            val normalizedText: String,
        ) : SubtitleValidationResult

        data class Invalid(val reason: String) : SubtitleValidationResult
    }

    // Matches standard SRT/VTT timestamp arrows:
    // e.g. "00:01:20,000 --> 00:01:23,500" or "01:20.000 --> 01:23.500"
    private val TIMELINE_ARROW_REGEX = Regex(
        """(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{3}\s*-->\s*(?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{3}"""
    )

    /**
     * Validates [bytes] with an optional [filenameHint].
     */
    fun validate(
        bytes: ByteArray,
        filenameHint: String? = null,
        maxSizeBytes: Long = DEFAULT_MAX_SUBTITLE_BYTES,
    ): SubtitleValidationResult {
        if (bytes.isEmpty()) {
            return SubtitleValidationResult.Invalid("Subtitle file is empty")
        }
        if (bytes.size > maxSizeBytes) {
            return SubtitleValidationResult.Invalid("Subtitle file exceeds maximum size ($maxSizeBytes bytes)")
        }

        // Check for binary corruption / excessive NUL bytes
        if (isBinary(bytes)) {
            return SubtitleValidationResult.Invalid("Invalid subtitle encoding: binary or corrupted data")
        }

        val text = decodeText(bytes)
            ?: return SubtitleValidationResult.Invalid("Invalid subtitle text encoding: could not decode as UTF-8 or UTF-16")

        val normalized = text
            .removePrefix("\uFEFF") // Remove UTF-8 BOM if present
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        if (normalized.isBlank()) {
            return SubtitleValidationResult.Invalid("Subtitle content contains no readable text")
        }

        val isVtt = normalized.startsWith("WEBVTT") ||
            filenameHint?.endsWith(".vtt", ignoreCase = true) == true

        // Count cue timeline arrows
        var cueCount = 0
        normalized.lineSequence().forEach { line ->
            if (TIMELINE_ARROW_REGEX.containsMatchIn(line)) {
                cueCount++
            }
        }

        if (cueCount == 0) {
            return SubtitleValidationResult.Invalid("No valid subtitle timestamp cues found (missing '-->')")
        }

        val format = if (isVtt) SubtitleFormat.VTT else SubtitleFormat.SRT
        return SubtitleValidationResult.Valid(
            format = format,
            cueCount = cueCount,
            normalizedText = normalized,
        )
    }

    private fun isBinary(bytes: ByteArray): Boolean {
        // If it starts with UTF-16 BOM, it may have null bytes every other byte
        if (bytes.size >= 2) {
            if ((bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) ||
                (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            ) {
                return false
            }
        }

        var nullCount = 0
        val sampleSize = minOf(bytes.size, 1024)
        for (i in 0 until sampleSize) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) {
                nullCount++
            }
        }
        return nullCount > 0
    }

    private fun decodeText(bytes: ByteArray): String? {
        // Check for UTF-16 BOM
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return runCatching { bytes.decodeToString(startIndex = 2) }.getOrNull()
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return runCatching { bytes.decodeToString(startIndex = 2) }.getOrNull()
            }
        }

        // Try UTF-8
        return runCatching { bytes.decodeToString() }.getOrNull()
    }
}
