package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.StudyFailure
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyResult
import com.openyt.core.domain.StudyValidationError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic unit tests for [StudyService], [StudyValidator], and [MemoryStudyRepository]
 * covering NX-T01 contracts.
 */
class StudyRepositoryAndServiceTest {

    private fun testHarness(): Triple<MemoryStudyRepository, StudyService, () -> Unit> {
        val repo = MemoryStudyRepository()
        var clock = 1_000L
        var seq = 0
        val service = StudyService(
            repository = repo,
            nowEpochMillis = { clock },
            nextId = { "id-${++seq}" },
        )
        return Triple(repo, service) { clock += 100L }
    }

    @Test
    fun crudUsesInjectedClockStableIdsAndPreservesCreatedAtOnEdit() = runTest {
        val repo = MemoryStudyRepository()
        var now = 10_000L
        var idSeq = 0
        val service = StudyService(repo, nowEpochMillis = { now }, nextId = { "study-${++idSeq}" })

        val rev = MediaRevision(sizeBytes = 4_096L, lastModifiedEpochMillis = 5_000L, contentSha256 = "sha-v1")
        repo.putLibraryEntry(
            LibraryEntry(
                id = "entry-1",
                title = "Lecture 1",
                kind = MediaKind.VIDEO,
                filePath = "/media/lecture1.mp4",
                mediaRevision = rev,
            ),
        )

        val createdNote = (service.createNote("entry-1", 15_000L, "Initial note") as StudyResult.Success).value
        assertEquals("study-1", createdNote.id)
        assertEquals(10_000L, createdNote.createdAtEpochMillis)
        assertEquals(10_000L, createdNote.updatedAtEpochMillis)
        assertEquals(rev, createdNote.mediaRevision)

        now = 25_000L
        val updatedNote = (service.updateNote(createdNote.id, "Updated note text") as StudyResult.Success).value
        assertEquals("study-1", updatedNote.id)
        assertEquals(10_000L, updatedNote.createdAtEpochMillis)
        assertEquals(25_000L, updatedNote.updatedAtEpochMillis)
        assertEquals("Updated note text", updatedNote.body)

        val createdBookmark = (service.createBookmark("entry-1", 30_000L, "  Key Proof  ") as StudyResult.Success).value
        assertEquals("Key Proof", createdBookmark.label)
        assertEquals(25_000L, createdBookmark.createdAtEpochMillis)

        now = 35_000L
        val createdRange = (service.saveRange("entry-1", 12_000L, 18_500L, "Repeat phrase") as StudyResult.Success).value
        assertEquals(6_500L, createdRange.durationMillis)
        assertEquals(35_000L, createdRange.createdAtEpochMillis)

        val bundle = service.loadEntryStudyBundle("entry-1")
        assertTrue(bundle.entryExists)
        assertEquals(1, bundle.notes.size)
        assertEquals(1, bundle.bookmarks.size)
        assertEquals(1, bundle.savedRanges.size)
        assertFalse(bundle.hasStaleAnchors)

        assertIs<StudyResult.Success<Unit>>(service.deleteNote(createdNote.id))
        assertIs<StudyResult.Failure>(service.deleteNote(createdNote.id))
    }

