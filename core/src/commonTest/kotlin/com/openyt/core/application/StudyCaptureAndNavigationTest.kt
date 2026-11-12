package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.MediaTechnicalInfo
import com.openyt.core.domain.StudyAnchorStatus
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyCaptureFailure
import com.openyt.core.domain.StudyCaptureResult
import com.openyt.core.domain.StudyEditorDraft
import com.openyt.core.domain.StudyFailure
import com.openyt.core.domain.StudyNavigationDecision
import com.openyt.core.domain.StudyNavigationResult
import com.openyt.core.domain.StudyNavigationTargetKind
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyResult
import com.openyt.core.domain.StudyValidationError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for NX-T02 (atomic timestamp capture, queue switch race safety,
 * capability/missing-file/duration guards, stale/out-of-range anchor diagnostics
 * without silent rewriting, unsaved edit survival / cancelation, duplicate taps,
 * and saved-timestamp playback agreement).
 */
class StudyCaptureAndNavigationTest {

    private val rev1 = MediaRevision(sizeBytes = 10_000L, lastModifiedEpochMillis = 1_000L, contentSha256 = "sha-v1")
    private val rev2Shortened = MediaRevision(sizeBytes = 5_000L, lastModifiedEpochMillis = 2_000L, contentSha256 = "sha-v2")

    private fun entry(
        id: String,
        path: String = "/media/$id.mp4",
        title: String = "Lesson $id",
        revision: MediaRevision? = rev1,
    ): LibraryEntry = LibraryEntry(
        id = id,
        title = title,
        kind = MediaKind.VIDEO,
        filePath = path,
        sizeBytes = revision?.sizeBytes ?: 10_000L,
        mediaRevision = revision,
    )

