package com.openyt.data

import com.openyt.core.application.StudyRepository
import com.openyt.core.domain.CollectionStudyProgress
import com.openyt.core.domain.EntryStudyBundle
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyRemovalPreview
import com.openyt.data.db.Entry_study_state
import com.openyt.data.db.Library_entry
import com.openyt.data.db.OpenYtDatabase
import com.openyt.data.db.Study_bookmark
import com.openyt.data.db.Study_note
import com.openyt.data.db.Study_range
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SQLDelight implementation of [StudyRepository].
 */
class SqlDelightStudyRepository(
    private val database: OpenYtDatabase,
) : StudyRepository {
    private val queries = database.downloadQueries

    override suspend fun entryExists(entryId: String): Boolean = withContext(Dispatchers.Default) {
        queries.selectLibraryEntryById(entryId).executeAsOneOrNull() != null
    }

    override suspend fun currentEntryRevision(entryId: String): MediaRevision? = withContext(Dispatchers.Default) {
        queries.selectLibraryEntryById(entryId).executeAsOneOrNull()?.extractRevision()
    }

    override suspend fun loadNotesForEntry(entryId: String): List<StudyNote> = withContext(Dispatchers.Default) {
        queries.selectStudyNotesForEntry(entryId).executeAsList().map { it.toDomain() }
    }

    override suspend fun getNoteById(noteId: String): StudyNote? = withContext(Dispatchers.Default) {
        queries.selectStudyNoteById(noteId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun upsertNote(note: StudyNote): Unit = withContext(Dispatchers.Default) {
        queries.upsertStudyNote(
            id = note.id,
            entry_id = note.entryId,
            timestamp_ms = note.timestampMillis,
            end_ms = note.endMillis,
            body = note.body,
            media_revision_size = note.mediaRevision?.sizeBytes,
            media_revision_mtime = note.mediaRevision?.lastModifiedEpochMillis,
            media_revision_sha256 = note.mediaRevision?.contentSha256,
            created_at = note.createdAtEpochMillis,
            updated_at = note.updatedAtEpochMillis,
        )
    }

    override suspend fun deleteNote(noteId: String): Boolean = withContext(Dispatchers.Default) {
        queries.transactionWithResult {
            if (queries.selectStudyNoteById(noteId).executeAsOneOrNull() == null) {
                false
            } else {
                queries.deleteStudyNote(noteId)
                true
            }
        }
    }

    override suspend fun loadBookmarksForEntry(entryId: String): List<StudyBookmark> = withContext(Dispatchers.Default) {
        queries.selectStudyBookmarksForEntry(entryId).executeAsList().map { it.toDomain() }
    }

    override suspend fun getBookmarkById(bookmarkId: String): StudyBookmark? = withContext(Dispatchers.Default) {
        queries.selectStudyBookmarkById(bookmarkId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun upsertBookmark(bookmark: StudyBookmark): Unit = withContext(Dispatchers.Default) {
        queries.upsertStudyBookmark(
            id = bookmark.id,
            entry_id = bookmark.entryId,
            timestamp_ms = bookmark.timestampMillis,
            label = bookmark.label,
            media_revision_size = bookmark.mediaRevision?.sizeBytes,
            media_revision_mtime = bookmark.mediaRevision?.lastModifiedEpochMillis,
            media_revision_sha256 = bookmark.mediaRevision?.contentSha256,
            created_at = bookmark.createdAtEpochMillis,
            updated_at = bookmark.updatedAtEpochMillis,
        )
    }

    override suspend fun deleteBookmark(bookmarkId: String): Boolean = withContext(Dispatchers.Default) {
        queries.transactionWithResult {
            if (queries.selectStudyBookmarkById(bookmarkId).executeAsOneOrNull() == null) {
                false
            } else {
                queries.deleteStudyBookmark(bookmarkId)
                true
            }
        }
    }

    override suspend fun loadSavedRangesForEntry(entryId: String): List<SavedStudyRange> = withContext(Dispatchers.Default) {
        queries.selectStudyRangesForEntry(entryId).executeAsList().map { it.toDomain() }
    }

    override suspend fun getSavedRangeById(rangeId: String): SavedStudyRange? = withContext(Dispatchers.Default) {
        queries.selectStudyRangeById(rangeId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun upsertSavedRange(range: SavedStudyRange): Unit = withContext(Dispatchers.Default) {
        queries.upsertStudyRange(
            id = range.id,
            entry_id = range.entryId,
            start_ms = range.startMillis,
            end_ms = range.endMillis,
            label = range.label,
            media_revision_size = range.mediaRevision?.sizeBytes,
            media_revision_mtime = range.mediaRevision?.lastModifiedEpochMillis,
            media_revision_sha256 = range.mediaRevision?.contentSha256,
            created_at = range.createdAtEpochMillis,
            updated_at = range.updatedAtEpochMillis,
        )
    }

    override suspend fun deleteSavedRange(rangeId: String): Boolean = withContext(Dispatchers.Default) {
        queries.transactionWithResult {
            if (queries.selectStudyRangeById(rangeId).executeAsOneOrNull() == null) {
                false
            } else {
                queries.deleteStudyRange(rangeId)
                true
            }
        }
    }

    override suspend fun loadEntryStudyState(entryId: String): EntryStudyState = withContext(Dispatchers.Default) {
        queries.selectEntryStudyState(entryId).executeAsOneOrNull()?.toDomain()
            ?: EntryStudyState(entryId = entryId, isStudied = false)
    }

    override suspend fun saveEntryStudyState(state: EntryStudyState): Unit = withContext(Dispatchers.Default) {
        queries.upsertEntryStudyState(
            entry_id = state.entryId,
            is_studied = if (state.isStudied) 1L else 0L,
            studied_at = state.studiedAtEpochMillis,
            updated_at = state.updatedAtEpochMillis,
        )
    }

    override suspend fun loadStudiedEntryIds(): Set<String> = withContext(Dispatchers.Default) {
        queries.selectStudiedEntryIds().executeAsList().toCollection(linkedSetOf())
    }

    override suspend fun loadEntryStudyBundle(entryId: String): EntryStudyBundle = withContext(Dispatchers.Default) {
        queries.transactionWithResult {
            val entry = queries.selectLibraryEntryById(entryId).executeAsOneOrNull()
            val state = queries.selectEntryStudyState(entryId).executeAsOneOrNull()?.toDomain()
                ?: EntryStudyState(entryId = entryId, isStudied = false)
            val notes = queries.selectStudyNotesForEntry(entryId).executeAsList().map { it.toDomain() }
            val bookmarks = queries.selectStudyBookmarksForEntry(entryId).executeAsList().map { it.toDomain() }
            val ranges = queries.selectStudyRangesForEntry(entryId).executeAsList().map { it.toDomain() }
            EntryStudyBundle(
                entryId = entryId,
                entryExists = entry != null,
                currentMediaRevision = entry?.extractRevision(),
                studyState = state,
                notes = notes,
                bookmarks = bookmarks,
                savedRanges = ranges,
            )
        }
    }

    override suspend fun collectionProgress(collectionId: String): CollectionStudyProgress = withContext(Dispatchers.Default) {
        val row = queries.selectCollectionStudyProgress(collectionId).executeAsOneOrNull()
        CollectionStudyProgress(
            collectionId = collectionId,
            studiedCount = (row?.studied_members ?: 0L).toInt(),
            totalMemberCount = (row?.total_members ?: 0L).toInt(),
        )
    }

    override suspend fun allCollectionProgress(): Map<String, CollectionStudyProgress> = withContext(Dispatchers.Default) {
        queries.selectAllCollectionStudyProgress().executeAsList().associate { row ->
            row.collection_id to CollectionStudyProgress(
                collectionId = row.collection_id,
                studiedCount = row.studied_members.toInt(),
                totalMemberCount = row.total_members.toInt(),
            )
        }
    }

    override suspend fun previewEntryRemoval(entryId: String): StudyRemovalPreview = withContext(Dispatchers.Default) {
        queries.transactionWithResult {
            val entry = queries.selectLibraryEntryById(entryId).executeAsOneOrNull()
            val state = queries.selectEntryStudyState(entryId).executeAsOneOrNull()
            val notes = queries.selectStudyNotesForEntry(entryId).executeAsList().map { it.toDomain() }
            val bookmarks = queries.selectStudyBookmarksForEntry(entryId).executeAsList().map { it.toDomain() }
            val ranges = queries.selectStudyRangesForEntry(entryId).executeAsList().map { it.toDomain() }
            StudyRemovalPreview(
                entryId = entryId,
                entryTitle = entry?.title,
                entryExistsInLibrary = entry != null,
                notes = notes,
                bookmarks = bookmarks,
                savedRanges = ranges,
                isMarkedStudied = state?.is_studied == 1L,
                originalMediaPreserved = true,
            )
        }
    }

    override suspend fun deleteAllStudyDataForEntry(entryId: String): Unit = withContext(Dispatchers.Default) {
        database.transaction {
            queries.deleteStudyNotesForEntry(entryId)
            queries.deleteStudyBookmarksForEntry(entryId)
            queries.deleteStudyRangesForEntry(entryId)
            queries.deleteEntryStudyState(entryId)
        }
    }

    private fun Library_entry.extractRevision(): MediaRevision? =
        toMediaRevision(media_revision_size, media_revision_mtime, media_revision_sha256)

    private fun Study_note.toDomain(): StudyNote = StudyNote(
        id = id,
        entryId = entry_id,
        timestampMillis = timestamp_ms,
        endMillis = end_ms,
        body = body,
        mediaRevision = toMediaRevision(media_revision_size, media_revision_mtime, media_revision_sha256),
        createdAtEpochMillis = created_at,
        updatedAtEpochMillis = updated_at,
    )

    private fun Study_bookmark.toDomain(): StudyBookmark = StudyBookmark(
        id = id,
        entryId = entry_id,
        timestampMillis = timestamp_ms,
        label = label,
        mediaRevision = toMediaRevision(media_revision_size, media_revision_mtime, media_revision_sha256),
        createdAtEpochMillis = created_at,
        updatedAtEpochMillis = updated_at,
    )

    private fun Study_range.toDomain(): SavedStudyRange = SavedStudyRange(
        id = id,
        entryId = entry_id,
        startMillis = start_ms,
        endMillis = end_ms,
        label = label,
        mediaRevision = toMediaRevision(media_revision_size, media_revision_mtime, media_revision_sha256),
        createdAtEpochMillis = created_at,
        updatedAtEpochMillis = updated_at,
    )

    private fun Entry_study_state.toDomain(): EntryStudyState = EntryStudyState(
        entryId = entry_id,
        isStudied = is_studied == 1L,
        studiedAtEpochMillis = studied_at,
        updatedAtEpochMillis = updated_at,
    )

    private fun toMediaRevision(
        size: Long?,
        mtime: Long?,
        sha256: String?,
    ): MediaRevision? = if (size != null && mtime != null) {
        MediaRevision(
            sizeBytes = size,
            lastModifiedEpochMillis = mtime,
            contentSha256 = sha256,
        )
    } else {
        null
    }
}