    @Test
    fun enforcesUnicodeCodePointAndValidationBounds() = runTest {
        val repo = MemoryStudyRepository()
        val service = StudyService(repo, nowEpochMillis = { 1_000L }, nextId = { "id" })
        repo.putLibraryEntry(
            LibraryEntry("e1", "Unicode Media", MediaKind.VIDEO, "/tmp/u.mp4"),
        )

        // Notes: 20,000 Unicode chars allowed (multi-script + emoji surrogate pairs + newlines)
        val unicodePrefix = "Estudo ação 日本語 دراسة \n 🎓"
        val maxBody = unicodePrefix + "a".repeat(StudyValidator.MAX_NOTE_BODY_CHARS - StudyValidator.unicodeCodePointCount(unicodePrefix))
        assertEquals(StudyValidator.MAX_NOTE_BODY_CHARS, StudyValidator.unicodeCodePointCount(maxBody))

        val okNote = assertIs<StudyResult.Success<StudyNote>>(service.createNote("e1", 0L, maxBody))
        assertEquals(maxBody, okNote.value.body)

        val tooLongNote = service.createNote("e1", 0L, maxBody + "b")
        val noteFail = assertIs<StudyResult.Failure>(tooLongNote).failure as StudyFailure.Validation
        assertEquals(StudyValidationError.NOTE_BODY_TOO_LONG, noteFail.error)

        val blankNote = assertIs<StudyResult.Failure>(service.createNote("e1", 0L, "  \n\t ")).failure as StudyFailure.Validation
        assertEquals(StudyValidationError.EMPTY_NOTE_BODY, blankNote.error)

        // Bookmarks: 120 supplementary Unicode emoji code points (240 UTF-16 chars) allowed; 121 rejected
        val maxEmojiLabel = "🔖".repeat(StudyValidator.MAX_BOOKMARK_LABEL_CHARS)
        assertEquals(240, maxEmojiLabel.length)
        assertEquals(120, StudyValidator.unicodeCodePointCount(maxEmojiLabel))
        assertIs<StudyResult.Success<*>>(service.createBookmark("e1", 5_000L, maxEmojiLabel))

        val tooLongBookmark = assertIs<StudyResult.Failure>(
            service.createBookmark("e1", 5_000L, maxEmojiLabel + "🔖"),
        ).failure as StudyFailure.Validation
        assertEquals(StudyValidationError.BOOKMARK_LABEL_TOO_LONG, tooLongBookmark.error)

        // Ranges: bounds, minimum 1s interval, duration check, and label limits
        val negative = assertIs<StudyResult.Failure>(service.saveRange("e1", -1L, 5_000L)).failure as StudyFailure.Validation
        assertEquals(StudyValidationError.NEGATIVE_TIMESTAMP, negative.error)

        val inverted = assertIs<StudyResult.Failure>(service.saveRange("e1", 5_000L, 5_000L)).failure as StudyFailure.Validation
        assertEquals(StudyValidationError.INVALID_TIME_RANGE, inverted.error)

        val tooShort = assertIs<StudyResult.Failure>(service.saveRange("e1", 5_000L, 5_999L)).failure as StudyFailure.Validation
        assertEquals(StudyValidationError.RANGE_TOO_SHORT, tooShort.error)

        val exactOneSecond = service.saveRange("e1", 5_000L, 6_000L, label = null, knownDurationMillis = 10_000L)
        assertIs<StudyResult.Success<*>>(exactOneSecond)

        val exceedsDuration = assertIs<StudyResult.Failure>(
            service.saveRange("e1", 5_000L, 10_001L, knownDurationMillis = 10_000L),
        ).failure as StudyFailure.Validation
        assertEquals(StudyValidationError.EXCEEDS_KNOWN_DURATION, exceedsDuration.error)
    }

    @Test
    fun overlappingNotesAndSameTimestampOrderingRemainDeterministic() = runTest {
        val repo = MemoryStudyRepository()
        var now = 100L
        var ids = listOf("n-b", "n-a", "n-range-early", "n-point-same-tick")
        var idx = 0
        val service = StudyService(
            repository = repo,
            nowEpochMillis = { now },
            nextId = { ids[idx++] },
        )
        repo.putLibraryEntry(LibraryEntry("e1", "Video", MediaKind.VIDEO, "/v.mp4"))

        // Created out of timestamp & ID order at identical and overlapping times
        now = 300L
        service.createNote("e1", 10_000L, "Point note B at 10s (later clock)")
        now = 200L
        service.createNote("e1", 10_000L, "Point note A at 10s (earlier clock)")
        now = 100L
        service.createNote("e1", 10_000L, "Range overlapping 10s..20s", endMillis = 20_000L)
        now = 200L
        service.createNote("e1", 10_000L, "Point note same tick 200 (lex earlier id)")

        // Overlapping range notes also created
        var rIdx = 0
        val rangeService = StudyService(repo, nowEpochMillis = { 500L }, nextId = { "r-${++rIdx}" })
        rangeService.saveRange("e1", 5_000L, 15_000L, "Loop 1")
        rangeService.saveRange("e1", 10_000L, 25_000L, "Overlapping Loop 2")
        rangeService.saveRange("e1", 5_000L, 12_000L, "Shorter Loop at 5s")

        val notes = service.loadEntryStudyBundle("e1").notes
        assertEquals(4, notes.size)
        // Deterministic ordering: timestamp ASC, COALESCE(end, -1) ASC, createdAt ASC, id ASC
        assertEquals("n-a", notes[0].id)              // 10s, point (-1), created=200, id="n-a"
        assertEquals("n-point-same-tick", notes[1].id) // 10s, point (-1), created=200, id="n-point-same-tick"
        assertEquals("n-b", notes[2].id)              // 10s, point (-1), created=300
        assertEquals("n-range-early", notes[3].id)    // 10s, end=20s

        val ranges = service.loadEntryStudyBundle("e1").savedRanges
        assertEquals(listOf("r-3", "r-1", "r-2"), ranges.map { it.id })
    }

