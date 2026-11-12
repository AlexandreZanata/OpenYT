package com.openyt.core.application

import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyCaptureFailure
import com.openyt.core.domain.StudyCaptureResult
import com.openyt.core.domain.StudyDiscardPrompt
import com.openyt.core.domain.StudyEditorDraft
import com.openyt.core.domain.StudyEditorState
import com.openyt.core.domain.StudyNavigationDecision
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyPendingActionKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pure Kotlin coordinator managing unsaved note and bookmark editor drafts.
 *
 * Guarantees:
 * - Normal navigation between screens preserves dirty drafts so unsaved text
 *   is retained when returning to an entry.
 * - Closing/canceling/switching away from dirty edits emits an explicit
 *   [StudyNavigationDecision.PromptConfirmDiscard] before discarding anything.
 * - Confirmed cancellations discard only the unsaved buffer without touching
 *   persisted repository records.
 * - Duplicate commit taps are safely suppressed while validation errors retain
 *   the user's unsaved text for correction.
 */
class StudyEditorCoordinator {
    private val _state = MutableStateFlow(StudyEditorState())
    val state: StateFlow<StudyEditorState> = _state.asStateFlow()

    private val saveMutex = Mutex()
    private val savingDraftKeys = mutableSetOf<String>()

    fun openNewNoteDraft(
        entryId: String,
        timestampMillis: Long,
        endMillis: Long? = null,
        capturedGeneration: Long? = null,
    ): StudyNavigationDecision {
        val key = StudyEditorDraft.noteDraftKey(entryId, noteId = null)
        val existing = _state.value.drafts[key]
        if (existing != null) {
            return focusDraftOrPrompt(existing)
        }
        return focusDraftOrPrompt(
            StudyEditorDraft.NoteDraft(
                entryId = entryId,
                noteId = null,
                originalTimestampMillis = timestampMillis,
                originalEndMillis = endMillis,
                capturedGeneration = capturedGeneration,
            ),
        )
    }

    fun openExistingNoteDraft(note: StudyNote): StudyNavigationDecision {
        val key = StudyEditorDraft.noteDraftKey(note.entryId, note.id)
        val existing = _state.value.drafts[key]
        if (existing != null) {
            return focusDraftOrPrompt(existing)
        }
        return focusDraftOrPrompt(
            StudyEditorDraft.NoteDraft(
                entryId = note.entryId,
                noteId = note.id,
                originalTimestampMillis = note.timestampMillis,
                originalEndMillis = note.endMillis,
                originalBody = note.body,
            ),
        )
    }

    fun openNewBookmarkDraft(
        entryId: String,
        timestampMillis: Long,
        capturedGeneration: Long? = null,
    ): StudyNavigationDecision {
        val key = StudyEditorDraft.bookmarkDraftKey(entryId, bookmarkId = null)
        val existing = _state.value.drafts[key]
        if (existing != null) {
            return focusDraftOrPrompt(existing)
        }
        return focusDraftOrPrompt(
            StudyEditorDraft.BookmarkDraft(
                entryId = entryId,
                bookmarkId = null,
                originalTimestampMillis = timestampMillis,
                capturedGeneration = capturedGeneration,
            ),
        )
    }

    fun openExistingBookmarkDraft(bookmark: StudyBookmark): StudyNavigationDecision {
        val key = StudyEditorDraft.bookmarkDraftKey(bookmark.entryId, bookmark.id)
        val existing = _state.value.drafts[key]
        if (existing != null) {
            return focusDraftOrPrompt(existing)
        }
        return focusDraftOrPrompt(
            StudyEditorDraft.BookmarkDraft(
                entryId = bookmark.entryId,
                bookmarkId = bookmark.id,
                originalTimestampMillis = bookmark.timestampMillis,
                originalLabel = bookmark.label,
            ),
        )
    }

    fun updateNoteDraft(
        draftKey: String,
        body: String? = null,
        timestampMillis: Long? = null,
        endMillis: Long? = null,
        clearEndMillis: Boolean = false,
    ) {
        _state.update { current ->
            val existing = current.drafts[draftKey] as? StudyEditorDraft.NoteDraft ?: return@update current
            val updated = existing.copy(
                draftBody = body ?: existing.draftBody,
                draftTimestampMillis = timestampMillis ?: existing.draftTimestampMillis,
                draftEndMillis = when {
                    clearEndMillis -> null
                    endMillis != null -> endMillis
                    else -> existing.draftEndMillis
                },
            )
            current.copy(
                drafts = current.drafts + (draftKey to updated),
                lastError = null,
            )
        }
    }

