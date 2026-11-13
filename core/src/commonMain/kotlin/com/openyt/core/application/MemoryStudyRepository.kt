package com.openyt.core.application

import com.openyt.core.domain.CollectionStudyProgress
import com.openyt.core.domain.EntryStudyBundle
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyComparators
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyRemovalPreview
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe in-memory implementation of [StudyRepository] for pure KMP tests
 * and previews. Mirrors SQLDelight ordering, collection membership rules, and
 * non-cascading authored text preservation guarantees.
 */
class MemoryStudyRepository : StudyRepository {
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, LibraryEntry>()
    private val collections = linkedMapOf<String, MutableSet<String>>()

    private val notes = linkedMapOf<String, StudyNote>()
    private val bookmarks = linkedMapOf<String, StudyBookmark>()
    private val ranges = linkedMapOf<String, SavedStudyRange>()
    private val studyStates = linkedMapOf<String, EntryStudyState>()

    suspend fun putLibraryEntry(entry: LibraryEntry) = mutex.withLock {
        entries[entry.id] = entry
    }

    suspend fun updateEntryPath(entryId: String, filePath: String) = mutex.withLock {
        val existing = entries[entryId] ?: return@withLock
        entries[entryId] = existing.copy(filePath = filePath)
    }

    suspend fun updateEntryRevision(
        entryId: String,
        revision: MediaRevision,
        lastVerifiedEpochMillis: Long,
    ) = mutex.withLock {
        val existing = entries[entryId] ?: return@withLock
        entries[entryId] = existing.copy(
            mediaRevision = revision,
            lastVerifiedEpochMillis = lastVerifiedEpochMillis,
        )
    }

    /**
     * Simulates removing the library entry record itself (never implicitly cascading
     * to authored study notes/bookmarks/ranges).
     */
    suspend fun removeLibraryEntry(entryId: String) = mutex.withLock {
        entries.remove(entryId)
        collections.values.forEach { it.remove(entryId) }
    }

    suspend fun setCollectionMembers(collectionId: String, entryIds: Set<String>) = mutex.withLock {
        collections[collectionId] = entryIds.toMutableSet()
    }

    suspend fun addEntryToCollection(collectionId: String, entryId: String) = mutex.withLock {
        collections.getOrPut(collectionId) { linkedSetOf() }.add(entryId)
        Unit
    }

    suspend fun removeEntryFromCollection(collectionId: String, entryId: String) = mutex.withLock {
        collections[collectionId]?.remove(entryId)
        Unit
    }

    override suspend fun entryExists(entryId: String): Boolean = mutex.withLock {
        entryId in entries
    }

    override suspend fun currentEntryRevision(entryId: String): MediaRevision? = mutex.withLock {
        entries[entryId]?.mediaRevision
    }

    override suspend fun loadNotesForEntry(entryId: String): List<StudyNote> = mutex.withLock {
        sortedNotesLocked(entryId)
    }

    override suspend fun getNoteById(noteId: String): StudyNote? = mutex.withLock {
        notes[noteId]
    }

    override suspend fun upsertNote(note: StudyNote) = mutex.withLock {
        notes[note.id] = note
    }

    override suspend fun deleteNote(noteId: String): Boolean = mutex.withLock {
        notes.remove(noteId) != null
    }

    override suspend fun loadBookmarksForEntry(entryId: String): List<StudyBookmark> = mutex.withLock {
        sortedBookmarksLocked(entryId)
    }

    override suspend fun getBookmarkById(bookmarkId: String): StudyBookmark? = mutex.withLock {
        bookmarks[bookmarkId]
    }

    override suspend fun upsertBookmark(bookmark: StudyBookmark) = mutex.withLock {
        bookmarks[bookmark.id] = bookmark
    }

    override suspend fun deleteBookmark(bookmarkId: String): Boolean = mutex.withLock {
        bookmarks.remove(bookmarkId) != null
    }

