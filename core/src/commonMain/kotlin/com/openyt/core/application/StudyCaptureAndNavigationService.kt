package com.openyt.core.application

import com.openyt.core.domain.CapturedTimestamp
import com.openyt.core.domain.EntryStudyOverview
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyAnchorHealth
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyCaptureFailure
import com.openyt.core.domain.StudyCaptureResult
import com.openyt.core.domain.StudyFailure
import com.openyt.core.domain.StudyNavigationResult
import com.openyt.core.domain.StudyNavigationTargetKind
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.StudyResult
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates atomic player-position capture with entry identity checks,
 * known-duration validation, duplicate-tap suppression, anchor health diagnostics,
 * and note/bookmark timestamp navigation.
 */
class StudyCaptureAndNavigationService(
    private val studyService: StudyService,
    private val playbackLauncher: PlaybackLauncher?,
    private val playbackStore: PlaybackStore?,
    private val entryProvider: suspend () -> List<LibraryEntry>,
    private val fileAvailability: FileAvailability,
    private val mediaProbe: MediaProbe? = null,
    private val nowEpochMillis: () -> Long = { 0L },
    private val duplicateTapWindowMillis: Long = DEFAULT_DUPLICATE_TAP_WINDOW_MILLIS,
    private val seekAgreementToleranceMillis: Long = DEFAULT_SEEK_TOLERANCE_MILLIS,
) {
    private data class TapRecord(
        val atEpochMillis: Long,
        val mediaPositionMillis: Long?,
    )

    private val tapMutex = Mutex()
    private val inFlightKeys = mutableSetOf<String>()
    private val lastCompletedTaps = mutableMapOf<String, TapRecord>()

    /**
     * Atomically captures the active player's loaded entry identity, position,
     * and duration, rejecting stopped/preparing/error/unseekable/raced states.
     */
    suspend fun captureLoadedPosition(
        expectedEntryId: String? = null,
        suppressDuplicateTap: Boolean = false,
    ): StudyCaptureResult<CapturedTimestamp> {
        val actionKey = "capture-pos:${expectedEntryId ?: "active"}"
        val session = playbackLauncher?.sessionControl
            ?: return StudyCaptureResult.Failure(
                StudyCaptureFailure.CapabilityUnsupported(
                    "Active player does not expose session control for timestamp capture",
                ),
            )
        val initialSnap = session.captureSnapshot()
        if (suppressDuplicateTap) {
            if (!acquireActionSlot(actionKey, initialSnap?.positionMillis)) {
                return StudyCaptureResult.Failure(StudyCaptureFailure.DuplicateTapIgnored())
            }
        }
        return try {
            val captured = performAtomicCapture(
                session = session,
                preCapturedSnapshot = initialSnap,
                expectedEntryId = expectedEntryId,
            )
            if (suppressDuplicateTap && captured is StudyCaptureResult.Success) {
                recordCompletedTap(actionKey, captured.value.timestampMillis)
            } else if (suppressDuplicateTap) {
                releaseActionSlot(actionKey)
            }
            captured
        } finally {
            if (suppressDuplicateTap) {
                releaseInFlightOnly(actionKey)
            }
        }
    }

    /**
     * Atomically captures the loaded entry's live player position and creates a
     * study note anchored to that entry, aborting cleanly if the queue switches
     * or an external/unseekable player is active.
     */
    suspend fun captureAndCreateNote(
        body: String,
        expectedEntryId: String? = null,
        endMillis: Long? = null,
    ): StudyCaptureResult<StudyNote> {
        val session = playbackLauncher?.sessionControl
            ?: return StudyCaptureResult.Failure(
                StudyCaptureFailure.CapabilityUnsupported(
                    "Active player does not support timestamp capture",
                ),
            )
        val preSnap = session.captureSnapshot()
        val actionKey = "capture-note:${expectedEntryId ?: preSnap?.entryId ?: "active"}"
        if (!acquireActionSlot(actionKey, preSnap?.positionMillis)) {
            return StudyCaptureResult.Failure(StudyCaptureFailure.DuplicateTapIgnored())
        }
        return try {
            val capturedResult = performAtomicCapture(
                session = session,
                preCapturedSnapshot = preSnap,
                expectedEntryId = expectedEntryId,
            )
            val captured = when (capturedResult) {
                is StudyCaptureResult.Failure -> return capturedResult
                is StudyCaptureResult.Success -> capturedResult.value
            }

            if (hasSessionChanged(session, captured.entryId, captured.sessionGeneration)) {
                return StudyCaptureResult.Failure(
                    StudyCaptureFailure.EntrySwitchRace(
                        expectedEntryId = captured.entryId,
                        actualEntryId = session.loadedMedia?.entryId,
                    ),
                )
            }

            val created = studyService.createNote(
                entryId = captured.entryId,
                timestampMillis = captured.timestampMillis,
                body = body,
                endMillis = endMillis,
                knownDurationMillis = captured.knownDurationMillis,
            )
            when (created) {
                is StudyResult.Success -> {
                    recordCompletedTap(actionKey, captured.timestampMillis)
                    StudyCaptureResult.Success(created.value)
                }
                is StudyResult.Failure -> StudyCaptureResult.Failure(
                    StudyCaptureFailure.ServiceFailure(created.failure),
                )
            }
        } finally {
            releaseInFlightOnly(actionKey)
        }
    }

    /**
     * Atomically captures the loaded entry's live player position and creates a
     * named bookmark anchored to that entry, aborting cleanly if the queue switches
     * or duplicate taps arrive in quick succession.
     */
    suspend fun captureAndCreateBookmark(
        label: String,
        expectedEntryId: String? = null,
    ): StudyCaptureResult<StudyBookmark> {
        val session = playbackLauncher?.sessionControl
            ?: return StudyCaptureResult.Failure(
                StudyCaptureFailure.CapabilityUnsupported(
                    "Active player does not support bookmark capture",
                ),
            )
        val preSnap = session.captureSnapshot()
        val actionKey = "capture-bookmark:${expectedEntryId ?: preSnap?.entryId ?: "active"}"
        if (!acquireActionSlot(actionKey, preSnap?.positionMillis)) {
            return StudyCaptureResult.Failure(StudyCaptureFailure.DuplicateTapIgnored())
        }
        return try {
            val capturedResult = performAtomicCapture(
                session = session,
                preCapturedSnapshot = preSnap,
                expectedEntryId = expectedEntryId,
            )
            val captured = when (capturedResult) {
                is StudyCaptureResult.Failure -> return capturedResult
                is StudyCaptureResult.Success -> capturedResult.value
            }

            if (hasSessionChanged(session, captured.entryId, captured.sessionGeneration)) {
                return StudyCaptureResult.Failure(
                    StudyCaptureFailure.EntrySwitchRace(
                        expectedEntryId = captured.entryId,
                        actualEntryId = session.loadedMedia?.entryId,
                    ),
                )
            }

            val created = studyService.createBookmark(
                entryId = captured.entryId,
                timestampMillis = captured.timestampMillis,
                label = label,
                knownDurationMillis = captured.knownDurationMillis,
            )
            when (created) {
                is StudyResult.Success -> {
                    recordCompletedTap(actionKey, captured.timestampMillis)
                    StudyCaptureResult.Success(created.value)
                }
                is StudyResult.Failure -> StudyCaptureResult.Failure(
                    StudyCaptureFailure.ServiceFailure(created.failure),
                )
            }
        } finally {
            releaseInFlightOnly(actionKey)
        }
    }

    /**
     * Creates or updates a note while validating against any known duration
     * for [entryId] (live session, stored checkpoint, or optional probe).
     */
    suspend fun addOrUpdateNote(
        entryId: String,
        timestampMillis: Long,
        body: String,
        noteId: String? = null,
        endMillis: Long? = null,
        clearEndMillis: Boolean = false,
        reanchorToCurrentRevision: Boolean = false,
    ): StudyCaptureResult<StudyNote> {
        val entry = findEntry(entryId)
            ?: return StudyCaptureResult.Failure(
                StudyCaptureFailure.ServiceFailure(StudyFailure.EntryNotFound(entryId)),
            )
        val knownDuration = resolveKnownDurationMillis(
            entry = entry,
            activeSnapshot = playbackLauncher?.sessionControl?.captureSnapshot(),
        )
        val result = if (noteId == null) {
            studyService.createNote(
                entryId = entryId,
                timestampMillis = timestampMillis,
                body = body,
                endMillis = endMillis,
                knownDurationMillis = knownDuration,
            )
        } else {
            studyService.updateNote(
                noteId = noteId,
                body = body,
                timestampMillis = timestampMillis,
                endMillis = endMillis,
                clearEndMillis = clearEndMillis,
                knownDurationMillis = knownDuration,
                reanchorToCurrentRevision = reanchorToCurrentRevision,
            )
        }
        return when (result) {
            is StudyResult.Success -> StudyCaptureResult.Success(result.value)
            is StudyResult.Failure -> StudyCaptureResult.Failure(
                StudyCaptureFailure.ServiceFailure(result.failure),
            )
        }
    }

    /**
     * Creates or updates a bookmark while validating against any known duration
     * for [entryId].
     */
    suspend fun addOrUpdateBookmark(
        entryId: String,
        timestampMillis: Long,
        label: String,
        bookmarkId: String? = null,
        reanchorToCurrentRevision: Boolean = false,
    ): StudyCaptureResult<StudyBookmark> {
        val entry = findEntry(entryId)
            ?: return StudyCaptureResult.Failure(
                StudyCaptureFailure.ServiceFailure(StudyFailure.EntryNotFound(entryId)),
            )
        val knownDuration = resolveKnownDurationMillis(
            entry = entry,
            activeSnapshot = playbackLauncher?.sessionControl?.captureSnapshot(),
        )
        val result = if (bookmarkId == null) {
            studyService.createBookmark(
                entryId = entryId,
                timestampMillis = timestampMillis,
                label = label,
                knownDurationMillis = knownDuration,
            )
        } else {
            studyService.updateBookmark(
                bookmarkId = bookmarkId,
                label = label,
                timestampMillis = timestampMillis,
                knownDurationMillis = knownDuration,
                reanchorToCurrentRevision = reanchorToCurrentRevision,
            )
        }
        return when (result) {
            is StudyResult.Success -> StudyCaptureResult.Success(result.value)
            is StudyResult.Failure -> StudyCaptureResult.Failure(
                StudyCaptureFailure.ServiceFailure(result.failure),
            )
        }
    }

    /**
     * Loads persisted study annotations for [entryId] and attaches anchor-health
     * diagnostics against current revision and known duration without mutating
     * stored anchors.
     */
    suspend fun loadEntryStudyOverview(entryId: String): EntryStudyOverview {
        val bundle = studyService.loadEntryStudyBundle(entryId)
        val entry = findEntry(entryId)
        val knownDuration = if (entry != null) {
            resolveKnownDurationMillis(
                entry = entry,
                activeSnapshot = playbackLauncher?.sessionControl?.captureSnapshot(),
            )
        } else {
            playbackStore?.positionForEntry(entryId)?.durationMillis?.takeIf { it > 0L }
        }
        return StudyAnchorInspector.inspectBundle(bundle, knownDuration)
    }

    suspend fun openNoteTimestamp(
        noteId: String,
        queueContext: List<LibraryEntry>? = null,
    ): StudyNavigationResult {
        val note = studyService.getNoteById(noteId)
            ?: return StudyNavigationResult.ItemNotFound(noteId)
        return openTimestampTarget(
            entryId = note.entryId,
            targetKind = StudyNavigationTargetKind.NOTE,
            targetId = note.id,
            targetPositionMillis = note.timestampMillis,
            targetEndMillis = note.endMillis,
            queueContext = queueContext,
            inspectHealth = { revision, duration ->
                StudyAnchorInspector.inspectNote(note, revision, duration)
            },
        )
    }

    suspend fun openBookmarkTimestamp(
        bookmarkId: String,
        queueContext: List<LibraryEntry>? = null,
    ): StudyNavigationResult {
        val bookmark = studyService.getBookmarkById(bookmarkId)
            ?: return StudyNavigationResult.ItemNotFound(bookmarkId)
        return openTimestampTarget(
            entryId = bookmark.entryId,
            targetKind = StudyNavigationTargetKind.BOOKMARK,
            targetId = bookmark.id,
            targetPositionMillis = bookmark.timestampMillis,
            targetEndMillis = null,
            queueContext = queueContext,
            inspectHealth = { revision, duration ->
                StudyAnchorInspector.inspectBookmark(bookmark, revision, duration)
            },
        )
    }

    suspend fun openSavedRangeStart(
        rangeId: String,
        queueContext: List<LibraryEntry>? = null,
    ): StudyNavigationResult {
        val range = studyService.getSavedRangeById(rangeId)
            ?: return StudyNavigationResult.ItemNotFound(rangeId)
        return openTimestampTarget(
            entryId = range.entryId,
            targetKind = StudyNavigationTargetKind.SAVED_RANGE,
            targetId = range.id,
            targetPositionMillis = range.startMillis,
            targetEndMillis = range.endMillis,
            queueContext = queueContext,
            inspectHealth = { revision, duration ->
                StudyAnchorInspector.inspectSavedRange(range, revision, duration)
            },
        )
    }

    /**
     * Opens [entryId] at an explicit [timestampMillis] (for example, after the user
     * confirms Playback from an `openyt://library/entry/<id>?t=<ms>` preview).
     */
    suspend fun openEntryAtTimestamp(
        entryId: String,
        timestampMillis: Long,
        queueContext: List<LibraryEntry>? = null,
    ): StudyNavigationResult {
        val safeTimestamp = timestampMillis.coerceAtLeast(0L)
        return openTimestampTarget(
            entryId = entryId,
            targetKind = StudyNavigationTargetKind.ENTRY_TIMESTAMP,
            targetId = "$entryId@$safeTimestamp",
            targetPositionMillis = safeTimestamp,
            targetEndMillis = null,
            queueContext = queueContext,
            inspectHealth = { revision, duration ->
                StudyAnchorInspector.inspectTimestamp(safeTimestamp, revision, duration)
            },
        )
    }

    /**
     * Explicit user-confirmed anchor update after reviewing a stale or
     * out-of-range note warning. Never invoked implicitly on navigation.
     */
    suspend fun reanchorNoteToCurrentRevision(
        noteId: String,
        correctedTimestampMillis: Long? = null,
        correctedEndMillis: Long? = null,
        clearEndMillis: Boolean = false,
    ): StudyResult<StudyNote> {
        val existing = studyService.getNoteById(noteId)
            ?: return StudyResult.Failure(StudyFailure.ItemNotFound(noteId))
        val entry = findEntry(existing.entryId)
            ?: return StudyResult.Failure(StudyFailure.EntryNotFound(existing.entryId))
        val knownDuration = resolveKnownDurationMillis(
            entry = entry,
            activeSnapshot = playbackLauncher?.sessionControl?.captureSnapshot(),
        )
        return studyService.updateNote(
            noteId = noteId,
            body = existing.body,
            timestampMillis = correctedTimestampMillis ?: existing.timestampMillis,
            endMillis = correctedEndMillis,
            clearEndMillis = clearEndMillis,
            knownDurationMillis = knownDuration,
            reanchorToCurrentRevision = true,
        )
    }

    /**
     * Explicit user-confirmed anchor update after reviewing a stale or
     * out-of-range bookmark warning.
     */
    suspend fun reanchorBookmarkToCurrentRevision(
        bookmarkId: String,
        correctedTimestampMillis: Long? = null,
    ): StudyResult<StudyBookmark> {
        val existing = studyService.getBookmarkById(bookmarkId)
            ?: return StudyResult.Failure(StudyFailure.ItemNotFound(bookmarkId))
        val entry = findEntry(existing.entryId)
            ?: return StudyResult.Failure(StudyFailure.EntryNotFound(existing.entryId))
        val knownDuration = resolveKnownDurationMillis(
            entry = entry,
            activeSnapshot = playbackLauncher?.sessionControl?.captureSnapshot(),
        )
        return studyService.updateBookmark(
            bookmarkId = bookmarkId,
            label = existing.label,
            timestampMillis = correctedTimestampMillis ?: existing.timestampMillis,
            knownDurationMillis = knownDuration,
            reanchorToCurrentRevision = true,
        )
    }

    /**
     * Explicit user-confirmed clamp for a note exceeding [knownDurationMillis].
     */
    suspend fun clampOutOfRangeNoteToDuration(
        noteId: String,
        knownDurationMillis: Long,
    ): StudyResult<StudyNote> {
        val existing = studyService.getNoteById(noteId)
            ?: return StudyResult.Failure(StudyFailure.ItemNotFound(noteId))
        val entry = findEntry(existing.entryId)
            ?: return StudyResult.Failure(StudyFailure.EntryNotFound(existing.entryId))
        val health = StudyAnchorInspector.inspectNote(existing, entry.mediaRevision, knownDurationMillis)
        return studyService.updateNote(
            noteId = noteId,
            body = existing.body,
            timestampMillis = health.suggestedClampedStartMillis,
            endMillis = health.suggestedClampedEndMillis,
            clearEndMillis = existing.endMillis != null && health.suggestedClampedEndMillis == null,
            knownDurationMillis = knownDurationMillis.takeIf { it > 0L },
            reanchorToCurrentRevision = true,
        )
    }

    /**
     * Explicit user-confirmed clamp for a bookmark exceeding [knownDurationMillis].
     */
    suspend fun clampOutOfRangeBookmarkToDuration(
        bookmarkId: String,
        knownDurationMillis: Long,
    ): StudyResult<StudyBookmark> {
        val existing = studyService.getBookmarkById(bookmarkId)
            ?: return StudyResult.Failure(StudyFailure.ItemNotFound(bookmarkId))
        val entry = findEntry(existing.entryId)
            ?: return StudyResult.Failure(StudyFailure.EntryNotFound(existing.entryId))
        val health = StudyAnchorInspector.inspectBookmark(existing, entry.mediaRevision, knownDurationMillis)
        return studyService.updateBookmark(
            bookmarkId = bookmarkId,
            label = existing.label,
            timestampMillis = health.suggestedClampedStartMillis,
            knownDurationMillis = knownDurationMillis.takeIf { it > 0L },
            reanchorToCurrentRevision = true,
        )
    }

    private suspend fun performAtomicCapture(
        session: PlaybackSessionControl,
        preCapturedSnapshot: PlaybackPositionSnapshot?,
        expectedEntryId: String?,
    ): StudyCaptureResult<CapturedTimestamp> {
        val snapshot = preCapturedSnapshot ?: session.captureSnapshot()
            ?: return StudyCaptureResult.Failure(
                if (session.state == PlaybackSessionState.ERROR) {
                    StudyCaptureFailure.PlayerError()
                } else {
                    StudyCaptureFailure.PlayerStopped()
                },
            )

        when (snapshot.state) {
            PlaybackSessionState.IDLE -> return StudyCaptureResult.Failure(StudyCaptureFailure.PlayerStopped())
            PlaybackSessionState.ERROR -> return StudyCaptureResult.Failure(StudyCaptureFailure.PlayerError())
            PlaybackSessionState.PREPARING -> return StudyCaptureResult.Failure(StudyCaptureFailure.PlayerNotReady())
            PlaybackSessionState.PLAYING,
            PlaybackSessionState.PAUSED,
            PlaybackSessionState.COMPLETED -> Unit
        }

        if (!snapshot.capabilities.isInternalPlayer || !snapshot.capabilities.canSeek) {
            return StudyCaptureResult.Failure(
                StudyCaptureFailure.CapabilityUnsupported(
                    snapshot.capabilities.limitationDescription
                        ?: "Active player does not support exact timestamp capture",
                ),
            )
        }

        val loadedEntryId = snapshot.entryId?.takeIf { it.isNotBlank() }
            ?: return StudyCaptureResult.Failure(StudyCaptureFailure.UnlinkedMedia())

        if (expectedEntryId != null && loadedEntryId != expectedEntryId) {
            return StudyCaptureResult.Failure(
                StudyCaptureFailure.EntrySwitchRace(
                    expectedEntryId = expectedEntryId,
                    actualEntryId = loadedEntryId,
                ),
            )
        }

        val entry = findEntry(loadedEntryId)
            ?: return StudyCaptureResult.Failure(
                StudyCaptureFailure.ServiceFailure(StudyFailure.EntryNotFound(loadedEntryId)),
            )

        if (!fileAvailability.exists(entry.filePath)) {
            return StudyCaptureResult.Failure(
                StudyCaptureFailure.MissingMediaFile(entryId = entry.id, filePath = entry.filePath),
            )
        }

        if (hasSessionChanged(session, loadedEntryId, snapshot.sessionGeneration)) {
            return StudyCaptureResult.Failure(
                StudyCaptureFailure.EntrySwitchRace(
                    expectedEntryId = expectedEntryId ?: loadedEntryId,
                    actualEntryId = session.loadedMedia?.entryId,
                ),
            )
        }

        val knownDuration = resolveKnownDurationMillis(entry, snapshot)
        return StudyCaptureResult.Success(
            CapturedTimestamp(
                entryId = entry.id,
                timestampMillis = snapshot.positionMillis.coerceAtLeast(0L),
                knownDurationMillis = knownDuration,
                mediaRevision = entry.mediaRevision,
                sessionGeneration = snapshot.sessionGeneration,
            ),
        )
    }

    private suspend fun openTimestampTarget(
        entryId: String,
        targetKind: StudyNavigationTargetKind,
        targetId: String,
        targetPositionMillis: Long,
        targetEndMillis: Long?,
        queueContext: List<LibraryEntry>?,
        inspectHealth: (com.openyt.core.domain.MediaRevision?, Long?) -> StudyAnchorHealth,
    ): StudyNavigationResult {
        val actionKey = "nav:${targetKind.name}:$targetId"
        if (!acquireActionSlot(actionKey, targetPositionMillis)) {
            return StudyNavigationResult.DuplicateTapIgnored
        }
        return try {
            val entries = entryProvider()
            val entry = entries.firstOrNull { it.id == entryId }
                ?: return StudyNavigationResult.EntryNotFound(entryId)

            if (!fileAvailability.exists(entry.filePath)) {
                return StudyNavigationResult.MissingMediaFile(entryId = entry.id, filePath = entry.filePath)
            }

            val launcher = playbackLauncher
            val session = launcher?.sessionControl
            if (launcher == null || !launcher.canPlay || session == null) {
                return StudyNavigationResult.CapabilityUnsupported(
                    "In-app playback control is unavailable on this host",
                )
            }
            if (!session.capabilities.isInternalPlayer || !session.capabilities.canSeek) {
                return StudyNavigationResult.CapabilityUnsupported(
                    session.capabilities.limitationDescription
                        ?: "Active player does not support seeking to saved timestamps",
                )
            }

            val preSnap = session.captureSnapshot()
            val knownDuration = resolveKnownDurationMillis(entry, preSnap)
            val preHealth = inspectHealth(entry.mediaRevision, knownDuration)
            if (preHealth.isOutOfRange && knownDuration != null) {
                return StudyNavigationResult.OutOfRangeAnchor(
                    entryId = entry.id,
                    targetKind = targetKind,
                    targetId = targetId,
                    savedTimestampMillis = targetPositionMillis,
                    savedEndMillis = targetEndMillis,
                    knownDurationMillis = knownDuration,
                    suggestedClampedStartMillis = preHealth.suggestedClampedStartMillis,
                    suggestedClampedEndMillis = preHealth.suggestedClampedEndMillis,
                    anchorHealth = preHealth,
                )
            }

            val sameEntryLoaded = preSnap != null &&
                preSnap.entryId == entry.id &&
                preSnap.state != PlaybackSessionState.IDLE &&
                preSnap.state != PlaybackSessionState.ERROR

            val jumped = if (sameEntryLoaded) {
                session.seekTo(targetPositionMillis)
            } else {
                val queueEntries = buildPlayableQueue(entry, queueContext ?: entries)
                val items = queueEntries.map {
                    PlayableMedia(
                        path = it.filePath,
                        title = it.title,
                        kind = it.kind,
                        entryId = it.id,
                    )
                }
                val startIndex = queueEntries.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
                session.openAt(items, startIndex, startPositionMillis = targetPositionMillis)
            }

            if (!jumped) {
                if (!session.capabilities.canSeek || !session.capabilities.isInternalPlayer) {
                    return StudyNavigationResult.CapabilityUnsupported(
                        session.capabilities.limitationDescription
                            ?: "Active player does not support timestamp seek",
                    )
                }
                return StudyNavigationResult.PlaybackFailed(
                    entryId = entry.id,
                    reason = "Player rejected timestamp navigation to ${targetPositionMillis}ms",
                )
            }

            val postSnap = session.captureSnapshot()
                ?: return StudyNavigationResult.PlaybackFailed(
                    entryId = entry.id,
                    reason = "Playback session became inactive after seek request",
                )

            if (postSnap.entryId != entry.id) {
                return StudyNavigationResult.PlaybackFailed(
                    entryId = entry.id,
                    reason = "Playback switched to a different entry (${postSnap.entryId})",
                )
            }

            val resolvedPostDuration = postSnap.knownDurationMillis ?: knownDuration
            val postHealth = inspectHealth(entry.mediaRevision, resolvedPostDuration)
            if (postHealth.isOutOfRange && resolvedPostDuration != null) {
                return StudyNavigationResult.OutOfRangeAnchor(
                    entryId = entry.id,
                    targetKind = targetKind,
                    targetId = targetId,
                    savedTimestampMillis = targetPositionMillis,
                    savedEndMillis = targetEndMillis,
                    knownDurationMillis = resolvedPostDuration,
                    suggestedClampedStartMillis = postHealth.suggestedClampedStartMillis,
                    suggestedClampedEndMillis = postHealth.suggestedClampedEndMillis,
                    anchorHealth = postHealth,
                )
            }

            if (postSnap.state == PlaybackSessionState.PREPARING) {
                recordCompletedTap(actionKey, targetPositionMillis)
                return StudyNavigationResult.Opened(
                    entryId = entry.id,
                    targetKind = targetKind,
                    targetId = targetId,
                    requestedPositionMillis = targetPositionMillis,
                    actualPositionMillis = null,
                    pendingPrepareSeekMillis = targetPositionMillis,
                    anchorHealth = postHealth,
                )
            }

            val actualPosition = postSnap.positionMillis
            if (abs(actualPosition - targetPositionMillis) > seekAgreementToleranceMillis) {
                return StudyNavigationResult.PlaybackFailed(
                    entryId = entry.id,
                    reason = "Player settled at ${actualPosition}ms instead of saved ${targetPositionMillis}ms",
                )
            }

            recordCompletedTap(actionKey, targetPositionMillis)
            StudyNavigationResult.Opened(
                entryId = entry.id,
                targetKind = targetKind,
                targetId = targetId,
                requestedPositionMillis = targetPositionMillis,
                actualPositionMillis = actualPosition,
                pendingPrepareSeekMillis = null,
                anchorHealth = postHealth,
            )
        } finally {
            releaseInFlightOnly(actionKey)
        }
    }

    private fun buildPlayableQueue(
        target: LibraryEntry,
        candidates: List<LibraryEntry>,
    ): List<LibraryEntry> {
        val playable = candidates.filter {
            it.kind != MediaKind.SUBTITLE &&
                (it.id == target.id || fileAvailability.exists(it.filePath))
        }
        return if (playable.any { it.id == target.id }) {
            playable
        } else {
            listOf(target) + playable
        }
    }

    private suspend fun findEntry(entryId: String): LibraryEntry? =
        entryProvider().firstOrNull { it.id == entryId }

    private suspend fun resolveKnownDurationMillis(
        entry: LibraryEntry,
        activeSnapshot: PlaybackPositionSnapshot?,
    ): Long? {
        if (activeSnapshot?.entryId == entry.id) {
            activeSnapshot.knownDurationMillis?.let { return it }
        }
        playbackStore?.positionForEntry(entry.id)?.durationMillis?.takeIf { it > 0L }?.let { return it }
        playbackStore?.position(entry.filePath)?.durationMillis?.takeIf { it > 0L }?.let { return it }
        return if (fileAvailability.exists(entry.filePath)) {
            runCatching { mediaProbe?.probe(entry.filePath)?.durationMillis?.takeIf { it > 0L } }.getOrNull()
        } else {
            null
        }
    }

    private fun hasSessionChanged(
        session: PlaybackSessionControl,
        expectedEntryId: String,
        expectedGeneration: Long,
    ): Boolean {
        val currentSnap = session.captureSnapshot() ?: return true
        return currentSnap.entryId != expectedEntryId ||
            currentSnap.sessionGeneration != expectedGeneration
    }

    private suspend fun acquireActionSlot(
        actionKey: String,
        mediaPositionMillis: Long?,
    ): Boolean = tapMutex.withLock {
        if (actionKey in inFlightKeys) return@withLock false
        val previous = lastCompletedTaps[actionKey]
        if (previous != null) {
            val now = nowEpochMillis()
            val elapsed = now - previous.atEpochMillis
            val sameWindow = elapsed in 0L until duplicateTapWindowMillis
            val samePosition = mediaPositionMillis == null ||
                previous.mediaPositionMillis == null ||
                abs(mediaPositionMillis - previous.mediaPositionMillis) < POSITION_CHANGE_BYPASS_MILLIS
            if (sameWindow && samePosition) {
                return@withLock false
            }
        }
        inFlightKeys += actionKey
        true
    }

    private suspend fun recordCompletedTap(
        actionKey: String,
        mediaPositionMillis: Long?,
    ) {
        tapMutex.withLock {
            inFlightKeys -= actionKey
            lastCompletedTaps[actionKey] = TapRecord(
                atEpochMillis = nowEpochMillis(),
                mediaPositionMillis = mediaPositionMillis,
            )
        }
    }

    private suspend fun releaseActionSlot(actionKey: String) {
        tapMutex.withLock {
            inFlightKeys -= actionKey
        }
    }

    private suspend fun releaseInFlightOnly(actionKey: String) {
        tapMutex.withLock {
            inFlightKeys -= actionKey
        }
    }

    companion object {
        const val DEFAULT_DUPLICATE_TAP_WINDOW_MILLIS = 400L
        const val DEFAULT_SEEK_TOLERANCE_MILLIS = 1_000L
        private const val POSITION_CHANGE_BYPASS_MILLIS = 500L
    }
}
