package com.openyt.core.application

import com.openyt.core.domain.CollectionStudyProgress
import com.openyt.core.domain.EntryStudyBundle
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyRemovalPreview

/**
 * Persistence port for study mode notes, bookmarks, saved A/B ranges, and
 * explicit manual study completion state.
 *
 * Study records bind to stable library [entryId] identifiers rather than mutable
 * file paths or source URLs. Neither path relocation nor implicit database
 * replacement may silently cascade-delete authored study text.
 */
interface StudyRepository {
    suspend fun entryExists(entryId: String): Boolean

    suspend fun currentEntryRevision(entryId: String): MediaRevision?

    suspend fun loadNotesForEntry(entryId: String): List<StudyNote>

    suspend fun getNoteById(noteId: String): StudyNote?

    suspend fun upsertNote(note: StudyNote)

    suspend fun deleteNote(noteId: String): Boolean

    suspend fun loadBookmarksForEntry(entryId: String): List<StudyBookmark>

    suspend fun getBookmarkById(bookmarkId: String): StudyBookmark?

    suspend fun upsertBookmark(bookmark: StudyBookmark)

    suspend fun deleteBookmark(bookmarkId: String): Boolean

    suspend fun loadSavedRangesForEntry(entryId: String): List<SavedStudyRange>

    suspend fun getSavedRangeById(rangeId: String): SavedStudyRange?

    suspend fun upsertSavedRange(range: SavedStudyRange)

    suspend fun deleteSavedRange(rangeId: String): Boolean

    suspend fun loadEntryStudyState(entryId: String): EntryStudyState

    suspend fun saveEntryStudyState(state: EntryStudyState)

    suspend fun loadStudiedEntryIds(): Set<String>

    suspend fun loadEntryStudyBundle(entryId: String): EntryStudyBundle

    suspend fun collectionProgress(collectionId: String): CollectionStudyProgress

    suspend fun allCollectionProgress(): Map<String, CollectionStudyProgress>

    suspend fun previewEntryRemoval(entryId: String): StudyRemovalPreview

    suspend fun deleteAllStudyDataForEntry(entryId: String)
}
