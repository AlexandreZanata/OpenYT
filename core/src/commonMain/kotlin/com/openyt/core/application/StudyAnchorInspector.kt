package com.openyt.core.application

import com.openyt.core.domain.AnnotatedSavedRange
import com.openyt.core.domain.AnnotatedStudyBookmark
import com.openyt.core.domain.AnnotatedStudyNote
import com.openyt.core.domain.EntryStudyBundle
import com.openyt.core.domain.EntryStudyOverview
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyAnchorHealth
import com.openyt.core.domain.StudyAnchorStatus
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyRevisionComparator

/**
 * Pure inspector evaluating study anchor staleness against the entry's current
 * [MediaRevision] and known duration without modifying stored annotations.
 */
object StudyAnchorInspector {

    fun inspectNote(
        note: StudyNote,
        currentRevision: MediaRevision?,
        knownDurationMillis: Long?,
    ): StudyAnchorHealth = inspectBounds(
        timestampMillis = note.timestampMillis,
        endMillis = note.endMillis,
        anchoredRevision = note.mediaRevision,
        currentRevision = currentRevision,
        knownDurationMillis = knownDurationMillis,
    )

    fun inspectBookmark(
        bookmark: StudyBookmark,
        currentRevision: MediaRevision?,
        knownDurationMillis: Long?,
    ): StudyAnchorHealth = inspectBounds(
        timestampMillis = bookmark.timestampMillis,
        endMillis = null,
        anchoredRevision = bookmark.mediaRevision,
        currentRevision = currentRevision,
        knownDurationMillis = knownDurationMillis,
    )

    fun inspectSavedRange(
        range: SavedStudyRange,
        currentRevision: MediaRevision?,
        knownDurationMillis: Long?,
    ): StudyAnchorHealth = inspectBounds(
        timestampMillis = range.startMillis,
        endMillis = range.endMillis,
        anchoredRevision = range.mediaRevision,
        currentRevision = currentRevision,
        knownDurationMillis = knownDurationMillis,
        ensureMinRangeInterval = true,
    )

    fun inspectTimestamp(
        timestampMillis: Long,
        currentRevision: MediaRevision?,
        knownDurationMillis: Long?,
    ): StudyAnchorHealth = inspectBounds(
        timestampMillis = timestampMillis,
        endMillis = null,
        anchoredRevision = currentRevision,
        currentRevision = currentRevision,
        knownDurationMillis = knownDurationMillis,
    )

    fun inspectBundle(
        bundle: EntryStudyBundle,
        knownDurationMillis: Long?,
    ): EntryStudyOverview {
        val positiveDuration = knownDurationMillis?.takeIf { it > 0L }
        return EntryStudyOverview(
            bundle = bundle,
            knownDurationMillis = positiveDuration,
            notes = bundle.notes.map {
                AnnotatedStudyNote(
                    note = it,
                    anchorHealth = inspectNote(it, bundle.currentMediaRevision, positiveDuration),
                )
            },
            bookmarks = bundle.bookmarks.map {
                AnnotatedStudyBookmark(
                    bookmark = it,
                    anchorHealth = inspectBookmark(it, bundle.currentMediaRevision, positiveDuration),
                )
            },
            savedRanges = bundle.savedRanges.map {
                AnnotatedSavedRange(
                    range = it,
                    anchorHealth = inspectSavedRange(it, bundle.currentMediaRevision, positiveDuration),
                )
            },
        )
    }

    private fun inspectBounds(
        timestampMillis: Long,
        endMillis: Long?,
        anchoredRevision: MediaRevision?,
        currentRevision: MediaRevision?,
        knownDurationMillis: Long?,
        ensureMinRangeInterval: Boolean = false,
    ): StudyAnchorHealth {
        val positiveDuration = knownDurationMillis?.takeIf { it > 0L }
        val isRevisionStale = StudyRevisionComparator.isAnchorPotentiallyStale(anchoredRevision, currentRevision)
        val isOutOfRange = positiveDuration != null && (
            timestampMillis < 0L ||
                timestampMillis > positiveDuration ||
                (endMillis != null && (endMillis < timestampMillis || endMillis > positiveDuration))
            )

        val (suggestedStart, suggestedEnd) = suggestClampedBounds(
            startMillis = timestampMillis,
            endMillis = endMillis,
            knownDurationMillis = positiveDuration,
            ensureMinRangeInterval = ensureMinRangeInterval,
        )

        val status = when {
            isRevisionStale && isOutOfRange -> StudyAnchorStatus.STALE_REVISION_AND_OUT_OF_RANGE
            isRevisionStale -> StudyAnchorStatus.STALE_REVISION
            isOutOfRange -> StudyAnchorStatus.OUT_OF_RANGE
            else -> StudyAnchorStatus.VALID
        }

        return StudyAnchorHealth(
            status = status,
            isRevisionStale = isRevisionStale,
            isOutOfRange = isOutOfRange,
            anchoredRevision = anchoredRevision,
            currentRevision = currentRevision,
            savedTimestampMillis = timestampMillis,
            savedEndMillis = endMillis,
            knownDurationMillis = positiveDuration,
            suggestedClampedStartMillis = suggestedStart,
            suggestedClampedEndMillis = suggestedEnd,
        )
    }

    private fun suggestClampedBounds(
        startMillis: Long,
        endMillis: Long?,
        knownDurationMillis: Long?,
        ensureMinRangeInterval: Boolean,
    ): Pair<Long, Long?> {
        if (knownDurationMillis == null) {
            return startMillis.coerceAtLeast(0L) to endMillis?.coerceAtLeast(startMillis.coerceAtLeast(0L))
        }
        if (endMillis == null) {
            return startMillis.coerceIn(0L, knownDurationMillis) to null
        }
        val clampedEnd = endMillis.coerceIn(0L, knownDurationMillis)
        val clampedStart = if (ensureMinRangeInterval) {
            val minGap = StudyValidator.MIN_RANGE_INTERVAL_MILLIS
            if (knownDurationMillis >= minGap) {
                startMillis.coerceIn(0L, (clampedEnd - minGap).coerceAtLeast(0L))
            } else {
                0L
            }
        } else {
            startMillis.coerceIn(0L, clampedEnd)
        }
        return clampedStart to clampedEnd
    }
}
