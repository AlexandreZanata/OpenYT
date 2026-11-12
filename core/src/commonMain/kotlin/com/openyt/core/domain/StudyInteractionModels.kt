package com.openyt.core.domain

/**
 * Health status of a study anchor relative to the current media revision and
 * known media duration. Stale or out-of-range anchors are surfaced for explicit
 * user correction and are never silently rewritten.
 */
enum class StudyAnchorStatus {
    VALID,
    STALE_REVISION,
    OUT_OF_RANGE,
    STALE_REVISION_AND_OUT_OF_RANGE,
}

/**
 * Diagnostic details describing whether a saved annotation anchor still aligns
 * with the entry's current media revision and known duration.
 */
data class StudyAnchorHealth(
    val status: StudyAnchorStatus,
    val isRevisionStale: Boolean,
    val isOutOfRange: Boolean,
    val anchoredRevision: MediaRevision? = null,
    val currentRevision: MediaRevision? = null,
    val savedTimestampMillis: Long,
    val savedEndMillis: Long? = null,
    val knownDurationMillis: Long? = null,
    val suggestedClampedStartMillis: Long,
    val suggestedClampedEndMillis: Long? = null,
) {
    val requiresUserCorrection: Boolean
        get() = status != StudyAnchorStatus.VALID
}

data class AnnotatedStudyNote(
    val note: StudyNote,
    val anchorHealth: StudyAnchorHealth,
)

data class AnnotatedStudyBookmark(
    val bookmark: StudyBookmark,
    val anchorHealth: StudyAnchorHealth,
)

data class AnnotatedSavedRange(
    val range: SavedStudyRange,
    val anchorHealth: StudyAnchorHealth,
)

/**
 * Entry study bundle enriched with anchor-health diagnostics computed against
 * the entry's current [MediaRevision] and known duration.
 */
data class EntryStudyOverview(
    val bundle: EntryStudyBundle,
    val knownDurationMillis: Long? = null,
    val notes: List<AnnotatedStudyNote>,
    val bookmarks: List<AnnotatedStudyBookmark>,
    val savedRanges: List<AnnotatedSavedRange>,
) {
    val hasAnchorWarnings: Boolean
        get() = notes.any { it.anchorHealth.requiresUserCorrection } ||
            bookmarks.any { it.anchorHealth.requiresUserCorrection } ||
            savedRanges.any { it.anchorHealth.requiresUserCorrection }
}

/**
 * Atomic timestamp capture bound to a library entry identity and playback session
 * generation so concurrent queue switches cannot mis-target another item.
 */
data class CapturedTimestamp(
    val entryId: String,
    val timestampMillis: Long,
    val knownDurationMillis: Long? = null,
    val mediaRevision: MediaRevision? = null,
    val sessionGeneration: Long = 0L,
)

/**
 * Structured error taxonomy for atomic timestamp capture and draft commits.
 */
sealed interface StudyCaptureFailure {
    val message: String

    data class PlayerStopped(
        override val message: String = "Playback session is stopped",
    ) : StudyCaptureFailure

    data class PlayerNotReady(
        override val message: String = "Playback session is still preparing",
    ) : StudyCaptureFailure

    data class PlayerError(
        override val message: String = "Playback session is in an error state",
    ) : StudyCaptureFailure

    data class CapabilityUnsupported(
        override val message: String,
    ) : StudyCaptureFailure

    data class UnlinkedMedia(
        override val message: String = "Loaded media is not linked to a library entry",
    ) : StudyCaptureFailure

    data class MissingMediaFile(
        val entryId: String,
        val filePath: String,
        override val message: String = "Media file is missing for entry $entryId",
    ) : StudyCaptureFailure

    data class EntrySwitchRace(
        val expectedEntryId: String?,
        val actualEntryId: String?,
        override val message: String =
            "Loaded playback item changed during timestamp capture (expected=$expectedEntryId, actual=$actualEntryId)",
    ) : StudyCaptureFailure

    data class DuplicateTapIgnored(
        override val message: String = "Duplicate capture action ignored",
    ) : StudyCaptureFailure

    data class ServiceFailure(
        val failure: StudyFailure,
        override val message: String = failure.message,
    ) : StudyCaptureFailure
}

sealed interface StudyCaptureResult<out T> {
    data class Success<T>(val value: T) : StudyCaptureResult<T>
    data class Failure(val failure: StudyCaptureFailure) : StudyCaptureResult<Nothing>
}

enum class StudyNavigationTargetKind {
    NOTE,
    BOOKMARK,
    SAVED_RANGE,
}

/**
 * Structured outcome of opening a saved study timestamp in the active player.
 */
