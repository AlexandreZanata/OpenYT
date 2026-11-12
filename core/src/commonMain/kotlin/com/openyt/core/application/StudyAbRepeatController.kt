package com.openyt.core.application

import com.openyt.core.domain.AbRepeatFailure
import com.openyt.core.domain.AbRepeatResult
import com.openyt.core.domain.AbRepeatStatus
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyAbRepeatState
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates explicit active-session A/B repetition across platforms through
 * [PlaybackSessionControl].
 *
 * Guarantees:
 * - Explicit user/session action only: persisted [SavedStudyRange] rows never
 *   auto-start playback or repeat on initialization or restart.
 * - Capability & duration validation: external/unseekable sessions and unknown
 *   durations disable A/B repeat with structured explanations instead of
 *   simulating loops.
 * - Serialized/coalesced seeks: concurrent position callbacks or slow seeks
 *   coalesce behind [seekMutex] and a settling latch so slow backends never
 *   enter an infinite seek storm.
 * - Lifecycle & pause safety: preserves PAUSED states (never fights user pause
 *   or audio focus loss) and immediately clears active repeat on item switch,
 *   explicit stop, close, or native player error.
 */
class StudyAbRepeatController(
    private val playbackLauncher: PlaybackLauncher?,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = defaultMonotonicClock(),
    private val seekCooldownMillis: Long = DEFAULT_SEEK_COOLDOWN_MILLIS,
    private val maxConsecutiveUnsettledSeeks: Int = DEFAULT_MAX_CONSECUTIVE_UNSETTLED_SEEKS,
) {
    private data class LoopTracking(
        val epoch: Long = 0L,
        val seekInFlight: Boolean = false,
        val recoveringFromCompleted: Boolean = false,
        val awaitingReturnBelowB: Boolean = false,
        val lastSeekTimestampMillis: Long = 0L,
        val consecutiveUnsettledSeeks: Int = 0,
        val closed: Boolean = false,
    )

    private val actionMutex = Mutex()
    private val seekMutex = Mutex()

    private val _state = MutableStateFlow(StudyAbRepeatState())
    val state: StateFlow<StudyAbRepeatState> = _state.asStateFlow()

    private val tracking = MutableStateFlow(LoopTracking())
    private var unregisterListener: (() -> Unit)? = null
    private var seekJob: Job? = null

    init {
        val session = playbackLauncher?.sessionControl
        if (session == null) {
            _state.value = StudyAbRepeatState(
                status = AbRepeatStatus.UNSUPPORTED,
                isSupported = false,
                disabledReason = "In-app playback session control is unavailable",
            )
        } else {
            unregisterListener = session.registerSessionListener { snapshot ->
                onSnapshotObserved(snapshot)
            }
            syncCapabilitySupport(session.captureSnapshot(), session.capabilities)
        }
    }

    /**
     * Marks point A at the active session's current playback position.
     */
    suspend fun markPointA(): AbRepeatResult<StudyAbRepeatState> = actionMutex.withLock {
        val session = requireControl() ?: return@withLock failureResult(unsupportedFailure())
        val snapshot = requireReadySnapshot(session) ?: return@withLock failureResult(snapshotFailure(session))
        val duration = snapshot.knownDurationMillis
            ?: return@withLock disabledUnknownDuration()

        val posA = snapshot.positionMillis.coerceIn(0L, duration)
        val current = _state.value
        val sameBinding = current.entryId == snapshot.entryId &&
            current.mediaPath == snapshot.mediaPath &&
            current.sessionGeneration == snapshot.sessionGeneration

        cancelActiveSeekAndBumpEpoch()
        session.updateActiveRepeatBounds(null)

        val preservedB = if (sameBinding) {
            current.pointBMillis?.takeIf { existingB ->
                existingB <= duration &&
                    existingB > posA &&
                    (existingB - posA) >= StudyValidator.MIN_RANGE_INTERVAL_MILLIS
            }
        } else {
            null
        }

        val next = StudyAbRepeatState(
            status = if (preservedB != null) AbRepeatStatus.READY else AbRepeatStatus.INACTIVE,
            isSupported = true,
            disabledReason = null,
            entryId = snapshot.entryId,
            mediaPath = snapshot.mediaPath,
            sessionGeneration = snapshot.sessionGeneration,
            knownDurationMillis = duration,
            pointAMillis = posA,
            pointBMillis = preservedB,
            activeSavedRangeId = null,
            loopCount = 0,
            lastError = null,
        )
        _state.value = next
        AbRepeatResult.Success(next)
    }

    /**
     * Marks point B at the active session's current playback position,
     * requiring an existing point A bound to the same loaded media session
     * with `B - A >= 1000 ms` and `B <= knownDurationMillis`.
     */
    suspend fun markPointB(): AbRepeatResult<StudyAbRepeatState> = actionMutex.withLock {
        val session = requireControl() ?: return@withLock failureResult(unsupportedFailure())
        val snapshot = requireReadySnapshot(session) ?: return@withLock failureResult(snapshotFailure(session))
        val duration = snapshot.knownDurationMillis
            ?: return@withLock disabledUnknownDuration()

        val current = _state.value
        val sameBinding = current.entryId == snapshot.entryId &&
            current.mediaPath == snapshot.mediaPath &&
            current.sessionGeneration == snapshot.sessionGeneration
        val startA = if (sameBinding) current.pointAMillis else null
        if (startA == null) {
            return@withLock failureResult(
                AbRepeatFailure.NoRangeConfigured("Set point A before marking point B"),
            )
        }

        val posB = snapshot.positionMillis.coerceIn(0L, duration)
        val validation = validateBounds(startA, posB, duration)
        if (validation != null) {
            return@withLock failureResult(validation)
        }

        cancelActiveSeekAndBumpEpoch()
        session.updateActiveRepeatBounds(null)

        val next = StudyAbRepeatState(
            status = AbRepeatStatus.READY,
            isSupported = true,
            disabledReason = null,
            entryId = snapshot.entryId,
            mediaPath = snapshot.mediaPath,
            sessionGeneration = snapshot.sessionGeneration,
            knownDurationMillis = duration,
            pointAMillis = startA,
            pointBMillis = posB,
            activeSavedRangeId = null,
            loopCount = 0,
            lastError = null,
        )
        _state.value = next
        AbRepeatResult.Success(next)
    }

    /**
     * Sets explicit A/B bounds (`0 <= startMillis < endMillis <= knownDuration`,
     * interval >= 1000 ms) for the currently loaded media session.
     */
    suspend fun setRange(
        startMillis: Long,
        endMillis: Long,
        savedRangeId: String? = null,
    ): AbRepeatResult<StudyAbRepeatState> = actionMutex.withLock {
        val session = requireControl() ?: return@withLock failureResult(unsupportedFailure())
        val snapshot = requireReadySnapshot(session) ?: return@withLock failureResult(snapshotFailure(session))
        val duration = snapshot.knownDurationMillis
            ?: return@withLock disabledUnknownDuration()

        val validation = validateBounds(startMillis, endMillis, duration)
        if (validation != null) {
            return@withLock failureResult(validation)
        }

        cancelActiveSeekAndBumpEpoch()
        session.updateActiveRepeatBounds(null)

        val next = StudyAbRepeatState(
            status = AbRepeatStatus.READY,
            isSupported = true,
            disabledReason = null,
            entryId = snapshot.entryId,
            mediaPath = snapshot.mediaPath,
            sessionGeneration = snapshot.sessionGeneration,
            knownDurationMillis = duration,
            pointAMillis = startMillis,
            pointBMillis = endMillis,
            activeSavedRangeId = savedRangeId,
            loopCount = 0,
            lastError = null,
        )
        _state.value = next
        AbRepeatResult.Success(next)
    }

    /**
     * Explicitly loads a [SavedStudyRange] into the currently loaded matching
     * playback session. Saved ranges never auto-start on restart; repeat begins
     * only when explicitly triggered here.
     */
    suspend fun activateSavedRange(
        range: SavedStudyRange,
        startImmediately: Boolean = true,
    ): AbRepeatResult<StudyAbRepeatState> {
        val snapshot = playbackLauncher?.sessionControl?.captureSnapshot()
        if (snapshot != null && snapshot.entryId != null && snapshot.entryId != range.entryId) {
            return failureResult(
                AbRepeatFailure.EntryMismatch(
                    expectedEntryId = range.entryId,
                    actualEntryId = snapshot.entryId,
                ),
            )
        }
        val setResult = setRange(
            startMillis = range.startMillis,
            endMillis = range.endMillis,
            savedRangeId = range.id,
        )
        if (setResult !is AbRepeatResult.Success || !startImmediately) {
            return setResult
        }
        return start(resumeIfPaused = true)
    }

    /**
     * Starts active A/B looping on the validated range for the current session.
     */
    suspend fun start(resumeIfPaused: Boolean = true): AbRepeatResult<StudyAbRepeatState> = actionMutex.withLock {
        val session = requireControl() ?: return@withLock failureResult(unsupportedFailure())
        val snapshot = requireReadySnapshot(session) ?: return@withLock failureResult(snapshotFailure(session))
        val duration = snapshot.knownDurationMillis
            ?: return@withLock disabledUnknownDuration()

        val current = _state.value
        val sameBinding = current.entryId == snapshot.entryId &&
            current.mediaPath == snapshot.mediaPath &&
            current.sessionGeneration == snapshot.sessionGeneration
        val pointA = if (sameBinding) current.pointAMillis else null
        val pointB = if (sameBinding) current.pointBMillis else null
        if (pointA == null || pointB == null) {
            return@withLock failureResult(AbRepeatFailure.NoRangeConfigured())
        }

        val validation = validateBounds(pointA, pointB, duration)
        if (validation != null) {
            return@withLock failureResult(validation)
        }

        cancelActiveSeekAndBumpEpoch()

        val shouldPauseWait = snapshot.state == PlaybackSessionState.PAUSED && !resumeIfPaused
        val bounds = ActiveAbRepeatBounds(
            entryId = snapshot.entryId,
            mediaPath = snapshot.mediaPath,
            sessionGeneration = snapshot.sessionGeneration,
            startMillis = pointA,
            endMillis = pointB,
        )
        session.updateActiveRepeatBounds(bounds)

        val needsInitialSeek = snapshot.positionMillis < pointA ||
            snapshot.positionMillis >= pointB ||
            snapshot.state == PlaybackSessionState.COMPLETED

        if (needsInitialSeek && !shouldPauseWait) {
            if (!session.seekTo(pointA)) {
                session.updateActiveRepeatBounds(null)
                return@withLock failureResult(AbRepeatFailure.SeekFailed())
            }
        }
        if (resumeIfPaused && (snapshot.state == PlaybackSessionState.PAUSED || snapshot.state == PlaybackSessionState.COMPLETED)) {
            session.play()
        }

        val next = current.copy(
            status = if (shouldPauseWait) AbRepeatStatus.PAUSED else AbRepeatStatus.ACTIVE,
            isSupported = true,
            disabledReason = null,
            knownDurationMillis = duration,
            lastError = null,
        )
        _state.value = next
        AbRepeatResult.Success(next)
    }

    /**
     * Stops active A/B repetition cleanly without triggering background playback.
     * When [clearBounds] is true, also discards A/B bounds and loop counters.
     */
    suspend fun stop(clearBounds: Boolean = false) = actionMutex.withLock {
        cancelActiveSeekAndBumpEpoch()
        playbackLauncher?.sessionControl?.updateActiveRepeatBounds(null)
        val current = _state.value
        _state.value = if (clearBounds) {
            StudyAbRepeatState(
                status = if (current.isSupported) AbRepeatStatus.INACTIVE else AbRepeatStatus.UNSUPPORTED,
                isSupported = current.isSupported,
                disabledReason = current.disabledReason,
            )
        } else {
            val nextStatus = when {
                !current.isSupported -> AbRepeatStatus.UNSUPPORTED
                current.hasValidRange -> AbRepeatStatus.READY
                else -> AbRepeatStatus.INACTIVE
            }
            current.copy(status = nextStatus, lastError = null)
        }
    }

    /**
     * Clears any active or configured A/B bounds completely.
     */
    suspend fun clear() {
        stop(clearBounds = true)
    }

    /**
     * Evaluates a session snapshot synchronously (called by host listeners and
     * deterministic tests). Handles clearing on item switch / stop / close / error,
     * pause preservation, and coalesced seek-back when position crosses B.
     */
    fun onSnapshotObserved(snapshot: PlaybackPositionSnapshot?) {
        if (tracking.value.closed) return
        val session = playbackLauncher?.sessionControl
        val current = _state.value

        if (snapshot == null || snapshot.state == PlaybackSessionState.IDLE) {
            if (current.isLooping || current.pointAMillis != null || current.pointBMillis != null) {
                resetToStoppedState(session)
            } else {
                syncCapabilitySupport(null, session?.capabilities)
            }
            return
        }

        if (snapshot.state == PlaybackSessionState.ERROR) {
            cancelActiveSeekAndBumpEpoch()
            session?.updateActiveRepeatBounds(null)
            _state.value = StudyAbRepeatState(
                status = AbRepeatStatus.INACTIVE,
                isSupported = true,
                disabledReason = null,
                lastError = AbRepeatFailure.PlayerError(),
            )
            return
        }

        if (!snapshot.capabilities.isInternalPlayer || !snapshot.capabilities.canSeek) {
            cancelActiveSeekAndBumpEpoch()
            session?.updateActiveRepeatBounds(null)
            val reason = snapshot.capabilities.limitationDescription
                ?: "Active player does not support A/B repetition"
            _state.value = StudyAbRepeatState(
                status = AbRepeatStatus.UNSUPPORTED,
                isSupported = false,
                disabledReason = reason,
                lastError = AbRepeatFailure.CapabilityUnsupported(reason),
            )
            return
        }

        if (!current.isLooping && current.pointAMillis == null && current.pointBMillis == null) {
            syncCapabilitySupport(snapshot, snapshot.capabilities)
            return
        }

        val bindingMismatch = current.sessionGeneration != snapshot.sessionGeneration ||
            current.entryId != snapshot.entryId ||
            current.mediaPath != snapshot.mediaPath
        if (bindingMismatch) {
            cancelActiveSeekAndBumpEpoch()
            session?.updateActiveRepeatBounds(null)
            syncCapabilitySupport(snapshot, snapshot.capabilities)
            return
        }

        val liveDuration = snapshot.knownDurationMillis ?: current.knownDurationMillis
        if (!current.isLooping) {
            if (liveDuration != null && current.pointBMillis != null && current.pointBMillis > liveDuration) {
                resetToStoppedState(
                    session = session,
                    error = AbRepeatFailure.InvalidRange(
                        message = "Configured B bound (${current.pointBMillis}ms) exceeds media duration (${liveDuration}ms)",
                    ),
                )
            } else if (liveDuration != current.knownDurationMillis) {
                _state.value = current.copy(knownDurationMillis = liveDuration)
            }
            return
        }

        val pointA = current.pointAMillis ?: return
        val pointB = current.pointBMillis ?: return
        if (liveDuration != null && pointB > liveDuration) {
            resetToStoppedState(
                session = session,
                error = AbRepeatFailure.InvalidRange(
                    message = "Configured B bound (${pointB}ms) exceeds media duration (${liveDuration}ms)",
                ),
            )
            return
        }

        if (snapshot.state == PlaybackSessionState.PAUSED) {
            val trackNow = tracking.value
            if (current.status == AbRepeatStatus.ACTIVE && !(trackNow.seekInFlight && trackNow.recoveringFromCompleted)) {
                _state.value = current.copy(status = AbRepeatStatus.PAUSED, knownDurationMillis = liveDuration)
            }
            return
        }

        if (snapshot.state == PlaybackSessionState.PREPARING) {
            return
        }

        val resumedState = if (current.status == AbRepeatStatus.PAUSED && snapshot.state == PlaybackSessionState.PLAYING) {
            current.copy(status = AbRepeatStatus.ACTIVE, knownDurationMillis = liveDuration).also {
                _state.value = it
            }
        } else if (liveDuration != current.knownDurationMillis) {
            current.copy(knownDurationMillis = liveDuration).also {
                _state.value = it
            }
        } else {
            current
        }

        if (resumedState.status != AbRepeatStatus.ACTIVE) return

        // Clear the settling latch as soon as the player publishes a pre-B position during active playback.
        val snapState = tracking.value
        if (snapState.awaitingReturnBelowB &&
            snapshot.state == PlaybackSessionState.PLAYING &&
            snapshot.positionMillis < pointB
        ) {
            tracking.update { curTrack ->
                curTrack.copy(
                    awaitingReturnBelowB = false,
                    consecutiveUnsettledSeeks = 0,
                )
            }
        }

        val hitEndOrBoundary = snapshot.state == PlaybackSessionState.COMPLETED ||
            snapshot.positionMillis >= pointB
        if (!hitEndOrBoundary) return

        val now = clockMillis()
        var abortedUnsettled = false
        var claimedEpoch = -1L
        var seekAttemptNumber = 0

        while (true) {
            val curTrack = tracking.value
            if (curTrack.closed || curTrack.seekInFlight) return
            if (curTrack.awaitingReturnBelowB) {
                val elapsed = now - curTrack.lastSeekTimestampMillis
                if (elapsed < seekCooldownMillis) return
                if (curTrack.consecutiveUnsettledSeeks >= maxConsecutiveUnsettledSeeks) {
                    abortedUnsettled = true
                    break
                }
            }
            val nextAttempt = curTrack.consecutiveUnsettledSeeks + 1
            val hitCompleted = snapshot.state == PlaybackSessionState.COMPLETED
            val updated = curTrack.copy(
                seekInFlight = true,
                recoveringFromCompleted = hitCompleted,
                awaitingReturnBelowB = true,
                lastSeekTimestampMillis = now,
                consecutiveUnsettledSeeks = nextAttempt,
            )
            if (tracking.compareAndSet(curTrack, updated)) {
                claimedEpoch = updated.epoch
                seekAttemptNumber = nextAttempt
                break
            }
        }

        if (abortedUnsettled) {
            resetToStoppedState(
                session = session,
                error = AbRepeatFailure.SeekFailed(
                    "Stopped A/B repeat because player did not seek back below ${pointB}ms",
                ),
            )
            return
        }

        if (claimedEpoch < 0L) return

        dispatchCoalescedSeek(
            session = session ?: return,
            claimedEpoch = claimedEpoch,
            expectedEntryId = resumedState.entryId,
            expectedMediaPath = resumedState.mediaPath ?: return,
            expectedGeneration = resumedState.sessionGeneration,
            pointA = pointA,
            pointB = pointB,
            wasCompleted = snapshot.state == PlaybackSessionState.COMPLETED,
            isInitialBoundarySeek = seekAttemptNumber == 1,
        )
    }

    fun close() {
        tracking.update { it.copy(closed = true, epoch = it.epoch + 1L, seekInFlight = false) }
        unregisterListener?.invoke()
        unregisterListener = null
        seekJob?.cancel()
        seekJob = null
        playbackLauncher?.sessionControl?.updateActiveRepeatBounds(null)
        _state.value = StudyAbRepeatState(status = AbRepeatStatus.INACTIVE)
    }

    private fun dispatchCoalescedSeek(
        session: PlaybackSessionControl,
        claimedEpoch: Long,
        expectedEntryId: String?,
        expectedMediaPath: String,
        expectedGeneration: Long,
        pointA: Long,
        pointB: Long,
        wasCompleted: Boolean,
        isInitialBoundarySeek: Boolean,
    ) {
        seekJob = scope.launch {
            try {
                seekMutex.withLock {
                    if (!isStillValidForSeek(session, claimedEpoch, expectedEntryId, expectedMediaPath, expectedGeneration, allowPausedDuringRestart = wasCompleted)) {
                        return@withLock
                    }
                    val seekOk = runCatching { session.seekTo(pointA) }.getOrDefault(false)
                    if (!isStillValidForSeek(session, claimedEpoch, expectedEntryId, expectedMediaPath, expectedGeneration, allowPausedDuringRestart = wasCompleted)) {
                        return@withLock
                    }
                    val afterSnap = session.captureSnapshot()
                    if (afterSnap == null ||
                        afterSnap.state == PlaybackSessionState.IDLE ||
                        afterSnap.state == PlaybackSessionState.ERROR ||
                        afterSnap.sessionGeneration != expectedGeneration ||
                        afterSnap.entryId != expectedEntryId ||
                        afterSnap.mediaPath != expectedMediaPath
                    ) {
                        resetToStoppedState(
                            session = session,
                            error = if (afterSnap?.state == PlaybackSessionState.ERROR) {
                                AbRepeatFailure.PlayerError()
                            } else null,
                        )
                        return@withLock
                    }
                    if (!seekOk) {
                        val failure = if (!session.capabilities.canSeek || !session.capabilities.isInternalPlayer) {
                            AbRepeatFailure.CapabilityUnsupported(
                                session.capabilities.limitationDescription
                                    ?: "Active player lost seek support",
                            )
                        } else {
                            AbRepeatFailure.SeekFailed()
                        }
                        resetToStoppedState(session = session, error = failure)
                        return@withLock
                    }

                    if (wasCompleted || afterSnap.state == PlaybackSessionState.COMPLETED) {
                        val latest = _state.value
                        val latestSnap = session.captureSnapshot()
                        if (!tracking.value.closed &&
                            tracking.value.epoch == claimedEpoch &&
                            latest.status == AbRepeatStatus.ACTIVE &&
                            latestSnap != null &&
                            latestSnap.state != PlaybackSessionState.IDLE &&
                            latestSnap.state != PlaybackSessionState.ERROR &&
                            latestSnap.sessionGeneration == expectedGeneration &&
                            latestSnap.entryId == expectedEntryId &&
                            latestSnap.mediaPath == expectedMediaPath &&
                            (wasCompleted || latestSnap.state != PlaybackSessionState.PAUSED)
                        ) {
                            session.play()
                        }
                    }

                    val settledNow = afterSnap.positionMillis < pointB && afterSnap.state != PlaybackSessionState.COMPLETED
                    val finishedAt = clockMillis()
                    tracking.update { curTrack ->
                        if (curTrack.epoch != claimedEpoch) {
                            curTrack
                        } else {
                            curTrack.copy(
                                lastSeekTimestampMillis = finishedAt,
                                awaitingReturnBelowB = !settledNow,
                                consecutiveUnsettledSeeks = if (settledNow) 0 else curTrack.consecutiveUnsettledSeeks,
                            )
                        }
                    }

                    if (isInitialBoundarySeek && tracking.value.epoch == claimedEpoch) {
                        _state.update { cur ->
                            if (cur.status == AbRepeatStatus.ACTIVE &&
                                cur.sessionGeneration == expectedGeneration &&
                                cur.entryId == expectedEntryId &&
                                cur.mediaPath == expectedMediaPath
                            ) {
                                cur.copy(loopCount = cur.loopCount + 1, lastError = null)
                            } else {
                                cur
                            }
                        }
                    }
                }
            } finally {
                tracking.update { curTrack -> curTrack.copy(seekInFlight = false, recoveringFromCompleted = false) }
            }
        }
    }

    private fun isStillValidForSeek(
        session: PlaybackSessionControl,
        claimedEpoch: Long,
        expectedEntryId: String?,
        expectedMediaPath: String,
        expectedGeneration: Long,
        allowPausedDuringRestart: Boolean,
    ): Boolean {
        val curTrack = tracking.value
        if (curTrack.closed || curTrack.epoch != claimedEpoch) return false
        val cur = _state.value
        if (cur.status != AbRepeatStatus.ACTIVE) return false
        if (cur.sessionGeneration != expectedGeneration ||
            cur.entryId != expectedEntryId ||
            cur.mediaPath != expectedMediaPath
        ) {
            return false
        }
        val snap = session.captureSnapshot() ?: return false
        val validState = when (snap.state) {
            PlaybackSessionState.IDLE, PlaybackSessionState.ERROR -> false
            PlaybackSessionState.PAUSED -> allowPausedDuringRestart
            else -> true
        }
        return validState &&
            snap.sessionGeneration == expectedGeneration &&
            snap.entryId == expectedEntryId &&
            snap.mediaPath == expectedMediaPath
    }

    private fun syncCapabilitySupport(
        snapshot: PlaybackPositionSnapshot?,
        capabilities: PlaybackCapabilities?,
    ) {
        val caps = snapshot?.capabilities ?: capabilities
        if (caps != null && (!caps.isInternalPlayer || !caps.canSeek)) {
            _state.value = StudyAbRepeatState(
                status = AbRepeatStatus.UNSUPPORTED,
                isSupported = false,
                disabledReason = caps.limitationDescription
                    ?: "Active player does not support seeking for A/B repeat",
            )
            return
        }
        if (snapshot != null &&
            (snapshot.state == PlaybackSessionState.PLAYING ||
                snapshot.state == PlaybackSessionState.PAUSED ||
                snapshot.state == PlaybackSessionState.COMPLETED) &&
            snapshot.knownDurationMillis == null
        ) {
            _state.value = StudyAbRepeatState(
                status = AbRepeatStatus.UNSUPPORTED,
                isSupported = false,
                disabledReason = "A/B repeat is disabled because media duration is unknown",
                entryId = snapshot.entryId,
                mediaPath = snapshot.mediaPath,
                sessionGeneration = snapshot.sessionGeneration,
            )
            return
        }
        val prev = _state.value
        val sameSession = snapshot != null &&
            prev.sessionGeneration == snapshot.sessionGeneration &&
            prev.entryId == snapshot.entryId &&
            prev.mediaPath == snapshot.mediaPath
        val preservedError = if (sameSession) prev.lastError else null

        _state.value = StudyAbRepeatState(
            status = AbRepeatStatus.INACTIVE,
            isSupported = true,
            disabledReason = null,
            entryId = snapshot?.entryId,
            mediaPath = snapshot?.mediaPath,
            sessionGeneration = snapshot?.sessionGeneration ?: 0L,
            knownDurationMillis = snapshot?.knownDurationMillis,
            lastError = preservedError,
        )
    }

    private fun resetToStoppedState(
        session: PlaybackSessionControl?,
        error: AbRepeatFailure? = null,
    ) {
        cancelActiveSeekAndBumpEpoch()
        session?.updateActiveRepeatBounds(null)
        val caps = session?.capabilities
        val supported = caps == null || (caps.isInternalPlayer && caps.canSeek)
        val snap = session?.captureSnapshot()
        _state.value = StudyAbRepeatState(
            status = if (supported) AbRepeatStatus.INACTIVE else AbRepeatStatus.UNSUPPORTED,
            isSupported = supported,
            disabledReason = if (supported) null else (caps.limitationDescription ?: "Unsupported playback session"),
            entryId = snap?.entryId,
            mediaPath = snap?.mediaPath,
            sessionGeneration = snap?.sessionGeneration ?: 0L,
            knownDurationMillis = snap?.knownDurationMillis,
            lastError = error,
        )
    }

    private fun cancelActiveSeekAndBumpEpoch() {
        tracking.update { cur ->
            cur.copy(
                epoch = cur.epoch + 1L,
                seekInFlight = false,
                recoveringFromCompleted = false,
                awaitingReturnBelowB = false,
                consecutiveUnsettledSeeks = 0,
            )
        }
        seekJob?.cancel()
        seekJob = null
    }

    private fun requireControl(): PlaybackSessionControl? {
        val launcher = playbackLauncher ?: return null
        if (!launcher.canPlay) return null
        val session = launcher.sessionControl ?: return null
        if (!session.capabilities.isInternalPlayer || !session.capabilities.canSeek) return null
        return session
    }

    private fun unsupportedFailure(): AbRepeatFailure {
        val caps = playbackLauncher?.sessionControl?.capabilities
        val reason = caps?.limitationDescription
            ?: "Active player does not support in-app seeking for A/B repeat"
        _state.value = _state.value.copy(
            status = AbRepeatStatus.UNSUPPORTED,
            isSupported = false,
            disabledReason = reason,
            lastError = AbRepeatFailure.CapabilityUnsupported(reason),
        )
        return AbRepeatFailure.CapabilityUnsupported(reason)
    }

    private fun requireReadySnapshot(session: PlaybackSessionControl): PlaybackPositionSnapshot? {
        val snap = session.captureSnapshot() ?: return null
        if (!snap.capabilities.isInternalPlayer || !snap.capabilities.canSeek) return null
        return when (snap.state) {
            PlaybackSessionState.PLAYING,
            PlaybackSessionState.PAUSED,
            PlaybackSessionState.COMPLETED -> snap
            else -> null
        }
    }

    private fun snapshotFailure(session: PlaybackSessionControl): AbRepeatFailure {
        val snap = session.captureSnapshot()
            ?: return AbRepeatFailure.PlayerStopped()
        if (!snap.capabilities.isInternalPlayer || !snap.capabilities.canSeek) {
            return unsupportedFailure()
        }
        return when (snap.state) {
            PlaybackSessionState.IDLE -> AbRepeatFailure.PlayerStopped()
            PlaybackSessionState.PREPARING -> AbRepeatFailure.PlayerNotReady()
            PlaybackSessionState.ERROR -> AbRepeatFailure.PlayerError()
            else -> AbRepeatFailure.PlayerStopped()
        }
    }

    private fun disabledUnknownDuration(): AbRepeatResult<StudyAbRepeatState> {
        val failure = AbRepeatFailure.UnknownDuration()
        _state.value = _state.value.copy(
            status = AbRepeatStatus.UNSUPPORTED,
            isSupported = false,
            disabledReason = failure.message,
            lastError = failure,
        )
        return failureResult(failure)
    }

    private fun validateBounds(
        startMillis: Long,
        endMillis: Long,
        knownDurationMillis: Long,
    ): AbRepeatFailure.InvalidRange? {
        val validation = StudyValidator.validateSavedRange(
            entryId = "ab-session",
            startMillis = startMillis,
            endMillis = endMillis,
            label = null,
            knownDurationMillis = knownDurationMillis,
        ) ?: return null
        return AbRepeatFailure.InvalidRange(
            validationError = validation.error,
            message = validation.message,
        )
    }

    private fun failureResult(failure: AbRepeatFailure): AbRepeatResult.Failure {
        _state.value = _state.value.copy(lastError = failure)
        return AbRepeatResult.Failure(failure)
    }

    companion object {
        const val DEFAULT_SEEK_COOLDOWN_MILLIS = 1_200L
        const val DEFAULT_MAX_CONSECUTIVE_UNSETTLED_SEEKS = 3

        private fun defaultMonotonicClock(): () -> Long {
            val mark = TimeSource.Monotonic.markNow()
            return { mark.elapsedNow().inWholeMilliseconds }
        }
    }
}
