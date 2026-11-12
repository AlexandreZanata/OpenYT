package com.openyt.core.domain

import kotlinx.serialization.Serializable

/**
 * Compares an annotation's anchored [MediaRevision] against the entry's current
 * revision to flag potentially stale timestamps after content modifications while
 * preserving anchors across locator/path moves.
 */
object StudyRevisionComparator {
    fun isAnchorPotentiallyStale(
        anchored: MediaRevision?,
        current: MediaRevision?,
    ): Boolean {
        if (anchored == null || current == null) return false
        return if (anchored.contentSha256 != null && current.contentSha256 != null) {
            anchored.sizeBytes != current.sizeBytes ||
                !anchored.contentSha256.equals(current.contentSha256, ignoreCase = true)
        } else {
            anchored.sizeBytes != current.sizeBytes ||
                anchored.lastModifiedEpochMillis != current.lastModifiedEpochMillis
        }
    }
}

/**
 * Deterministic sort orders shared across memory and SQLDelight persistence.
 */
object StudyComparators {
    val noteOrder: Comparator<StudyNote> = compareBy(
        { it.timestampMillis },
        { it.endMillis ?: -1L },
        { it.createdAtEpochMillis },
        { it.id },
    )

    val bookmarkOrder: Comparator<StudyBookmark> = compareBy(
        { it.timestampMillis },
        { it.createdAtEpochMillis },
        { it.id },
    )

    val savedRangeOrder: Comparator<SavedStudyRange> = compareBy(
        { it.startMillis },
        { it.endMillis },
        { it.createdAtEpochMillis },
        { it.id },
    )
}

/**
 * Plain-text timed study note anchored to a stable library [entryId] and
 * local-media millisecond position (with optional interval [endMillis]).
 */
@Serializable
data class StudyNote(
    val id: String,
    val entryId: String,
    val timestampMillis: Long,
    val endMillis: Long? = null,
    val body: String,
    val mediaRevision: MediaRevision? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun isAnchorStale(currentRevision: MediaRevision?): Boolean =
        StudyRevisionComparator.isAnchorPotentiallyStale(mediaRevision, currentRevision)
}

/**
 * Named timestamp bookmark anchored to a stable library [entryId].
 */
@Serializable
data class StudyBookmark(
    val id: String,
    val entryId: String,
    val timestampMillis: Long,
    val label: String,
    val mediaRevision: MediaRevision? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    fun isAnchorStale(currentRevision: MediaRevision?): Boolean =
        StudyRevisionComparator.isAnchorPotentiallyStale(mediaRevision, currentRevision)
}

/**
 * Saved A/B playback range anchored to a stable library [entryId]. Saved ranges
 * persist across restarts without auto-starting playback or repeat loops.
 */
@Serializable
data class SavedStudyRange(
    val id: String,
    val entryId: String,
    val startMillis: Long,
    val endMillis: Long,
    val label: String? = null,
    val mediaRevision: MediaRevision? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    val durationMillis: Long
        get() = endMillis - startMillis

    fun isAnchorStale(currentRevision: MediaRevision?): Boolean =
        StudyRevisionComparator.isAnchorPotentiallyStale(mediaRevision, currentRevision)
}

/**
 * Explicit manual study completion state for an entry, stored separately from
 * playback resume checkpoints so playback near the end never erases study completion.
 */
@Serializable
data class EntryStudyState(
    val entryId: String,
    val isStudied: Boolean = false,
    val studiedAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long = 0L,
)

/**
 * Aggregate study completion progress for a library collection, computed over
 * current member library entries using shared per-entry completion state.
 */
@Serializable
data class CollectionStudyProgress(
    val collectionId: String,
    val studiedCount: Int,
    val totalMemberCount: Int,
) {
    val fraction: Float
        get() = if (totalMemberCount <= 0) 0f else (studiedCount.toFloat() / totalMemberCount.toFloat()).coerceIn(0f, 1f)
}

/**
 * Snapshot of all persisted study data and anchor-staleness metadata for an entry.
 */
@Serializable
data class EntryStudyBundle(
    val entryId: String,
    val entryExists: Boolean,
    val currentMediaRevision: MediaRevision?,
    val studyState: EntryStudyState,
    val notes: List<StudyNote>,
    val bookmarks: List<StudyBookmark>,
    val savedRanges: List<SavedStudyRange>,
) {
    val staleNoteIds: Set<String>
        get() = notes.filter { it.isAnchorStale(currentMediaRevision) }
            .mapTo(linkedSetOf()) { it.id }

    val staleBookmarkIds: Set<String>
        get() = bookmarks.filter { it.isAnchorStale(currentMediaRevision) }
            .mapTo(linkedSetOf()) { it.id }

    val staleRangeIds: Set<String>
        get() = savedRanges.filter { it.isAnchorStale(currentMediaRevision) }
            .mapTo(linkedSetOf()) { it.id }

    val hasStaleAnchors: Boolean
        get() = staleNoteIds.isNotEmpty() || staleBookmarkIds.isNotEmpty() || staleRangeIds.isNotEmpty()
}

/**
 * Export and confirmation preview produced before removing a library record
 * or explicitly deleting study annotations. Original media is never deleted.
 */
@Serializable
data class StudyRemovalPreview(
    val entryId: String,
    val entryTitle: String? = null,
    val entryExistsInLibrary: Boolean,
    val notes: List<StudyNote>,
    val bookmarks: List<StudyBookmark>,
    val savedRanges: List<SavedStudyRange>,
    val isMarkedStudied: Boolean,
    val originalMediaPreserved: Boolean = true,
) {
    val noteCount: Int get() = notes.size
    val bookmarkCount: Int get() = bookmarks.size
    val savedRangeCount: Int get() = savedRanges.size
    val hasAuthoredNotes: Boolean get() = notes.isNotEmpty()
    val hasAuthoredAnnotations: Boolean
        get() = notes.isNotEmpty() || bookmarks.isNotEmpty() || savedRanges.isNotEmpty()
    val requiresConfirmationPrompt: Boolean
        get() = hasAuthoredAnnotations || isMarkedStudied
}

enum class StudyValidationError {
    EMPTY_NOTE_BODY,
    NOTE_BODY_TOO_LONG,
    EMPTY_BOOKMARK_LABEL,
    BOOKMARK_LABEL_TOO_LONG,
    RANGE_LABEL_TOO_LONG,
    BLANK_RANGE_LABEL,
    NEGATIVE_TIMESTAMP,
    INVALID_TIME_RANGE,
    RANGE_TOO_SHORT,
    EXCEEDS_KNOWN_DURATION,
    EMPTY_ID,
}

sealed interface StudyFailure {
    val message: String

    data class EntryNotFound(
        val entryId: String,
        override val message: String = "Library entry not found: $entryId",
    ) : StudyFailure

    data class ItemNotFound(
        val itemId: String,
        override val message: String = "Study item not found: $itemId",
    ) : StudyFailure

    data class Validation(
        val error: StudyValidationError,
        override val message: String,
    ) : StudyFailure
}

sealed interface StudyResult<out T> {
    data class Success<T>(val value: T) : StudyResult<T>
    data class Failure(val failure: StudyFailure) : StudyResult<Nothing>
}