sealed interface StudyNavigationResult {
    data class Opened(
        val entryId: String,
        val targetKind: StudyNavigationTargetKind,
        val targetId: String,
        val requestedPositionMillis: Long,
        val actualPositionMillis: Long? = null,
        val pendingPrepareSeekMillis: Long? = null,
        val anchorHealth: StudyAnchorHealth,
    ) : StudyNavigationResult

    data class OutOfRangeAnchor(
        val entryId: String,
        val targetKind: StudyNavigationTargetKind,
        val targetId: String,
        val savedTimestampMillis: Long,
        val savedEndMillis: Long? = null,
        val knownDurationMillis: Long,
        val suggestedClampedStartMillis: Long,
        val suggestedClampedEndMillis: Long? = null,
        val anchorHealth: StudyAnchorHealth,
    ) : StudyNavigationResult

    data class MissingMediaFile(
        val entryId: String,
        val filePath: String,
    ) : StudyNavigationResult

    data class EntryNotFound(
        val entryId: String,
    ) : StudyNavigationResult

    data class ItemNotFound(
        val itemId: String,
    ) : StudyNavigationResult

    data class CapabilityUnsupported(
        val reason: String,
    ) : StudyNavigationResult

    data class PlaybackFailed(
        val entryId: String,
        val reason: String,
    ) : StudyNavigationResult

    data object DuplicateTapIgnored : StudyNavigationResult
}

/**
 * Unsaved in-memory editor draft surviving normal app navigation until either
 * committed to [StudyService] or explicitly canceled/discarded by the user.
 */
sealed interface StudyEditorDraft {
    val key: String
    val entryId: String
    val existingItemId: String?
    val isDirty: Boolean

    data class NoteDraft(
        override val entryId: String,
        val noteId: String? = null,
        val originalTimestampMillis: Long,
        val originalEndMillis: Long? = null,
        val originalBody: String = "",
        val draftTimestampMillis: Long = originalTimestampMillis,
        val draftEndMillis: Long? = originalEndMillis,
        val draftBody: String = originalBody,
        val capturedGeneration: Long? = null,
    ) : StudyEditorDraft {
        override val key: String
            get() = noteDraftKey(entryId, noteId)

        override val existingItemId: String?
            get() = noteId

        override val isDirty: Boolean
            get() = if (noteId == null) {
                draftBody.isNotBlank() ||
                    draftTimestampMillis != originalTimestampMillis ||
                    draftEndMillis != originalEndMillis
            } else {
                draftBody != originalBody ||
                    draftTimestampMillis != originalTimestampMillis ||
                    draftEndMillis != originalEndMillis
            }
    }

    data class BookmarkDraft(
        override val entryId: String,
        val bookmarkId: String? = null,
        val originalTimestampMillis: Long,
        val originalLabel: String = "",
        val draftTimestampMillis: Long = originalTimestampMillis,
        val draftLabel: String = originalLabel,
        val capturedGeneration: Long? = null,
    ) : StudyEditorDraft {
        override val key: String
            get() = bookmarkDraftKey(entryId, bookmarkId)

        override val existingItemId: String?
            get() = bookmarkId

        override val isDirty: Boolean
            get() = if (bookmarkId == null) {
                draftLabel.isNotBlank() || draftTimestampMillis != originalTimestampMillis
            } else {
                draftLabel != originalLabel || draftTimestampMillis != originalTimestampMillis
            }
    }

    companion object {
        fun noteDraftKey(entryId: String, noteId: String?): String =
            if (noteId == null) "note:new:$entryId" else "note:edit:$noteId"

        fun bookmarkDraftKey(entryId: String, bookmarkId: String?): String =
            if (bookmarkId == null) "bookmark:new:$entryId" else "bookmark:edit:$bookmarkId"
    }
}

enum class StudyPendingActionKind {
    CLOSE_EDITOR,
    SWITCH_DRAFT,
    CANCEL_EDIT,
}

data class StudyDiscardPrompt(
    val draftKey: String,
    val entryId: String,
    val actionKind: StudyPendingActionKind,
    val targetDraftKey: String? = null,
)

data class StudyEditorState(
    val drafts: Map<String, StudyEditorDraft> = emptyMap(),
    val focusedDraftKey: String? = null,
    val pendingDiscardPrompt: StudyDiscardPrompt? = null,
    val lastError: StudyCaptureFailure? = null,
) {
    val focusedDraft: StudyEditorDraft?
        get() = focusedDraftKey?.let { drafts[it] }

    val hasUnsavedChanges: Boolean
        get() = drafts.values.any { it.isDirty }
}

sealed interface StudyNavigationDecision {
    data object Proceeding : StudyNavigationDecision
    data object PreservedInBackground : StudyNavigationDecision
    data class PromptConfirmDiscard(val prompt: StudyDiscardPrompt) : StudyNavigationDecision
}