    @Test
    fun capturesLoadedPositionAtomicallyAndCreatesAlignedNoteAndBookmark() = runBlocking<Unit> {
        val repo = MemoryStudyRepository()
        val entry1 = entry("entry-1")
        val entry2 = entry("entry-2")
        repo.putLibraryEntry(entry1)
        repo.putLibraryEntry(entry2)

        var clock = 1_700_000_000_000L
        var idSeq = 0
        val studyService = StudyService(repo, nowEpochMillis = { clock }, nextId = { "item-${++idSeq}" })
        val player = RecordingPlaybackSession()
        val service = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = player,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry1, entry2) },
            fileAvailability = FileAvailability { true },
            nowEpochMillis = { clock },
        )

        val item1 = PlayableMedia(entry1.filePath, entry1.title, entry1.kind, entry1.id)
        val item2 = PlayableMedia(entry2.filePath, entry2.title, entry2.kind, entry2.id)
        player.openAt(listOf(item1, item2), 0, startPositionMillis = 42_500L)
        player.completePrepare(durationMillis = 120_000L)
        player.pause() // Capturing while paused is primary study workflow

        val noteResult = service.captureAndCreateNote(
            body = "Key definition at 42.5s",
            expectedEntryId = "entry-1",
        )
        val createdNote = assertIs<StudyCaptureResult.Success<StudyNote>>(noteResult).value
        assertEquals("entry-1", createdNote.entryId)
        assertEquals(42_500L, createdNote.timestampMillis)
        assertEquals(rev1, createdNote.mediaRevision)
        assertEquals(player.currentPositionMillis, createdNote.timestampMillis)

        player.play()
        player.seekTo(75_000L)
        val bmResult = service.captureAndCreateBookmark(
            label = "Worked example",
            expectedEntryId = "entry-1",
        )
        val createdBookmark = assertIs<StudyCaptureResult.Success<StudyBookmark>>(bmResult).value
        assertEquals("entry-1", createdBookmark.entryId)
        assertEquals(75_000L, createdBookmark.timestampMillis)
        assertEquals(player.currentPositionMillis, createdBookmark.timestampMillis)
    }

    @Test
    fun queueSwitchDuringAsyncCaptureAbortsWithoutTargetingWrongOrPreviousEntry() = runBlocking<Unit> {
        val repo = MemoryStudyRepository()
        val entry1 = entry("entry-1")
        val entry2 = entry("entry-2")
        repo.putLibraryEntry(entry1)
        repo.putLibraryEntry(entry2)

        val studyService = StudyService(repo, nowEpochMillis = { 10_000L }, nextId = { "note-1" })
        val player = RecordingPlaybackSession()
        val item1 = PlayableMedia(entry1.filePath, entry1.title, entry1.kind, entry1.id)
        val item2 = PlayableMedia(entry2.filePath, entry2.title, entry2.kind, entry2.id)

        player.openAt(listOf(item1, item2), 0, startPositionMillis = 45_000L)
        player.completePrepare(durationMillis = 120_000L)

        val lookupEntered = CompletableDeferred<Unit>()
        val allowLookupFinish = CompletableDeferred<Unit>()
        val service = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = player,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = {
                lookupEntered.complete(Unit)
                allowLookupFinish.await()
                listOf(entry1, entry2)
            },
            fileAvailability = FileAvailability { true },
            nowEpochMillis = { 10_000L },
        )

        // Start capture while entry-1 is playing at 45s (even without pinning expectedEntryId)
        val captureDeferred = async {
            service.captureAndCreateNote(body = "Captured during switch")
        }

        lookupEntered.await()
        // Queue switches mid-capture to entry-2 at 5s!
        player.openAt(listOf(item1, item2), 1, startPositionMillis = 5_000L)
        player.completePrepare(durationMillis = 90_000L)
        allowLookupFinish.complete(Unit)

        val outcome = captureDeferred.await()
        val failure = assertIs<StudyCaptureResult.Failure>(outcome).failure
        val switchError = assertIs<StudyCaptureFailure.EntrySwitchRace>(failure)
        assertEquals("entry-1", switchError.expectedEntryId)
        assertEquals("entry-2", switchError.actualEntryId)

        // Neither entry-1 nor entry-2 may have any accidentally persisted note
        assertTrue(repo.loadNotesForEntry("entry-1").isEmpty())
        assertTrue(repo.loadNotesForEntry("entry-2").isEmpty())

        // Also test explicit expectedEntryId guard when player already points to entry-2
        val directMismatch = service.captureAndCreateNote(
            body = "Targeted entry-1 late",
            expectedEntryId = "entry-1",
        )
        val mismatchFailure = assertIs<StudyCaptureResult.Failure>(directMismatch).failure
        val mismatchRace = assertIs<StudyCaptureFailure.EntrySwitchRace>(mismatchFailure)
        assertEquals("entry-1", mismatchRace.expectedEntryId)
        assertEquals("entry-2", mismatchRace.actualEntryId)
        assertTrue(repo.loadNotesForEntry("entry-1").isEmpty())
        assertTrue(repo.loadNotesForEntry("entry-2").isEmpty())
    }

    @Test
    fun rejectsStoppedPreparingErrorAndUnlinkedMediaCapture() = runBlocking<Unit> {
        val repo = MemoryStudyRepository()
        val entry1 = entry("entry-1")
        repo.putLibraryEntry(entry1)
        val studyService = StudyService(repo, nowEpochMillis = { 1_000L }, nextId = { "n1" })
        val player = RecordingPlaybackSession()
        val service = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = player,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry1) },
            fileAvailability = FileAvailability { true },
            nowEpochMillis = { 1_000L },
        )

        // 1. Stopped / IDLE before playback
        val stopped = service.captureAndCreateNote("Note while stopped")
        assertIs<StudyCaptureFailure.PlayerStopped>(assertIs<StudyCaptureResult.Failure>(stopped).failure)

        // 2. Preparing before async ready
        val linked = PlayableMedia(entry1.filePath, entry1.title, entry1.kind, entry1.id)
        val preparingPlayer = RecordingPlaybackSession(autoCompleteOpenAt = false)
        val preparingService = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = preparingPlayer,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry1) },
            fileAvailability = FileAvailability { true },
            nowEpochMillis = { 1_000L },
        )
        preparingPlayer.openAt(listOf(linked), 0, startPositionMillis = 12_000L)
        val preparing = preparingService.captureLoadedPosition()
        assertIs<StudyCaptureFailure.PlayerNotReady>(assertIs<StudyCaptureResult.Failure>(preparing).failure)

        // 3. Unlinked media (no entryId)
        val unlinked = PlayableMedia("/tmp/unlinked.mp4", "Unlinked", MediaKind.VIDEO, entryId = null)
        player.openAt(listOf(unlinked), 0, startPositionMillis = 0L)
        player.completePrepare(durationMillis = 30_000L)
        val unlinkedRes = service.captureAndCreateBookmark("Moment")
        assertIs<StudyCaptureFailure.UnlinkedMedia>(assertIs<StudyCaptureResult.Failure>(unlinkedRes).failure)

        // 4. Error player state
        val badPath = PlayableMedia("/media/missing-err.mp4", "Bad", MediaKind.VIDEO, "entry-1")
        val errorPlayer = RecordingPlaybackSession(missingPaths = setOf("/media/missing-err.mp4"))
        errorPlayer.openAt(listOf(badPath), 0)
        val errorService = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = errorPlayer,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry1) },
            fileAvailability = FileAvailability { true },
            nowEpochMillis = { 1_000L },
        )
        val errCapture = errorService.captureLoadedPosition()
        assertIs<StudyCaptureFailure.PlayerError>(assertIs<StudyCaptureResult.Failure>(errCapture).failure)
    }

    @Test
    fun rejectsCaptureAndNavigationWhenSeekCapabilityUnsupported() = runBlocking<Unit> {
        val repo = MemoryStudyRepository()
        val entry1 = entry("entry-1")
        repo.putLibraryEntry(entry1)
        val studyService = StudyService(repo, nowEpochMillis = { 1_000L }, nextId = { "n1" })
        val created = assertIs<StudyResult.Success<StudyNote>>(
            studyService.createNote("entry-1", timestampMillis = 20_000L, body = "Saved earlier"),
        ).value

        val externalCapabilities = PlaybackCapabilities(
            canSeek = false,
            canMiniPlayer = false,
            canPause = false,
            isInternalPlayer = false,
            limitationDescription = "External system player does not support in-app seek or session control",
        )
        val externalPlayer = RecordingPlaybackSession(capabilities = externalCapabilities)
        externalPlayer.openAt(listOf(PlayableMedia(entry1.filePath, entry1.title, entry1.kind, entry1.id)), 0)

        val service = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = externalPlayer,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry1) },
            fileAvailability = FileAvailability { true },
            nowEpochMillis = { 5_000L },
        )

        val captureAttempt = service.captureAndCreateNote("Should fail cleanly")
        val capFailure = assertIs<StudyCaptureFailure.CapabilityUnsupported>(
            assertIs<StudyCaptureResult.Failure>(captureAttempt).failure,
        )
        assertTrue(capFailure.message.contains("External system player"))

        val navAttempt = service.openNoteTimestamp(created.id)
        val navUnsupported = assertIs<StudyNavigationResult.CapabilityUnsupported>(navAttempt)
        assertTrue(navUnsupported.reason.contains("External system player"))
        assertEquals(0, externalPlayer.seekCalls.size)

        // Null launcher or launcher without sessionControl also reports CapabilityUnsupported
        val noControlService = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = object : PlaybackLauncher {
                override val canPlay: Boolean = true
                override fun play(items: List<PlayableMedia>, startIndex: Int) = Unit
            },
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry1) },
            fileAvailability = FileAvailability { true },
        )
        assertIs<StudyNavigationResult.CapabilityUnsupported>(noControlService.openNoteTimestamp(created.id))
    }

    @Test
    fun missingMediaFileFailsBeforeCallingPlaybackSession() = runBlocking<Unit> {
        val repo = MemoryStudyRepository()
        val missingEntry = entry("entry-missing", path = "/media/deleted.mp4")
        repo.putLibraryEntry(missingEntry)
        val studyService = StudyService(repo, nowEpochMillis = { 1_000L }, nextId = { "bm-1" })
        val bm = assertIs<StudyResult.Success<StudyBookmark>>(
            studyService.createBookmark("entry-missing", 15_000L, "Important"),
        ).value

        val player = RecordingPlaybackSession()
        val service = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = player,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(missingEntry) },
            fileAvailability = FileAvailability { false },
        )

        val nav = service.openBookmarkTimestamp(bm.id)
        val missing = assertIs<StudyNavigationResult.MissingMediaFile>(nav)
        assertEquals("entry-missing", missing.entryId)
        assertEquals("/media/deleted.mp4", missing.filePath)
        assertEquals(0, player.openAtCalls.size, "Missing file check must abort before invoking player openAt")
        assertEquals(0, player.seekCalls.size)
    }

    @Test
    fun editedAndShortenedMediaFlagsOutOfRangeAndStaleWithoutSilentRewrite() = runBlocking<Unit> {
        val repo = MemoryStudyRepository()
        var currentEntry = entry("entry-1", revision = rev1)
        repo.putLibraryEntry(currentEntry)

        var clock = 1_000L
        var seq = 0
        val studyService = StudyService(repo, nowEpochMillis = { clock }, nextId = { "s-${++seq}" })
        val store = MemoryPlaybackStore()
        store.savePosition(
            currentEntry.filePath,
            PlaybackCheckpoint(positionMillis = 10_000L, durationMillis = 120_000L, entryId = currentEntry.id),
            currentEntry.id,
        )

        val longNote = assertIs<StudyResult.Success<StudyNote>>(
            studyService.createNote("entry-1", timestampMillis = 95_000L, body = "Outro explanation", knownDurationMillis = 120_000L),
        ).value
        val earlyBookmark = assertIs<StudyResult.Success<StudyBookmark>>(
            studyService.createBookmark("entry-1", timestampMillis = 15_000L, label = "Opening thesis", knownDurationMillis = 120_000L),
        ).value

        // Media is edited and shortened to 60s
        currentEntry = currentEntry.copy(sizeBytes = rev2Shortened.sizeBytes, mediaRevision = rev2Shortened)
        repo.updateEntryRevision(currentEntry.id, rev2Shortened, lastVerifiedEpochMillis = 2_000L)
        store.savePosition(
            currentEntry.filePath,
            PlaybackCheckpoint(positionMillis = 5_000L, durationMillis = 60_000L, entryId = currentEntry.id),
            currentEntry.id,
        )

        val player = RecordingPlaybackSession(durationByPath = mapOf(currentEntry.filePath to 60_000L))
        val probe = MediaProbe {
            MediaTechnicalInfo(durationMillis = 60_000L, videoCodec = "h264", audioCodec = "aac")
        }
        val service = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = player,
            playbackStore = store,
            entryProvider = { listOf(currentEntry) },
            fileAvailability = FileAvailability { true },
            mediaProbe = probe,
            nowEpochMillis = { clock },
        )

        // Manual creation beyond known 60s duration is rejected
        val invalidManual = service.addOrUpdateNote("entry-1", timestampMillis = 70_000L, body = "Too late")
        val valFailure = assertIs<StudyCaptureFailure.ServiceFailure>(
            assertIs<StudyCaptureResult.Failure>(invalidManual).failure,
        ).failure
        assertEquals(
            StudyValidationError.EXCEEDS_KNOWN_DURATION,
            assertIs<StudyFailure.Validation>(valFailure).error,
        )

        // Overview surfaces stale/out-of-range issues WITHOUT rewriting stored DB timestamps
        val overview = service.loadEntryStudyOverview("entry-1")
        assertTrue(overview.hasAnchorWarnings)
        val noteView = overview.notes.single()
        assertEquals(95_000L, noteView.note.timestampMillis)
        assertEquals(StudyAnchorStatus.STALE_REVISION_AND_OUT_OF_RANGE, noteView.anchorHealth.status)
        assertEquals(60_000L, noteView.anchorHealth.knownDurationMillis)
        assertEquals(60_000L, noteView.anchorHealth.suggestedClampedStartMillis)
        val bmView = overview.bookmarks.single()
        assertEquals(StudyAnchorStatus.STALE_REVISION, bmView.anchorHealth.status)

        // Opening the 95s note surfaces OutOfRangeAnchor rather than seeking silently to 60s
        val openOutOfRange = service.openNoteTimestamp(longNote.id)
        val outOfRange = assertIs<StudyNavigationResult.OutOfRangeAnchor>(openOutOfRange)
        assertEquals(95_000L, outOfRange.savedTimestampMillis)
        assertEquals(60_000L, outOfRange.knownDurationMillis)
        assertEquals(60_000L, outOfRange.suggestedClampedStartMillis)
        assertEquals(0, player.openAtCalls.size, "Must not start playback on unconfirmed out-of-range anchor")
        // Verify persisted note timestamp was NEVER silently overwritten
        assertEquals(95_000L, repo.getNoteById(longNote.id)!!.timestampMillis)

        // Opening 15s bookmark succeeds and warns that revision is stale while position matches
        clock += 1_000L
        val openEarly = service.openBookmarkTimestamp(earlyBookmark.id)
        val opened = assertIs<StudyNavigationResult.Opened>(openEarly)
        assertEquals(15_000L, opened.requestedPositionMillis)
        assertEquals(15_000L, opened.actualPositionMillis)
        assertEquals(StudyAnchorStatus.STALE_REVISION, opened.anchorHealth.status)
        assertEquals(15_000L, player.currentPositionMillis)

        // Explicit user-confirmed repair updates timestamps cleanly and clears warnings
        clock += 1_000L
        val clamped = assertIs<StudyResult.Success<StudyNote>>(
            service.clampOutOfRangeNoteToDuration(longNote.id, 60_000L),
        ).value
        assertEquals(60_000L, clamped.timestampMillis)
        assertEquals(rev2Shortened, clamped.mediaRevision)
        assertIs<StudyResult.Success<StudyBookmark>>(
            service.reanchorBookmarkToCurrentRevision(earlyBookmark.id),
        )
        assertFalse(service.loadEntryStudyOverview("entry-1").hasAnchorWarnings)
    }

    @Test
    fun unsavedEditsSurviveNormalNavigationPromptBeforeLossAndCancelCleanly() = runBlocking<Unit> {
        val repo = MemoryStudyRepository()
        val entry1 = entry("entry-1")
        repo.putLibraryEntry(entry1)
        val studyService = StudyService(repo, nowEpochMillis = { 1_000L }, nextId = { "n1" })
        val savedNote = assertIs<StudyResult.Success<StudyNote>>(
            studyService.createNote("entry-1", timestampMillis = 25_000L, body = "Original saved body"),
        ).value

        val store = MemoryPlaybackStore()
        store.savePosition(entry1.filePath, PlaybackCheckpoint(10_000L, 60_000L, entry1.id), entry1.id)
        val service = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = RecordingPlaybackSession(),
            playbackStore = store,
            entryProvider = { listOf(entry1) },
            fileAvailability = FileAvailability { true },
        )

        val editor = StudyEditorCoordinator()
        assertEquals(StudyNavigationDecision.Proceeding, editor.openExistingNoteDraft(savedNote))
        val key = StudyEditorDraft.noteDraftKey("entry-1", savedNote.id)
        editor.updateNoteDraft(key, body = "Unsaved edits in progress", timestampMillis = 30_000L)
        assertTrue(editor.state.value.hasUnsavedChanges)

        // 1. Normal screen navigation preserves draft untouched
        assertEquals(StudyNavigationDecision.PreservedInBackground, editor.onNormalNavigation())
        assertTrue(editor.draftsForEntry("entry-1").isNotEmpty())
        assertEquals(StudyNavigationDecision.Proceeding, editor.openExistingNoteDraft(savedNote))
        val restored = assertIs<StudyEditorDraft.NoteDraft>(editor.state.value.focusedDraft)
        assertEquals("Unsaved edits in progress", restored.draftBody)
        assertEquals(30_000L, restored.draftTimestampMillis)

        // 2. Trying to open a different draft OR cancel prompts before losing unsaved text
        val switchDecision = editor.openNewBookmarkDraft("entry-1", timestampMillis = 10_000L)
        assertIs<StudyNavigationDecision.PromptConfirmDiscard>(switchDecision)
        editor.keepEditing()
        assertEquals(key, editor.state.value.focusedDraftKey)

        // 3. Cancel edit prompts first; confirming cancel discards buffer and leaves persisted DB intact
        val cancelDecision = editor.requestCancelDraft(key)
        assertIs<StudyNavigationDecision.PromptConfirmDiscard>(cancelDecision)
        editor.confirmDiscard()
        assertNull(editor.state.value.drafts[key])
        assertEquals("Original saved body", repo.getNoteById(savedNote.id)!!.body)
        assertEquals(25_000L, repo.getNoteById(savedNote.id)!!.timestampMillis)

        // 4. Failed validation on save retains unsaved text for correction
        assertEquals(StudyNavigationDecision.Proceeding, editor.openExistingNoteDraft(savedNote))
        editor.updateNoteDraft(key, body = "Edited text that hit duration error", timestampMillis = 90_000L)
        val failedSave = editor.saveNoteDraft(key, service)
        assertIs<StudyCaptureResult.Failure>(failedSave)
        val retainedAfterError = assertIs<StudyEditorDraft.NoteDraft>(editor.state.value.drafts[key])
        assertEquals("Edited text that hit duration error", retainedAfterError.draftBody)
        assertNotNull(editor.state.value.lastError)

        // Fix timestamp and save -> persists and clears draft
        editor.updateNoteDraft(key, timestampMillis = 40_000L)
        val okSave = assertIs<StudyCaptureResult.Success<StudyNote>>(editor.saveNoteDraft(key, service)).value
        assertEquals("Edited text that hit duration error", okSave.body)
        assertEquals(40_000L, okSave.timestampMillis)
        assertNull(editor.state.value.drafts[key])
    }

    @Test
    fun duplicateTapsAreSuppressedWhileDistinctTargetsAndMovedPositionsWork() = runBlocking<Unit> {
        val repo = MemoryStudyRepository()
        val entry1 = entry("entry-1")
        repo.putLibraryEntry(entry1)

        var clock = 10_000L
        var idSeq = 0
        val studyService = StudyService(repo, nowEpochMillis = { clock }, nextId = { "id-${++idSeq}" })
        val player = RecordingPlaybackSession()
        val item1 = PlayableMedia(entry1.filePath, entry1.title, entry1.kind, entry1.id)
        player.openAt(listOf(item1), 0, startPositionMillis = 12_000L)
        player.completePrepare(durationMillis = 120_000L)

        val service = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = player,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry1) },
            fileAvailability = FileAvailability { true },
            nowEpochMillis = { clock },
            duplicateTapWindowMillis = 400L,
        )

        // Rapid double-tap on bookmark capture within window -> second ignored
        val firstBm = assertIs<StudyCaptureResult.Success<StudyBookmark>>(
            service.captureAndCreateBookmark("Chorus"),
        ).value
        clock += 50L
        val duplicateBm = assertIs<StudyCaptureResult.Failure>(
            service.captureAndCreateBookmark("Chorus"),
        ).failure
        assertIs<StudyCaptureFailure.DuplicateTapIgnored>(duplicateBm)
        assertEquals(1, repo.loadBookmarksForEntry("entry-1").size)

        // Even with fixed clock, moving playback >= 500ms allows a genuine new bookmark
        player.seekTo(25_000L)
        val secondBm = assertIs<StudyCaptureResult.Success<StudyBookmark>>(
            service.captureAndCreateBookmark("Bridge"),
        ).value
        assertEquals(25_000L, secondBm.timestampMillis)
        assertEquals(2, repo.loadBookmarksForEntry("entry-1").size)

        // Rapid double-tap on opening same bookmark -> second returns DuplicateTapIgnored cleanly
        clock += 1_000L
        val nav1 = assertIs<StudyNavigationResult.Opened>(service.openBookmarkTimestamp(firstBm.id))
        assertEquals(12_000L, nav1.actualPositionMillis)
        val navSeekCountAfterFirst = player.seekCalls.size
        clock += 80L
        assertIs<StudyNavigationResult.DuplicateTapIgnored>(service.openBookmarkTimestamp(firstBm.id))
        assertEquals(navSeekCountAfterFirst, player.seekCalls.size)

        // Tapping a DIFFERENT bookmark immediately works even inside the window
        val nav2 = assertIs<StudyNavigationResult.Opened>(service.openBookmarkTimestamp(secondBm.id))
        assertEquals(25_000L, nav2.actualPositionMillis)
    }

    @Test
    fun noteAndBookmarkNavigationSwitchesQueueOrSeeksInPlaceAndVerifiesPositionAgreement() = runBlocking<Unit> {
        val repo = MemoryStudyRepository()
        val entry1 = entry("entry-1")
        val entry2 = entry("entry-2")
        repo.putLibraryEntry(entry1)
        repo.putLibraryEntry(entry2)

        var clock = 1_000L
        var seq = 0
        val studyService = StudyService(repo, nowEpochMillis = { clock }, nextId = { "target-${++seq}" })
        val noteOnEntry1 = assertIs<StudyResult.Success<StudyNote>>(
            studyService.createNote("entry-1", timestampMillis = 33_000L, body = "Target on entry 1"),
        ).value

        val player = RecordingPlaybackSession()
        val item1 = PlayableMedia(entry1.filePath, entry1.title, entry1.kind, entry1.id)
        val item2 = PlayableMedia(entry2.filePath, entry2.title, entry2.kind, entry2.id)

        // Start with entry-2 currently active
        player.openAt(listOf(item1, item2), 1, startPositionMillis = 10_000L)
        player.completePrepare(durationMillis = 120_000L)

        val service = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = player,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry1, entry2) },
            fileAvailability = FileAvailability { true },
            nowEpochMillis = { clock },
        )

        // Opening note on entry-1 switches loaded item cleanly to entry-1 at 33_000ms
        val openResult = assertIs<StudyNavigationResult.Opened>(
            service.openNoteTimestamp(noteOnEntry1.id, queueContext = listOf(entry1, entry2)),
        )
        assertEquals("entry-1", openResult.entryId)
        assertEquals(StudyNavigationTargetKind.NOTE, openResult.targetKind)
        assertEquals(33_000L, openResult.requestedPositionMillis)
        assertEquals(33_000L, openResult.actualPositionMillis)
        assertEquals("entry-1", player.loadedMedia?.entryId)
        assertEquals(33_000L, player.currentPositionMillis)

        // Opening bookmark on ALREADY loaded entry-1 seeks in-place without calling openAt again
        val bmOnEntry1 = assertIs<StudyResult.Success<StudyBookmark>>(
            studyService.createBookmark("entry-1", timestampMillis = 52_000L, label = "Midpoint"),
        ).value
        val openCallsBeforeSeek = player.openAtCalls.size
        clock += 1_000L
        val seekResult = assertIs<StudyNavigationResult.Opened>(service.openBookmarkTimestamp(bmOnEntry1.id))
        assertEquals(openCallsBeforeSeek, player.openAtCalls.size, "Same loaded entry should seek in place")
        assertEquals(52_000L, seekResult.actualPositionMillis)
        assertEquals(52_000L, player.currentPositionMillis)

        // Async-preparing player reports exact pendingPrepareSeekMillis matching requested time
        val asyncPlayer = RecordingPlaybackSession(autoCompleteOpenAt = false)
        val asyncService = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = asyncPlayer,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry1, entry2) },
            fileAvailability = FileAvailability { true },
            nowEpochMillis = { clock },
        )
        val pendingOpen = assertIs<StudyNavigationResult.Opened>(asyncService.openNoteTimestamp(noteOnEntry1.id))
        assertNull(pendingOpen.actualPositionMillis)
        assertEquals(33_000L, pendingOpen.pendingPrepareSeekMillis)
        asyncPlayer.completePrepare(durationMillis = 120_000L)
        assertEquals(33_000L, asyncPlayer.currentPositionMillis)

        // When actual player reveals a shortened duration (20s) upon opening without prior cache/probe,
        // navigation surfaces OutOfRangeAnchor rather than pretending the clamped end equals 33s
        val shrunkPlayer = RecordingPlaybackSession(durationByPath = mapOf(entry1.filePath to 20_000L))
        val shrunkService = StudyCaptureAndNavigationService(
            studyService = studyService,
            playbackLauncher = shrunkPlayer,
            playbackStore = MemoryPlaybackStore(),
            entryProvider = { listOf(entry1) },
            fileAvailability = FileAvailability { true },
            nowEpochMillis = { clock + 2_000L },
        )
        val discoveredOut = assertIs<StudyNavigationResult.OutOfRangeAnchor>(
            shrunkService.openNoteTimestamp(noteOnEntry1.id),
        )
        assertEquals(33_000L, discoveredOut.savedTimestampMillis)
        assertEquals(20_000L, discoveredOut.knownDurationMillis)
    }
}

