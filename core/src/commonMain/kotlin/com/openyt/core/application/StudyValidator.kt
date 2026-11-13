package com.openyt.core.application

import com.openyt.core.domain.StudyFailure
import com.openyt.core.domain.StudyValidationError

/**
 * Pure Kotlin validators enforcing bounds, non-negative timestamps, known-duration
 * guards, and Unicode code-point character limits for study mode annotations.
 */
object StudyValidator {
    const val MAX_NOTE_BODY_CHARS = 20_000
    const val MAX_BOOKMARK_LABEL_CHARS = 120
    const val MAX_RANGE_LABEL_CHARS = 120
    const val MIN_RANGE_INTERVAL_MILLIS = 1_000L

    /**
     * Counts Unicode code points in [text], treating high+low surrogate pairs
     * (such as supplementary emoji) as a single character.
     */
    fun unicodeCodePointCount(text: String): Int {
        var count = 0
        var index = 0
        val length = text.length
        while (index < length) {
            val ch = text[index]
            index += if (ch.isHighSurrogate() && index + 1 < length && text[index + 1].isLowSurrogate()) {
                2
            } else {
                1
            }
            count += 1
        }
        return count
    }

    fun validateNote(
        entryId: String,
        timestampMillis: Long,
        endMillis: Long?,
        body: String,
        knownDurationMillis: Long? = null,
    ): StudyFailure.Validation? {
        if (entryId.isBlank()) {
            return validationError(StudyValidationError.EMPTY_ID, "Entry ID cannot be blank")
        }
        if (timestampMillis < 0L) {
            return validationError(StudyValidationError.NEGATIVE_TIMESTAMP, "Note timestamp cannot be negative: $timestampMillis")
        }
        if (endMillis != null && endMillis < timestampMillis) {
            return validationError(StudyValidationError.INVALID_TIME_RANGE, "Note end timestamp ($endMillis) cannot precede start ($timestampMillis)")
        }
        if (knownDurationMillis != null && knownDurationMillis >= 0L) {
            val upper = endMillis ?: timestampMillis
            if (timestampMillis > knownDurationMillis || upper > knownDurationMillis) {
                return validationError(
                    StudyValidationError.EXCEEDS_KNOWN_DURATION,
                    "Note timestamp ($upper ms) exceeds known media duration ($knownDurationMillis ms)",
                )
            }
        }
        if (body.isBlank()) {
            return validationError(StudyValidationError.EMPTY_NOTE_BODY, "Note body cannot be blank")
        }
        val chars = unicodeCodePointCount(body)
        if (chars > MAX_NOTE_BODY_CHARS) {
            return validationError(
                StudyValidationError.NOTE_BODY_TOO_LONG,
                "Note body exceeds $MAX_NOTE_BODY_CHARS characters (was $chars)",
            )
        }
        return null
    }

    fun validateBookmark(
        entryId: String,
        timestampMillis: Long,
        label: String,
        knownDurationMillis: Long? = null,
    ): StudyFailure.Validation? {
        if (entryId.isBlank()) {
            return validationError(StudyValidationError.EMPTY_ID, "Entry ID cannot be blank")
        }
        if (timestampMillis < 0L) {
            return validationError(StudyValidationError.NEGATIVE_TIMESTAMP, "Bookmark timestamp cannot be negative: $timestampMillis")
        }
        if (knownDurationMillis != null && knownDurationMillis >= 0L && timestampMillis > knownDurationMillis) {
            return validationError(
                StudyValidationError.EXCEEDS_KNOWN_DURATION,
                "Bookmark timestamp ($timestampMillis ms) exceeds known media duration ($knownDurationMillis ms)",
            )
        }
        val trimmed = label.trim()
        if (trimmed.isEmpty()) {
            return validationError(StudyValidationError.EMPTY_BOOKMARK_LABEL, "Bookmark label cannot be blank")
        }
        val chars = unicodeCodePointCount(trimmed)
        if (chars > MAX_BOOKMARK_LABEL_CHARS) {
            return validationError(
                StudyValidationError.BOOKMARK_LABEL_TOO_LONG,
                "Bookmark label exceeds $MAX_BOOKMARK_LABEL_CHARS characters (was $chars)",
            )
        }
        return null
    }

    fun validateSavedRange(
        entryId: String,
        startMillis: Long,
        endMillis: Long,
        label: String?,
        knownDurationMillis: Long? = null,
    ): StudyFailure.Validation? {
        if (entryId.isBlank()) {
            return validationError(StudyValidationError.EMPTY_ID, "Entry ID cannot be blank")
        }
        if (startMillis < 0L || endMillis < 0L) {
            return validationError(StudyValidationError.NEGATIVE_TIMESTAMP, "Range bounds cannot be negative")
        }
        if (endMillis <= startMillis) {
            return validationError(StudyValidationError.INVALID_TIME_RANGE, "Range end ($endMillis) must exceed start ($startMillis)")
        }
        if (endMillis - startMillis < MIN_RANGE_INTERVAL_MILLIS) {
            return validationError(
                StudyValidationError.RANGE_TOO_SHORT,
                "Saved range interval must be at least $MIN_RANGE_INTERVAL_MILLIS ms (was ${endMillis - startMillis} ms)",
            )
        }
        if (knownDurationMillis != null && knownDurationMillis >= 0L && endMillis > knownDurationMillis) {
            return validationError(
                StudyValidationError.EXCEEDS_KNOWN_DURATION,
                "Range end ($endMillis ms) exceeds known media duration ($knownDurationMillis ms)",
            )
        }
        if (label != null) {
            val trimmed = label.trim()
            if (trimmed.isEmpty()) {
                return validationError(StudyValidationError.BLANK_RANGE_LABEL, "Range label cannot be blank when supplied")
            }
            val chars = unicodeCodePointCount(trimmed)
            if (chars > MAX_RANGE_LABEL_CHARS) {
                return validationError(
                    StudyValidationError.RANGE_LABEL_TOO_LONG,
                    "Saved range label exceeds $MAX_RANGE_LABEL_CHARS characters (was $chars)",
                )
            }
        }
        return null
    }

    private fun validationError(
        error: StudyValidationError,
        message: String,
    ): StudyFailure.Validation = StudyFailure.Validation(error = error, message = message)
}