    @Test
    fun rejectsMissingEntryAndDetectsContentRevisionStalenessWithoutLosingLocatorMoves() = runTest {
        val repo = MemoryStudyRepository()
        var now = 1_000L
        var seq = 0
        val service = StudyService(repo, nowEpochMillis = { now }, nextId = { "item-${++seq}" })

        // Missing entry rejected
        val missingNote = assertIs<StudyResult.Failure>(service.createNote("does-not-exist", 1_000L, "Hello"))
        assertIs<StudyFailure.EntryNotFound>(missingNote.failure)
        val missingBookmark = assertIs<StudyResult.Failure>(service.createBookmark("does-not-exist", 1_000L, "Mark"))
        assertIs<StudyFailure.EntryNotFound>(missingBookmark.failure)
        val missingRange = assertIs<StudyResult.Failure>(service.saveRange("does-not-exist", 1_000L, 3_000L))
        assertIs<StudyFailure.EntryNotFound>(missingRange.failure)
        val missingStudy = assertIs<StudyResult.Failure>(service.setEntryStudied("does-not-exist", true))
        assertIs<StudyFailure.EntryNotFound>(missingStudy.failure)

        val rev1 = MediaRevision(sizeBytes = 20_000L, lastModifiedEpochMillis = 50_000L, contentSha256 = "sha-1")
        repo.putLibraryEntry(
            LibraryEntry("e1", "Course", MediaKind.VIDEO, "/downloads/course.mp4", mediaRevision = rev1),
        )
        val note1 = (service.createNote("e1", 12_000L, "Note anchored to rev1") as StudyResult.Success).value
        val bm1 = (service.createBookmark("e1", 20_000L, "BM rev1") as StudyResult.Success).value
        val range1 = (service.saveRange("e1", 1_000L, 5_000L, "Range rev1") as StudyResult.Success).value

        // Locator/path change preserves anchors without flagging stale
        repo.updateEntryPath("e1", "/archive/relocated/course.mp4")
        val afterMove = service.loadEntryStudyBundle("e1")
        assertFalse(afterMove.hasStaleAnchors)
        assertEquals(1, afterMove.notes.size)

        // Same SHA-256 content with different mtime (e.g. copy across volumes) stays valid
        repo.updateEntryRevision("e1", rev1.copy(lastModifiedEpochMillis = 99_999L), 100_000L)
        assertFalse(service.loadEntryStudyBundle("e1").hasStaleAnchors)

        // Content revision change (edited media) flags old anchors potentially stale
        val rev2 = MediaRevision(sizeBytes = 18_500L, lastModifiedEpochMillis = 120_000L, contentSha256 = "sha-2")
        repo.updateEntryRevision("e1", rev2, 120_000L)
        now = 130_000L
        val note2 = (service.createNote("e1", 25_000L, "Fresh note on rev2") as StudyResult.Success).value

        // Editing text body alone without re-anchoring preserves stale flag
        now = 140_000L
        service.updateNote(note1.id, "Edited body while still anchored to rev1")

        val bundleAfterEdit = service.loadEntryStudyBundle("e1")
        assertTrue(bundleAfterEdit.hasStaleAnchors)
        assertEquals(setOf(note1.id), bundleAfterEdit.staleNoteIds)
        assertEquals(setOf(bm1.id), bundleAfterEdit.staleBookmarkIds)
        assertEquals(setOf(range1.id), bundleAfterEdit.staleRangeIds)
        assertFalse(note2.id in bundleAfterEdit.staleNoteIds)

        // Re-anchoring updates the stored revision and clears the stale flag
        now = 150_000L
        service.updateNote(note1.id, "Confirmed time on rev2", timestampMillis = 11_500L)
        assertFalse(note1.id in service.loadEntryStudyBundle("e1").staleNoteIds)
    }

