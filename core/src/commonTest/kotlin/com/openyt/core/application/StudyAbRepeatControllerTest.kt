package com.openyt.core.application

import com.openyt.core.domain.AbRepeatFailure
import com.openyt.core.domain.AbRepeatResult
import com.openyt.core.domain.AbRepeatStatus
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyResult
import com.openyt.core.domain.StudyValidationError
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudyAbRepeatControllerTest {

    @Test
    fun savedRangesNeverAutoStartOnRestartUntilExplicitAction() = runBlocking<Unit> {
        val repo = MemoryStudyRepository()
        val entry = LibraryEntry(
            id = "entry-restart",
            title = "Restart Fixture",
            kind = MediaKind.VIDEO,
            filePath = "/media/restart.mp4",
            sizeBytes = 4_096L,
        )
        repo.putLibraryEntry(entry)
        val studyService = StudyService(repo, nowEpochMillis = { 1_000L }, nextId = { "rng-1" })
        val saved = (studyService.saveRange(entry.id, 2_000L, 6_000L, "Chorus", 30_000L)
            as StudyResult.Success<SavedStudyRange>).value

        // Simulate fresh restart controller attached to active player before any explicit action
        val session = FakeAbPlaybackSession(durationByPath = mapOf(entry.filePath to 30_000L))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = StudyAbRepeatController(session, scope)
        try {
            assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)
            assertTrue(session.openAtCalls.isEmpty())
            assertNull(session.activeRepeatBounds)

            // Even after user opens playback, persisted range must NOT auto-start repeat
            session.openAt(listOf(PlayableMedia(entry.filePath, entry.title, MediaKind.VIDEO, entry.id)), 0, 0L)
            assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)
            assertNull(controller.state.value.pointAMillis)
            assertNull(controller.state.value.pointBMillis)
            assertNull(session.activeRepeatBounds)

            // Explicit activation enables loop for that range
            val activated = controller.activateSavedRange(saved, startImmediately = true)
            assertTrue(activated is AbRepeatResult.Success)
            assertEquals(AbRepeatStatus.ACTIVE, activated.value.status)
            assertEquals(2_000L, activated.value.pointAMillis)
            assertEquals(6_000L, activated.value.pointBMillis)
            assertEquals(saved.id, activated.value.activeSavedRangeId)
            assertNotNull(session.activeRepeatBounds)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun rejectsUnknownDurationExternalFallbackAndInvalidRangesWithExplanations() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // 1. External fallback disabled with explanation
            val external = FakeAbPlaybackSession(
                capabilities = PlaybackCapabilities(
                    canSeek = false,
                    canMiniPlayer = false,
                    canPause = false,
                    isInternalPlayer = false,
                    limitationDescription = "External system player does not support seek",
                ),
            )
            external.openAt(listOf(PlayableMedia("/ext.mp4", "Ext", MediaKind.VIDEO, "ext-1")), 0)
            val extController = StudyAbRepeatController(external, scope)
            assertFalse(extController.state.value.isSupported)
            assertEquals(AbRepeatStatus.UNSUPPORTED, extController.state.value.status)
            assertTrue(extController.state.value.disabledReason!!.contains("External"))
            val extAttempt = extController.setRange(1_000L, 3_000L)
            assertTrue(extAttempt is AbRepeatResult.Failure)
            assertTrue(extAttempt.failure is AbRepeatFailure.CapabilityUnsupported)
            assertNull(external.activeRepeatBounds)
            extController.close()

            // 2. Unknown duration disabled cleanly
            val unknownDur = FakeAbPlaybackSession(durationByPath = mapOf("/nodur.mp4" to 0L))
            unknownDur.openAt(listOf(PlayableMedia("/nodur.mp4", "NoDur", MediaKind.VIDEO, "nd-1")), 0)
            val ndController = StudyAbRepeatController(unknownDur, scope)
            assertFalse(ndController.state.value.isSupported)
            assertEquals(AbRepeatStatus.UNSUPPORTED, ndController.state.value.status)
            val ndAttempt = ndController.markPointA()
            assertTrue(ndAttempt is AbRepeatResult.Failure)
            assertTrue(ndAttempt.failure is AbRepeatFailure.UnknownDuration)
            ndController.close()

            // 3. Invalid ranges (negative, inverted, < 1000ms, > duration, missing A)
            val normal = FakeAbPlaybackSession(durationByPath = mapOf("/v.mp4" to 20_000L))
            val normController = StudyAbRepeatController(normal, scope)
            // Stopped session rejected
            assertTrue((normController.markPointA() as AbRepeatResult.Failure).failure is AbRepeatFailure.PlayerStopped)

            normal.openAt(listOf(PlayableMedia("/v.mp4", "V", MediaKind.VIDEO, "v-1")), 0)
            val bWithoutA = normController.markPointB()
            assertTrue((bWithoutA as AbRepeatResult.Failure).failure is AbRepeatFailure.NoRangeConfigured)

            val negativeA = normController.setRange(-500L, 2_000L)
            assertEquals(
                StudyValidationError.NEGATIVE_TIMESTAMP,
                ((negativeA as AbRepeatResult.Failure).failure as AbRepeatFailure.InvalidRange).validationError,
            )

            val inverted = normController.setRange(5_000L, 3_000L)
            assertEquals(
                StudyValidationError.INVALID_TIME_RANGE,
                ((inverted as AbRepeatResult.Failure).failure as AbRepeatFailure.InvalidRange).validationError,
            )

            val tooShort = normController.setRange(2_000L, 2_900L)
            assertEquals(
                StudyValidationError.RANGE_TOO_SHORT,
                ((tooShort as AbRepeatResult.Failure).failure as AbRepeatFailure.InvalidRange).validationError,
            )

            val pastDuration = normController.setRange(2_000L, 25_000L)
            assertEquals(
                StudyValidationError.EXCEEDS_KNOWN_DURATION,
                ((pastDuration as AbRepeatResult.Failure).failure as AbRepeatFailure.InvalidRange).validationError,
            )
            normController.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun runsFiveLoopsPauseResumeAndCoalescesSlowSeekCallbacksWithoutStorm() = runBlocking<Unit> {
        var virtualClock = 1_000L
        val session = FakeAbPlaybackSession(
            durationByPath = mapOf("/study.mp4" to 30_000L),
            seekDelayMillis = 80L,
            lagPositionBeforeSettling = true,
        )
        session.openAt(listOf(PlayableMedia("/study.mp4", "Study", MediaKind.VIDEO, "s-1")), 0, 2_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = StudyAbRepeatController(
            playbackLauncher = session,
            scope = scope,
            clockMillis = { virtualClock },
            seekCooldownMillis = 1_000L,
        )

        try {
            session.advancePositionTo(2_000L)
            assertTrue(controller.markPointA() is AbRepeatResult.Success)
            session.advancePositionTo(4_000L)
            assertTrue(controller.markPointB() is AbRepeatResult.Success)

            // Explicitly start; since player is currently at B (4000), start seeks to A cleanly
            assertTrue(controller.start() is AbRepeatResult.Success)
            assertEquals(AbRepeatStatus.ACTIVE, controller.state.value.status)
            session.completePendingSeekImmediately()
            val initialSeekCount = session.seekCalls.size

            // Execute 5 full loops with overlapping slow callbacks + post-seek lag reports
            repeat(5) { loopIndex ->
                session.advancePositionTo(3_000L)
                session.advancePositionTo(4_080L) // Crossed B
                // Fire rapid redundant ticks while seekDelayMillis (80ms) is still running
                session.emitCurrentSnapshot()
                session.emitCurrentSnapshot()
                virtualClock += 30L
                session.emitCurrentSnapshot()

                // Wait for slow seek coroutine to finish while still lagging at 4_080 initially
                delay(140)
                // Stale/lagging callback while position is settling still must NOT fire duplicate seeks
                session.emitCurrentSnapshot()
                // Now player settles at A=2000ms
                session.completePendingSeekImmediately()
                virtualClock += 50L
                session.emitCurrentSnapshot()

                assertEquals(loopIndex + 1, controller.state.value.loopCount)
                assertEquals(2_000L, session.currentPositionMillis)
            }

            // Total boundary seeks during 5 loops MUST equal exactly 5 (zero seek storm!)
            assertEquals(initialSeekCount + 5, session.seekCalls.size)

            // Pause preserves bounds & never fights pause by calling play()
            val playCallsBeforePause = session.playCalls
            session.pause()
            assertEquals(AbRepeatStatus.PAUSED, controller.state.value.status)
            assertEquals(2_000L, controller.state.value.pointAMillis)
            assertEquals(4_000L, controller.state.value.pointBMillis)
            assertEquals(playCallsBeforePause, session.playCalls)

            // Resuming returns cleanly to ACTIVE
            session.play()
            assertEquals(AbRepeatStatus.ACTIVE, controller.state.value.status)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun abortsBoundedlyIfSeekBackendNeverMovesBelowB() = runBlocking<Unit> {
        var virtualClock = 0L
        val jammed = FakeAbPlaybackSession(
            durationByPath = mapOf("/jam.mp4" to 20_000L),
            ignoreSeekPositionUpdate = true,
        )
        jammed.openAt(listOf(PlayableMedia("/jam.mp4", "Jam", MediaKind.VIDEO, "j-1")), 0, 2_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = StudyAbRepeatController(
            playbackLauncher = jammed,
            scope = scope,
            clockMillis = { virtualClock },
            seekCooldownMillis = 200L,
            maxConsecutiveUnsettledSeeks = 3,
        )
        try {
            assertTrue(controller.setRange(2_000L, 4_000L) is AbRepeatResult.Success)
            assertTrue(controller.start() is AbRepeatResult.Success)
            // Move into range then overshoot B permanently
            jammed.forcePositionWithoutSeekCheck(4_200L)
            repeat(10) {
                virtualClock += 250L
                jammed.emitCurrentSnapshot()
                delay(25)
            }
            assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)
            assertTrue(controller.state.value.lastError is AbRepeatFailure.SeekFailed)
            assertNull(jammed.activeRepeatBounds)
            // Bounded seeks: 3 retry attempts, not unbounded infinite loop
            assertTrue(jammed.seekCalls.size <= 4)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun handlesEndOfMediaItemSwitchStopCloseAndPlayerErrorWithoutBackgroundPlayback() = runBlocking<Unit> {
        val item1 = PlayableMedia("/one.mp4", "One", MediaKind.VIDEO, "e-1")
        val item2 = PlayableMedia("/two.mp4", "Two", MediaKind.VIDEO, "e-2")
        val session = FakeAbPlaybackSession(
            durationByPath = mapOf(item1.path to 10_000L, item2.path to 10_000L),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = StudyAbRepeatController(session, scope)
        try {
            // 1. End of media loops cleanly back to A when repeat is active
            session.openAt(listOf(item1, item2), 0, 5_000L)
            assertTrue(controller.setRange(5_000L, 10_000L) is AbRepeatResult.Success)
            assertTrue(controller.start() is AbRepeatResult.Success)
            val playsBeforeEof = session.playCalls
            session.reachEndOfMedia()
            delay(80)
            assertEquals(1, controller.state.value.loopCount)
            assertEquals(5_000L, session.currentPositionMillis)
            assertEquals(PlaybackSessionState.PLAYING, session.state)
            assertEquals(playsBeforeEof + 1, session.playCalls)

            // 2. Item switch clears active repeat cleanly
            session.openAt(listOf(item1, item2), 1, 0L)
            assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)
            assertNull(controller.state.value.pointAMillis)
            assertNull(controller.state.value.pointBMillis)
            assertNull(session.activeRepeatBounds)

            // 3. Explicit stop clears active repeat AND triggers no background playback after stop
            assertTrue(controller.setRange(1_000L, 4_000L) is AbRepeatResult.Success)
            assertTrue(controller.start() is AbRepeatResult.Success)
            val playsBeforeStop = session.playCalls
            session.stop()
            delay(50)
            assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)
            assertNull(session.activeRepeatBounds)
            assertEquals(PlaybackSessionState.IDLE, session.state)
            assertEquals(playsBeforeStop, session.playCalls)

            // 4. Native error clears active repeat cleanly
            session.openAt(listOf(item1, item2), 0, 1_500L)
            assertTrue(controller.setRange(1_000L, 4_000L) is AbRepeatResult.Success)
            assertTrue(controller.start() is AbRepeatResult.Success)
            session.triggerError()
            assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)
            assertTrue(controller.state.value.lastError is AbRepeatFailure.PlayerError)
            assertNull(session.activeRepeatBounds)

            // 5. Close clears active repeat cleanly
            session.openAt(listOf(item1, item2), 0, 1_500L)
            assertTrue(controller.setRange(1_000L, 4_000L) is AbRepeatResult.Success)
            assertTrue(controller.start() is AbRepeatResult.Success)
            session.close()
            assertEquals(AbRepeatStatus.INACTIVE, controller.state.value.status)
            assertNull(session.activeRepeatBounds)
        } finally {
            controller.close()
            scope.cancel()
        }
    }
}

private class FakeAbPlaybackSession(
    override val capabilities: PlaybackCapabilities = PlaybackCapabilities(),
    private val durationByPath: Map<String, Long> = emptyMap(),
    private val seekDelayMillis: Long = 0L,
    private val lagPositionBeforeSettling: Boolean = false,
    private val ignoreSeekPositionUpdate: Boolean = false,
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

    var activeRepeatBounds: ActiveAbRepeatBounds? = null
        private set
    var playCalls: Int = 0
        private set
    val openAtCalls = mutableListOf<String?>()
    val seekCalls = CopyOnWriteArrayList<Long>()
    private var pendingTargetAfterLag: Long? = null
    private val listeners = CopyOnWriteArrayList<PlaybackSessionListener>()

    override fun registerSessionListener(listener: PlaybackSessionListener): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    override fun updateActiveRepeatBounds(bounds: ActiveAbRepeatBounds?) {
        activeRepeatBounds = bounds
    }

    override fun play(items: List<PlayableMedia>, startIndex: Int) {
        val item = items.getOrNull(startIndex) ?: return
        openSync(item, 0L)
    }

    override suspend fun openAt(
        items: List<PlayableMedia>,
        startIndex: Int,
        startPositionMillis: Long?,
    ): Boolean {
        val item = items.getOrNull(startIndex) ?: return false
        return openSync(item, startPositionMillis)
    }

    private fun openSync(item: PlayableMedia, startPositionMillis: Long?): Boolean {
        openAtCalls += item.entryId
        generation++
        loadedMedia = item
        durationMillis = durationByPath[item.path] ?: 60_000L
        currentPositionMillis = clampSeekPosition(startPositionMillis ?: 0L, durationMillis)
        state = PlaybackSessionState.PLAYING
        pendingTargetAfterLag = null
        emitCurrentSnapshot()
        return true
    }

    override suspend fun seekTo(positionMillis: Long): Boolean {
        if (!capabilities.canSeek || loadedMedia == null) return false
        seekCalls += positionMillis
        if (seekDelayMillis > 0L) {
            delay(seekDelayMillis)
        }
        if (ignoreSeekPositionUpdate) {
            return true
        }
        val clamped = clampSeekPosition(positionMillis, durationMillis)
        if (lagPositionBeforeSettling) {
            pendingTargetAfterLag = clamped
        } else {
            currentPositionMillis = clamped
            if (state == PlaybackSessionState.COMPLETED) {
                state = PlaybackSessionState.PAUSED
            }
            emitCurrentSnapshot()
        }
        return true
    }

    fun completePendingSeekImmediately() {
        val pending = pendingTargetAfterLag ?: return
        pendingTargetAfterLag = null
        currentPositionMillis = pending
        emitCurrentSnapshot()
    }

    override suspend fun pause() {
        if (state == PlaybackSessionState.PLAYING) {
            state = PlaybackSessionState.PAUSED
            emitCurrentSnapshot()
        }
    }

    override suspend fun play() {
        playCalls++
        if (loadedMedia != null && state != PlaybackSessionState.IDLE && state != PlaybackSessionState.ERROR) {
            state = PlaybackSessionState.PLAYING
            emitCurrentSnapshot()
        }
    }

    override suspend fun stop() {
        generation++
        loadedMedia = null
        state = PlaybackSessionState.IDLE
        currentPositionMillis = 0L
        durationMillis = 0L
        pendingTargetAfterLag = null
        emitCurrentSnapshot()
    }

    override fun close() {
        generation++
        loadedMedia = null
        state = PlaybackSessionState.IDLE
        currentPositionMillis = 0L
        durationMillis = 0L
        pendingTargetAfterLag = null
        emitCurrentSnapshot()
    }

    fun advancePositionTo(positionMillis: Long) {
        currentPositionMillis = clampSeekPosition(positionMillis, durationMillis)
        emitCurrentSnapshot()
    }

    fun forcePositionWithoutSeekCheck(positionMillis: Long) {
        currentPositionMillis = positionMillis
        emitCurrentSnapshot()
    }

    fun reachEndOfMedia() {
        currentPositionMillis = durationMillis
        state = PlaybackSessionState.COMPLETED
        emitCurrentSnapshot()
    }

    fun triggerError() {
        loadedMedia = null
        state = PlaybackSessionState.ERROR
        val errSnap = PlaybackPositionSnapshot(
            entryId = null,
            mediaPath = "",
            title = "",
            state = PlaybackSessionState.ERROR,
            capabilities = capabilities,
            positionMillis = 0L,
            durationMillis = 0L,
            sessionGeneration = generation,
        )
        listeners.forEach { it.onSessionSnapshot(errSnap) }
    }

    fun emitCurrentSnapshot() {
        val snap = captureSnapshot()
        listeners.forEach { it.onSessionSnapshot(snap) }
    }
}