private class RecordingPlaybackSession(
    override val capabilities: PlaybackCapabilities = PlaybackCapabilities(),
    private val missingPaths: Set<String> = emptySet(),
    private val durationByPath: Map<String, Long> = emptyMap(),
    private val autoCompleteOpenAt: Boolean = true,
) : PlaybackLauncher, PlaybackSessionControl {
    override val canPlay: Boolean = true
    override var loadedMedia: PlayableMedia? = null
        private set
    override var state: PlaybackSessionState = PlaybackSessionState.IDLE
        private set
    override var currentPositionMillis: Long = 0L
        private set
    override var durationMillis: Long = 0L
        private set
    private var generation: Long = 0L
    override val currentSessionGeneration: Long
        get() = generation

    private var pendingSeek: Long? = null
    val openAtCalls = mutableListOf<Pair<String?, Long?>>()
    val seekCalls = mutableListOf<Long>()

    override fun play(items: List<PlayableMedia>, startIndex: Int) {
        val item = items.getOrNull(startIndex) ?: return
        openAtSync(item, null)
    }

    override suspend fun openAt(
        items: List<PlayableMedia>,
        startIndex: Int,
        startPositionMillis: Long?,
    ): Boolean {
        val item = items.getOrNull(startIndex) ?: return false
        return openAtSync(item, startPositionMillis)
    }

    private fun openAtSync(item: PlayableMedia, startPositionMillis: Long?): Boolean {
        openAtCalls += item.entryId to startPositionMillis
        if (item.path in missingPaths) {
            generation++
            loadedMedia = null
            state = PlaybackSessionState.ERROR
            currentPositionMillis = 0L
            durationMillis = 0L
            return false
        }
        generation++
        loadedMedia = item
        currentPositionMillis = 0L
        durationMillis = 0L
        pendingSeek = startPositionMillis?.let { clampSeekPosition(it, 0L) }
        if (!capabilities.isInternalPlayer && !capabilities.canSeek) {
            state = PlaybackSessionState.PLAYING
            currentPositionMillis = pendingSeek ?: 0L
            return true
        }
        state = PlaybackSessionState.PREPARING
        if (autoCompleteOpenAt) {
            completePrepare(durationByPath[item.path] ?: 120_000L)
        }
        return true
    }

    fun completePrepare(durationMillis: Long) {
        this.durationMillis = durationMillis
        state = PlaybackSessionState.PLAYING
        val pending = pendingSeek
        if (pending != null) {
            pendingSeek = null
            currentPositionMillis = clampSeekPosition(pending, durationMillis)
        }
    }

    override suspend fun seekTo(positionMillis: Long): Boolean {
        if (!capabilities.canSeek) return false
        if (loadedMedia == null && state != PlaybackSessionState.PREPARING) return false
        seekCalls += positionMillis
        val clamped = clampSeekPosition(positionMillis, durationMillis)
        if (state == PlaybackSessionState.PREPARING) {
            pendingSeek = clamped
        } else {
            currentPositionMillis = clamped
        }
        return true
    }

    override suspend fun pause() {
        if (state == PlaybackSessionState.PLAYING) state = PlaybackSessionState.PAUSED
    }

    override suspend fun play() {
        if (state == PlaybackSessionState.PAUSED) state = PlaybackSessionState.PLAYING
    }

    override suspend fun stop() {
        generation++
        state = PlaybackSessionState.IDLE
        loadedMedia = null
        pendingSeek = null
        currentPositionMillis = 0L
        durationMillis = 0L
    }

    override fun close() {
        generation++
        state = PlaybackSessionState.IDLE
        loadedMedia = null
        pendingSeek = null
        currentPositionMillis = 0L
        durationMillis = 0L
    }
}