    @Test
    fun manualCompletionTracksCollectionMembershipAndRemovalPreviewWithoutCascadeLoss() = runTest {
        val repo = MemoryStudyRepository()
        val playbackStore = MemoryPlaybackStore()
        var now = 5_000L
        var seq = 0
        val service = StudyService(repo, nowEpochMillis = { now }, nextId = { "s-${++seq}" })

        repo.putLibraryEntry(LibraryEntry("e1", "Part 1", MediaKind.VIDEO, "/e1.mp4"))
        repo.putLibraryEntry(LibraryEntry("e2", "Part 2", MediaKind.VIDEO, "/e2.mp4"))
        repo.putLibraryEntry(LibraryEntry("e3", "Part 3", MediaKind.VIDEO, "/e3.mp4"))

        // e1 is shared across two collections
        repo.setCollectionMembers("col-a", setOf("e1", "e2"))
        repo.setCollectionMembers("col-b", setOf("e1"))

        service.setEntryStudied("e1", true)
        assertEquals(1, service.collectionProgress("col-a").studiedCount)
        assertEquals(2, service.collectionProgress("col-a").totalMemberCount)
        assertEquals(1, service.collectionProgress("col-b").studiedCount)
        assertEquals(1, service.collectionProgress("col-b").totalMemberCount)

        // Saving playback checkpoint near end (resets resumeMillis to 0) does NOT clear studied state
        playbackStore.savePosition(
            "/e1.mp4",
            PlaybackCheckpoint(positionMillis = 59_500L, durationMillis = 60_000L, entryId = "e1"),
            "e1",
        )
        assertEquals(0L, playbackStore.position("/e1.mp4", "e1")?.resumeMillis)
        assertTrue(service.loadEntryStudyBundle("e1").studyState.isStudied)

        // Membership changes reflect accurately while preserving shared entry completion
        service.setEntryStudied("e3", true)
        repo.addEntryToCollection("col-a", "e3")
        assertEquals(2, service.collectionProgress("col-a").studiedCount)
        assertEquals(3, service.collectionProgress("col-a").totalMemberCount)

        repo.removeEntryFromCollection("col-a", "e1")
        assertEquals(1, service.collectionProgress("col-a").studiedCount)
        assertEquals(2, service.collectionProgress("col-a").totalMemberCount)
        // e1 stays studied in col-b!
        assertEquals(1, service.collectionProgress("col-b").studiedCount)

        // Authored notes + removal preview + no silent loss on library record removal
        service.createNote("e1", 4_000L, "Critical study note")
        val previewBefore = service.previewEntryRemoval("e1")
        assertTrue(previewBefore.entryExistsInLibrary)
        assertEquals("Part 1", previewBefore.entryTitle)
        assertEquals(1, previewBefore.noteCount)
        assertTrue(previewBefore.isMarkedStudied)
        assertTrue(previewBefore.requiresConfirmationPrompt)
        assertTrue(previewBefore.originalMediaPreserved)

        repo.removeLibraryEntry("e1")
        val previewAfterRecordDelete = service.previewEntryRemoval("e1")
        assertFalse(previewAfterRecordDelete.entryExistsInLibrary)
        assertNull(previewAfterRecordDelete.entryTitle)
        assertEquals("Critical study note", previewAfterRecordDelete.notes.single().body)

        // Explicit deletion after confirmation cleans up authored study records
        service.deleteAllStudyDataForEntry("e1")
        val afterConfirmDelete = service.previewEntryRemoval("e1")
        assertEquals(0, afterConfirmDelete.noteCount)
        assertFalse(afterConfirmDelete.isMarkedStudied)
        assertFalse(afterConfirmDelete.requiresConfirmationPrompt)
    }
}