    fun updateBookmarkDraft(
        draftKey: String,
        label: String? = null,
        timestampMillis: Long? = null,
    ) {
        _state.update { current ->
            val existing = current.drafts[draftKey] as? StudyEditorDraft.BookmarkDraft ?: return@update current
            val updated = existing.copy(
                draftLabel = label ?: existing.draftLabel,
                draftTimestampMillis = timestampMillis ?: existing.draftTimestampMillis,
            )
            current.copy(
                drafts = current.drafts + (draftKey to updated),
                lastError = null,
            )
        }
    }

    /**
     * Normal screen navigation keeps any unsaved edits safely in memory so
     * returning to the study area restores the user's unfinished note/bookmark.
     */
    fun onNormalNavigation(): StudyNavigationDecision {
        _state.update { it.copy(pendingDiscardPrompt = null, focusedDraftKey = null) }
        return StudyNavigationDecision.PreservedInBackground
    }

    fun draftsForEntry(entryId: String): List<StudyEditorDraft> =
        _state.value.drafts.values.filter { it.entryId == entryId }

    fun restoreDraft(draftKey: String): StudyEditorDraft? {
        val found = _state.value.drafts[draftKey] ?: return null
        _state.update { it.copy(focusedDraftKey = draftKey, pendingDiscardPrompt = null) }
        return found
    }

    fun requestCancelDraft(draftKey: String): StudyNavigationDecision {
        val draft = _state.value.drafts[draftKey] ?: return StudyNavigationDecision.Proceeding
        return if (draft.isDirty) {
            val prompt = StudyDiscardPrompt(
                draftKey = draft.key,
                entryId = draft.entryId,
                actionKind = StudyPendingActionKind.CANCEL_EDIT,
            )
            _state.update { it.copy(pendingDiscardPrompt = prompt) }
            StudyNavigationDecision.PromptConfirmDiscard(prompt)
        } else {
            removeDraft(draft.key)
            StudyNavigationDecision.Proceeding
        }
    }

    fun requestCloseFocusedDraft(): StudyNavigationDecision {
        val current = _state.value
        val focused = current.focusedDraft ?: return StudyNavigationDecision.Proceeding
        return if (focused.isDirty) {
            val prompt = StudyDiscardPrompt(
                draftKey = focused.key,
                entryId = focused.entryId,
                actionKind = StudyPendingActionKind.CLOSE_EDITOR,
            )
            _state.update { it.copy(pendingDiscardPrompt = prompt) }
            StudyNavigationDecision.PromptConfirmDiscard(prompt)
        } else {
            removeDraft(focused.key)
            StudyNavigationDecision.Proceeding
        }
    }

    fun keepEditing() {
        _state.update { it.copy(pendingDiscardPrompt = null) }
    }

    fun confirmDiscard() {
        val prompt = _state.value.pendingDiscardPrompt ?: return
        _state.update { current ->
            val remaining = current.drafts - prompt.draftKey
            val nextFocus = when (prompt.actionKind) {
                StudyPendingActionKind.SWITCH_DRAFT -> prompt.targetDraftKey?.takeIf { it in remaining }
                StudyPendingActionKind.CLOSE_EDITOR,
                StudyPendingActionKind.CANCEL_EDIT -> null
            }
            current.copy(
                drafts = remaining,
                focusedDraftKey = nextFocus,
                pendingDiscardPrompt = null,
                lastError = null,
            )
        }
    }

