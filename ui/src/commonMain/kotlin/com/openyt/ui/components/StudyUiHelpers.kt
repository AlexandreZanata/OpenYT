package com.openyt.ui.components

import com.openyt.core.application.StudyMarkdownExporter
import com.openyt.core.domain.StudyCaptureFailure
import com.openyt.core.domain.StudyFailure
import com.openyt.core.domain.StudyNavigationResult
import com.openyt.core.domain.StudyValidationError
import com.openyt.ui.i18n.StudyStrings

internal enum class StudyItemFilter {
    ALL,
    NOTES,
    BOOKMARKS,
    RANGES,
}

internal enum class LibraryStudyFilter {
    ALL,
    STUDIED,
    UNSTUDIED,
}

/**
 * Parses user-typed timestamp input such as `mm:ss`, `h:mm:ss`, `12.5`, or `90` (seconds)
 * into non-negative milliseconds, returning null if malformed.
 */
internal fun parseUserTimestampMillis(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.endsWith("ms", ignoreCase = true)) {
        val ms = trimmed.dropLast(2).trim().toLongOrNull() ?: return null
        return ms.takeIf { it >= 0L }
    }
    if (':' !in trimmed) {
        val clean = trimmed.removeSuffix("s").removeSuffix("S").trim()
        val seconds = clean.toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds < 0.0) return null
        return kotlin.math.round(seconds * 1_000.0).toLong()
    }
    val parts = trimmed.split(':')
    if (parts.size !in 2..3) return null
    val seconds = parts.last().toDoubleOrNull() ?: return null
    if (!seconds.isFinite() || seconds < 0.0 || seconds >= 60.0) return null
    val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
    if (minutes < 0L || (parts.size == 3 && minutes >= 60L)) return null
    val hours = if (parts.size == 3) (parts[0].toLongOrNull() ?: return null) else 0L
    if (hours < 0L) return null
    val wholeMillis = (hours * 3_600L + minutes * 60L) * 1_000L
    return wholeMillis + kotlin.math.round(seconds * 1_000.0).toLong()
}

internal fun formatTimecodeForInput(millis: Long): String = StudyMarkdownExporter.formatTimestamp(millis)

internal fun translateStudyFailure(failure: StudyFailure, strings: StudyStrings): String = when (failure) {
    is StudyFailure.Validation -> when (failure.error) {
        StudyValidationError.EMPTY_NOTE_BODY -> strings.emptyNoteBodyError
        StudyValidationError.NOTE_BODY_TOO_LONG -> strings.noteTooLongError
        StudyValidationError.EMPTY_BOOKMARK_LABEL -> strings.emptyBookmarkLabelError
        StudyValidationError.BOOKMARK_LABEL_TOO_LONG -> strings.bookmarkLabelTooLongError
        StudyValidationError.RANGE_LABEL_TOO_LONG -> strings.rangeLabelTooLongError
        StudyValidationError.INVALID_TIME_RANGE -> strings.invalidTimeRangeError
        StudyValidationError.RANGE_TOO_SHORT -> strings.rangeTooShortError
        StudyValidationError.EXCEEDS_KNOWN_DURATION -> strings.exceedsDurationError
        else -> failure.message
    }
    else -> failure.message
}

internal fun translateCaptureFailure(failure: StudyCaptureFailure, strings: StudyStrings): String = when (failure) {
    is StudyCaptureFailure.PlayerStopped -> strings.playerStoppedMessage
    is StudyCaptureFailure.PlayerNotReady -> strings.playerPreparingMessage
    is StudyCaptureFailure.PlayerError -> strings.playerErrorMessage
    is StudyCaptureFailure.CapabilityUnsupported -> strings.capabilityUnsupportedMessage
    is StudyCaptureFailure.MissingMediaFile -> strings.missingMediaMessage
    is StudyCaptureFailure.EntrySwitchRace -> strings.entrySwitchRaceMessage
    is StudyCaptureFailure.DuplicateTapIgnored -> strings.duplicateTapMessage
    is StudyCaptureFailure.UnlinkedMedia -> strings.abDisabledHint
    is StudyCaptureFailure.ServiceFailure -> translateStudyFailure(failure.failure, strings)
}

internal fun translateNavigationFailure(result: StudyNavigationResult, strings: StudyStrings): String? = when (result) {
    is StudyNavigationResult.Opened -> null
    is StudyNavigationResult.DuplicateTapIgnored -> null
    is StudyNavigationResult.MissingMediaFile -> strings.missingMediaMessage
    is StudyNavigationResult.OutOfRangeAnchor -> strings.exceedsDurationError
    is StudyNavigationResult.CapabilityUnsupported -> strings.capabilityUnsupportedMessage
    is StudyNavigationResult.PlaybackFailed -> strings.playerErrorMessage
    is StudyNavigationResult.EntryNotFound -> strings.deepLinkEntryMissing(result.entryId)
    is StudyNavigationResult.ItemNotFound -> result.itemId
}