    override suspend fun loadSavedRangesForEntry(entryId: String): List<SavedStudyRange> = mutex.withLock {
        sortedRangesLocked(entryId)
    }

    override suspend fun getSavedRangeById(rangeId: String): SavedStudyRange? = mutex.withLock {
        ranges[rangeId]
    }

    override suspend fun upsertSavedRange(range: SavedStudyRange) = mutex.withLock {
        ranges[range.id] = range
    }

    override suspend fun deleteSavedRange(rangeId: String): Boolean = mutex.withLock {
        ranges.remove(rangeId) != null
    }

    override suspend fun loadEntryStudyState(entryId: String): EntryStudyState = mutex.withLock {
        studyStates[entryId] ?: EntryStudyState(entryId = entryId, isStudied = false)
    }

    override suspend fun saveEntryStudyState(state: EntryStudyState) = mutex.withLock {
        studyStates[state.entryId] = state
    }

    override suspend fun loadStudiedEntryIds(): Set<String> = mutex.withLock {
        studyStates.values.filter { it.isStudied }.mapTo(linkedSetOf()) { it.entryId }
    }

    override suspend fun loadEntryStudyBundle(entryId: String): EntryStudyBundle = mutex.withLock {
        val entry = entries[entryId]
        EntryStudyBundle(
            entryId = entryId,
            entryExists = entry != null,
            currentMediaRevision = entry?.mediaRevision,
            studyState = studyStates[entryId] ?: EntryStudyState(entryId = entryId, isStudied = false),
            notes = sortedNotesLocked(entryId),
            bookmarks = sortedBookmarksLocked(entryId),
            savedRanges = sortedRangesLocked(entryId),
        )
    }

    override suspend fun collectionProgress(collectionId: String): CollectionStudyProgress = mutex.withLock {
        progressForCollectionLocked(collectionId)
    }

    override suspend fun allCollectionProgress(): Map<String, CollectionStudyProgress> = mutex.withLock {
        collections.keys.associateWith { progressForCollectionLocked(it) }
    }

    override suspend fun previewEntryRemoval(entryId: String): StudyRemovalPreview = mutex.withLock {
        val entry = entries[entryId]
        val entryState = studyStates[entryId]
        StudyRemovalPreview(
            entryId = entryId,
            entryTitle = entry?.title,
            entryExistsInLibrary = entry != null,
            notes = sortedNotesLocked(entryId),
            bookmarks = sortedBookmarksLocked(entryId),
            savedRanges = sortedRangesLocked(entryId),
            isMarkedStudied = entryState?.isStudied == true,
            originalMediaPreserved = true,
        )
    }

    override suspend fun deleteAllStudyDataForEntry(entryId: String) = mutex.withLock {
        notes.entries.removeAll { it.value.entryId == entryId }
        bookmarks.entries.removeAll { it.value.entryId == entryId }
        ranges.entries.removeAll { it.value.entryId == entryId }
        studyStates.remove(entryId)
        Unit
    }

    private fun sortedNotesLocked(entryId: String): List<StudyNote> =
        notes.values.filter { it.entryId == entryId }.sortedWith(StudyComparators.noteOrder)

    private fun sortedBookmarksLocked(entryId: String): List<StudyBookmark> =
        bookmarks.values.filter { it.entryId == entryId }.sortedWith(StudyComparators.bookmarkOrder)

    private fun sortedRangesLocked(entryId: String): List<SavedStudyRange> =
        ranges.values.filter { it.entryId == entryId }.sortedWith(StudyComparators.savedRangeOrder)

    private fun progressForCollectionLocked(collectionId: String): CollectionStudyProgress {
        val members = collections[collectionId].orEmpty().filter { it in entries }
        val studied = members.count { studyStates[it]?.isStudied == true }
        return CollectionStudyProgress(
            collectionId = collectionId,
            studiedCount = studied,
            totalMemberCount = members.size,
        )
    }
}