    suspend fun saveNoteDraft(
        draftKey: String,
        service: StudyCaptureAndNavigationService,
        reanchorToCurrentRevision: Boolean = false,
    ): StudyCaptureResult<StudyNote> {
        val draft = _state.value.drafts[draftKey] as? StudyEditorDraft.NoteDraft
            ?: return StudyCaptureResult.Failure(
                StudyCaptureFailure.ServiceFailure(
                    com.openyt.core.domain.StudyFailure.ItemNotFound(draftKey),
                ),
            )
        if (!acquireSaveSlot(draftKey)) {
            return StudyCaptureResult.Failure(StudyCaptureFailure.DuplicateTapIgnored())
        }
        return try {
            val result = service.addOrUpdateNote(
                entryId = draft.entryId,
                timestampMillis = draft.draftTimestampMillis,
                body = draft.draftBody,
                noteId = draft.noteId,
                endMillis = draft.draftEndMillis,
                clearEndMillis = draft.originalEndMillis != null && draft.draftEndMillis == null,
                reanchorToCurrentRevision = reanchorToCurrentRevision,
            )
            when (result) {
                is StudyCaptureResult.Success -> {
                    removeDraft(draftKey)
                    result
                }
                is StudyCaptureResult.Failure -> {
                    _state.update { it.copy(lastError = result.failure) }
                    result
                }
            }
        } finally {
            releaseSaveSlot(draftKey)
        }
    }

    suspend fun saveBookmarkDraft(
        draftKey: String,
        service: StudyCaptureAndNavigationService,
        reanchorToCurrentRevision: Boolean = false,
    ): StudyCaptureResult<StudyBookmark> {
        val draft = _state.value.drafts[draftKey] as? StudyEditorDraft.BookmarkDraft
            ?: return StudyCaptureResult.Failure(
                StudyCaptureFailure.ServiceFailure(
                    com.openyt.core.domain.StudyFailure.ItemNotFound(draftKey),
                ),
            )
        if (!acquireSaveSlot(draftKey)) {
            return StudyCaptureResult.Failure(StudyCaptureFailure.DuplicateTapIgnored())
        }
        return try {
            val result = service.addOrUpdateBookmark(
                entryId = draft.entryId,
                timestampMillis = draft.draftTimestampMillis,
                label = draft.draftLabel,
                bookmarkId = draft.bookmarkId,
                reanchorToCurrentRevision = reanchorToCurrentRevision,
            )
            when (result) {
                is StudyCaptureResult.Success -> {
                    removeDraft(draftKey)
                    result
                }
                is StudyCaptureResult.Failure -> {
                    _state.update { it.copy(lastError = result.failure) }
                    result
                }
            }
        } finally {
            releaseSaveSlot(draftKey)
        }
    }

    private fun focusDraftOrPrompt(targetDraft: StudyEditorDraft): StudyNavigationDecision {
        val current = _state.value
        val active = current.focusedDraft
        if (active != null && active.key != targetDraft.key && active.isDirty) {
            val storedTarget = current.drafts[targetDraft.key] ?: targetDraft
            val prompt = StudyDiscardPrompt(
                draftKey = active.key,
                entryId = active.entryId,
                actionKind = StudyPendingActionKind.SWITCH_DRAFT,
                targetDraftKey = storedTarget.key,
            )
            _state.update {
                it.copy(
                    drafts = it.drafts + (storedTarget.key to storedTarget),
                    pendingDiscardPrompt = prompt,
                    lastError = null,
                )
            }
            return StudyNavigationDecision.PromptConfirmDiscard(prompt)
        }
        _state.update {
            val merged = if (targetDraft.key in it.drafts) {
                it.drafts
            } else {
                it.drafts + (targetDraft.key to targetDraft)
            }
            it.copy(
                drafts = merged,
                focusedDraftKey = targetDraft.key,
                pendingDiscardPrompt = null,
                lastError = null,
            )
        }
        return StudyNavigationDecision.Proceeding
    }

    private fun removeDraft(draftKey: String) {
        _state.update { current ->
            current.copy(
                drafts = current.drafts - draftKey,
                focusedDraftKey = if (current.focusedDraftKey == draftKey) null else current.focusedDraftKey,
                pendingDiscardPrompt = if (current.pendingDiscardPrompt?.draftKey == draftKey) null else current.pendingDiscardPrompt,
                lastError = null,
            )
        }
    }

    private suspend fun acquireSaveSlot(key: String): Boolean = saveMutex.withLock {
        if (key in savingDraftKeys) return@withLock false
        savingDraftKeys += key
        true
    }

    private suspend fun releaseSaveSlot(key: String) {
        saveMutex.withLock {
            savingDraftKeys -= key
        }
    }
}
