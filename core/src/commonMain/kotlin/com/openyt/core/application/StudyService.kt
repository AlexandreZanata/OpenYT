package com.openyt.core.application

import com.openyt.core.domain.CollectionStudyProgress
import com.openyt.core.domain.EntryStudyBundle
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyFailure
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyRemovalPreview
import com.openyt.core.domain.StudyResult
import kotlin.time.TimeSource

/**
 * Application service coordinating validated CRUD operations for study notes,
 * bookmarks, saved A/B ranges, manual study completion state, collection
 * progress, and safe record-removal previews.
 */
class StudyService(
    private val repository: StudyRepository,
    private val nowEpochMillis: () -> Long = defaultMonotonicClock(),
    private val nextId: () -> String = defaultSequentialIdGenerator(),
) {
    suspend fun createNote(
        entryId: String,
        timestampMillis: Long,
        body: String,
        endMillis: Long? = null,
        knownDurationMillis: Long? = null,
    ): StudyResult<StudyNote> {
        StudyValidator.validateNote(
            entryId = entryId,
            timestampMillis = timestampMillis,
            endMillis = endMillis,
            body = body,
            knownDurationMillis = knownDurationMillis,
        )?.let { return StudyResult.Failure(it) }

        if (!repository.entryExists(entryId)) {
            return StudyResult.Failure(StudyFailure.EntryNotFound(entryId))
        }

        val now = nowEpochMillis()
        val revision = repository.currentEntryRevision(entryId)
        val note = StudyNote(
            id = nextId(),
            entryId = entryId,
            timestampMillis = timestampMillis,
            endMillis = endMillis,
            body = body,
            mediaRevision = revision,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        repository.upsertNote(note)
        return StudyResult.Success(note)
    }

    suspend fun updateNote(
        noteId: String,
        body: String,
        timestampMillis: Long? = null,
        endMillis: Long? = null,
        clearEndMillis: Boolean = false,
        knownDurationMillis: Long? = null,
        reanchorToCurrentRevision: Boolean = false,
    ): StudyResult<StudyNote> {
        val existing = repository.getNoteById(noteId)
            ?: return StudyResult.Failure(StudyFailure.ItemNotFound(noteId))
        if (!repository.entryExists(existing.entryId)) {
            return StudyResult.Failure(StudyFailure.EntryNotFound(existing.entryId))
        }

        val targetStart = timestampMillis ?: existing.timestampMillis
        val targetEnd = when {
            clearEndMillis -> null
            endMillis != null -> endMillis
            else -> existing.endMillis
        }

        StudyValidator.validateNote(
            entryId = existing.entryId,
            timestampMillis = targetStart,
            endMillis = targetEnd,
            body = body,
            knownDurationMillis = knownDurationMillis,
        )?.let { return StudyResult.Failure(it) }

        val anchorChanged = timestampMillis != null || endMillis != null || clearEndMillis || reanchorToCurrentRevision
        val targetRevision = if (anchorChanged) {
            repository.currentEntryRevision(existing.entryId)
        } else {
            existing.mediaRevision
        }

        val updated = existing.copy(
            timestampMillis = targetStart,
            endMillis = targetEnd,
            body = body,
            mediaRevision = targetRevision,
            updatedAtEpochMillis = nowEpochMillis(),
        )
        repository.upsertNote(updated)
        return StudyResult.Success(updated)
    }

    suspend fun deleteNote(noteId: String): StudyResult<Unit> =
        if (repository.deleteNote(noteId)) {
            StudyResult.Success(Unit)
        } else {
            StudyResult.Failure(StudyFailure.ItemNotFound(noteId))
        }

    suspend fun createBookmark(
        entryId: String,
        timestampMillis: Long,
        label: String,
        knownDurationMillis: Long? = null,
    ): StudyResult<StudyBookmark> {
        StudyValidator.validateBookmark(
            entryId = entryId,
            timestampMillis = timestampMillis,
            label = label,
            knownDurationMillis = knownDurationMillis,
        )?.let { return StudyResult.Failure(it) }

        if (!repository.entryExists(entryId)) {
            return StudyResult.Failure(StudyFailure.EntryNotFound(entryId))
        }

        val now = nowEpochMillis()
        val revision = repository.currentEntryRevision(entryId)
        val bookmark = StudyBookmark(
            id = nextId(),
            entryId = entryId,
            timestampMillis = timestampMillis,
            label = label.trim(),
            mediaRevision = revision,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        repository.upsertBookmark(bookmark)
        return StudyResult.Success(bookmark)
    }

    suspend fun updateBookmark(
        bookmarkId: String,
        label: String,
        timestampMillis: Long? = null,
        knownDurationMillis: Long? = null,
        reanchorToCurrentRevision: Boolean = false,
    ): StudyResult<StudyBookmark> {
        val existing = repository.getBookmarkById(bookmarkId)
            ?: return StudyResult.Failure(StudyFailure.ItemNotFound(bookmarkId))
        if (!repository.entryExists(existing.entryId)) {
            return StudyResult.Failure(StudyFailure.EntryNotFound(existing.entryId))
        }

        val targetTime = timestampMillis ?: existing.timestampMillis
        StudyValidator.validateBookmark(
            entryId = existing.entryId,
            timestampMillis = targetTime,
            label = label,
            knownDurationMillis = knownDurationMillis,
        )?.let { return StudyResult.Failure(it) }

        val anchorChanged = timestampMillis != null || reanchorToCurrentRevision
        val targetRevision = if (anchorChanged) {
            repository.currentEntryRevision(existing.entryId)
        } else {
            existing.mediaRevision
        }

        val updated = existing.copy(
            timestampMillis = targetTime,
            label = label.trim(),
            mediaRevision = targetRevision,
            updatedAtEpochMillis = nowEpochMillis(),
        )
        repository.upsertBookmark(updated)
        return StudyResult.Success(updated)
    }

    suspend fun deleteBookmark(bookmarkId: String): StudyResult<Unit> =
        if (repository.deleteBookmark(bookmarkId)) {
            StudyResult.Success(Unit)
        } else {
            StudyResult.Failure(StudyFailure.ItemNotFound(bookmarkId))
        }

    suspend fun saveRange(
        entryId: String,
        startMillis: Long,
        endMillis: Long,
        label: String? = null,
        knownDurationMillis: Long? = null,
    ): StudyResult<SavedStudyRange> {
        StudyValidator.validateSavedRange(
            entryId = entryId,
            startMillis = startMillis,
            endMillis = endMillis,
            label = label,
            knownDurationMillis = knownDurationMillis,
        )?.let { return StudyResult.Failure(it) }

        if (!repository.entryExists(entryId)) {
            return StudyResult.Failure(StudyFailure.EntryNotFound(entryId))
        }

        val now = nowEpochMillis()
        val revision = repository.currentEntryRevision(entryId)
        val range = SavedStudyRange(
            id = nextId(),
            entryId = entryId,
            startMillis = startMillis,
            endMillis = endMillis,
            label = label?.trim(),
            mediaRevision = revision,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        repository.upsertSavedRange(range)
        return StudyResult.Success(range)
    }

    suspend fun updateSavedRange(
        rangeId: String,
        startMillis: Long? = null,
        endMillis: Long? = null,
        label: String? = null,
        clearLabel: Boolean = false,
        knownDurationMillis: Long? = null,
        reanchorToCurrentRevision: Boolean = false,
    ): StudyResult<SavedStudyRange> {
        val existing = repository.getSavedRangeById(rangeId)
            ?: return StudyResult.Failure(StudyFailure.ItemNotFound(rangeId))
        if (!repository.entryExists(existing.entryId)) {
            return StudyResult.Failure(StudyFailure.EntryNotFound(existing.entryId))
        }

        val targetStart = startMillis ?: existing.startMillis
        val targetEnd = endMillis ?: existing.endMillis
        val targetLabel = if (clearLabel) null else (label ?: existing.label)

        StudyValidator.validateSavedRange(
            entryId = existing.entryId,
            startMillis = targetStart,
            endMillis = targetEnd,
            label = targetLabel,
            knownDurationMillis = knownDurationMillis,
        )?.let { return StudyResult.Failure(it) }

        val anchorChanged = startMillis != null || endMillis != null || reanchorToCurrentRevision
        val targetRevision = if (anchorChanged) {
            repository.currentEntryRevision(existing.entryId)
        } else {
            existing.mediaRevision
        }

        val updated = existing.copy(
            startMillis = targetStart,
            endMillis = targetEnd,
            label = targetLabel?.trim(),
            mediaRevision = targetRevision,
            updatedAtEpochMillis = nowEpochMillis(),
        )
        repository.upsertSavedRange(updated)
        return StudyResult.Success(updated)
    }

    suspend fun deleteSavedRange(rangeId: String): StudyResult<Unit> =
        if (repository.deleteSavedRange(rangeId)) {
            StudyResult.Success(Unit)
        } else {
            StudyResult.Failure(StudyFailure.ItemNotFound(rangeId))
        }

    suspend fun setEntryStudied(
        entryId: String,
        studied: Boolean,
    ): StudyResult<EntryStudyState> {
        if (!repository.entryExists(entryId)) {
            return StudyResult.Failure(StudyFailure.EntryNotFound(entryId))
        }
        val now = nowEpochMillis()
        val state = EntryStudyState(
            entryId = entryId,
            isStudied = studied,
            studiedAtEpochMillis = if (studied) now else null,
            updatedAtEpochMillis = now,
        )
        repository.saveEntryStudyState(state)
        return StudyResult.Success(state)
    }

    suspend fun loadEntryStudyBundle(entryId: String): EntryStudyBundle =
        repository.loadEntryStudyBundle(entryId)

    suspend fun collectionProgress(collectionId: String): CollectionStudyProgress =
        repository.collectionProgress(collectionId)

    suspend fun allCollectionProgress(): Map<String, CollectionStudyProgress> =
        repository.allCollectionProgress()

    suspend fun previewEntryRemoval(entryId: String): StudyRemovalPreview =
        repository.previewEntryRemoval(entryId)

    /**
     * Explicitly purges study records for [entryId] after the user reviews
     * [previewEntryRemoval] and confirms removal. Never touches media files.
     */
    suspend fun deleteAllStudyDataForEntry(entryId: String) {
        repository.deleteAllStudyDataForEntry(entryId)
    }

    companion object {
        private val fallbackStart = TimeSource.Monotonic.markNow()

        private fun defaultMonotonicClock(): () -> Long = {
            1_700_000_000_000L + fallbackStart.elapsedNow().inWholeMilliseconds
        }

        private fun defaultSequentialIdGenerator(): () -> String {
            var counter = 0L
            return {
                counter += 1L
                "study-${1_700_000_000_000L + fallbackStart.elapsedNow().inWholeMilliseconds}-$counter"
            }
        }
    }
}
