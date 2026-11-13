package com.openyt.core.application

import com.openyt.core.domain.MediaKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSessionControlTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun clampSeekPositionEnforcesBounds() {
        // Negative position clamped to 0
        assertEquals(0L, clampSeekPosition(-1000L, 60_000L))
        assertEquals(0L, clampSeekPosition(-1L, 0L))

        // Exact 0
        assertEquals(0L, clampSeekPosition(0L, 60_000L))

        // Within duration
        assertEquals(25_000L, clampSeekPosition(25_000L, 60_000L))

        // Exceeding duration clamped to duration
        assertEquals(60_000L, clampSeekPosition(75_000L, 60_000L))

        // Duration unknown (0 or negative) preserves non-negative requested position
        assertEquals(15_000L, clampSeekPosition(15_000L, 0L))
        assertEquals(12_000L, clampSeekPosition(12_000L, -1L))
    }

    @Test
    fun playbackCheckpointResumeAndEntryId() {
        val cpWithEntry = PlaybackCheckpoint(positionMillis = 15_000L, durationMillis = 60_000L, entryId = "entry-1")
        assertEquals(15_000L, cpWithEntry.resumeMillis)
        assertEquals("entry-1", cpWithEntry.entryId)

        // Near end (< 3s from duration) resumes at 0
        val nearEnd = PlaybackCheckpoint(positionMillis = 58_000L, durationMillis = 60_000L, entryId = "entry-1")
        assertEquals(0L, nearEnd.resumeMillis)

        // Negative resumes at 0
        val negative = PlaybackCheckpoint(positionMillis = -500L, durationMillis = 60_000L)
        assertEquals(0L, negative.resumeMillis)
        assertNull(negative.entryId)
    }

    @Test
    fun playableMediaBackwardCompatibleSerialization() {
        // Legacy JSON produced without entryId field
        val legacyJson = """{"path":"/video.mp4","title":"My Video","kind":"VIDEO"}"""
        val decoded = json.decodeFromString<PlayableMedia>(legacyJson)
        assertEquals("/video.mp4", decoded.path)
        assertEquals("My Video", decoded.title)
        assertEquals(MediaKind.VIDEO, decoded.kind)
        assertNull(decoded.entryId)

        // Modern JSON with entryId
        val modern = PlayableMedia("/audio.opus", "Podcast", MediaKind.AUDIO, entryId = "pod-42")
        val serialized = json.encodeToString(modern)
        val deserialized = json.decodeFromString<PlayableMedia>(serialized)
        assertEquals(modern, deserialized)
        assertEquals("pod-42", deserialized.entryId)
    }

    @Test
    fun playbackCapabilitiesDistinguishInternalFromExternalFallback() {
        val internalCaps = PlaybackCapabilities(
            canSeek = true,
            canMiniPlayer = true,
            canPause = true,
            isInternalPlayer = true,
            limitationDescription = null,
        )
        assertTrue(internalCaps.canSeek)
        assertTrue(internalCaps.isInternalPlayer)
        assertNull(internalCaps.limitationDescription)

        val externalFallbackCaps = PlaybackCapabilities(
            canSeek = false,
            canMiniPlayer = false,
            canPause = false,
            isInternalPlayer = false,
            limitationDescription = "External system player does not support in-app seek or session control",
        )
        assertFalse(externalFallbackCaps.canSeek)
        assertFalse(externalFallbackCaps.isInternalPlayer)
        assertNotNull(externalFallbackCaps.limitationDescription)
    }

    @Test
    fun requestedTimestampHonoredAfterAsynchronousPrepare() = runBlocking {
        val harness = FakePlaybackSessionHarness()
        val media = listOf(PlayableMedia("/path/test.mp4", "Test", MediaKind.VIDEO, "entry-1"))

        // Open at 25 seconds
        val opened = harness.openAt(media, 0, startPositionMillis = 25_000L)
        assertTrue(opened)
        assertEquals(PlaybackSessionState.PREPARING, harness.state)
        assertEquals(media[0], harness.loadedMedia)

        // Player engine completes asynchronous preparation with duration 60s
        harness.simulatePrepareComplete(durationMillis = 60_000L)

        // Seek to 25s was honored upon prepare completion!
        assertEquals(PlaybackSessionState.PLAYING, harness.state)
        assertEquals(25_000L, harness.currentPositionMillis)
        assertEquals(60_000L, harness.durationMillis)
    }

    @Test
    fun outOfRangeClampingDuringSeek() = runBlocking {
        val harness = FakePlaybackSessionHarness()
        val media = listOf(PlayableMedia("/path/test.mp4", "Test", MediaKind.VIDEO, "entry-1"))
        harness.openAt(media, 0, startPositionMillis = 0L)
        harness.simulatePrepareComplete(durationMillis = 50_000L)

        // Seek negative clamped to 0
        harness.seekTo(-5_000L)
        assertEquals(0L, harness.currentPositionMillis)

        // Seek past duration clamped to duration
        harness.seekTo(100_000L)
        assertEquals(50_000L, harness.currentPositionMillis)

        // Valid seek
        harness.seekTo(30_000L)
        assertEquals(30_000L, harness.currentPositionMillis)
    }

    @Test
    fun switchingEntryWhileSeekIsPendingDiscardsStaleSeek() = runBlocking {
        val harness = FakePlaybackSessionHarness()
        val item1 = PlayableMedia("/path/item1.mp4", "Item 1", MediaKind.VIDEO, "entry-1")
        val item2 = PlayableMedia("/path/item2.mp4", "Item 2", MediaKind.VIDEO, "entry-2")
        val list = listOf(item1, item2)

        // Open item 1 with seek to 45s, enters PREPARING
        harness.openAt(list, 0, startPositionMillis = 45_000L)
        assertEquals(PlaybackSessionState.PREPARING, harness.state)
        assertEquals(item1, harness.loadedMedia)
        val item1Generation = harness.generation

        // Before item 1 completes prepare, user switches to item 2 with seek to 10s
        harness.openAt(list, 1, startPositionMillis = 10_000L)
        assertEquals(PlaybackSessionState.PREPARING, harness.state)
        assertEquals(item2, harness.loadedMedia)

        // Stale prepare completion for item 1 arrives from old player instance
        harness.simulatePrepareCompleteForGeneration(item1Generation, durationMillis = 60_000L)

        // Stale callback was ignored: media remains item2 in PREPARING
        assertEquals(PlaybackSessionState.PREPARING, harness.state)
        assertEquals(item2, harness.loadedMedia)

        // Item 2 prepare completes
        harness.simulatePrepareComplete(durationMillis = 60_000L)
        assertEquals(PlaybackSessionState.PLAYING, harness.state)
        // Position must be item 2's seek (10s), NOT item 1's seek (45s)!
        assertEquals(10_000L, harness.currentPositionMillis)
    }

    @Test
    fun missingMediaFailsCleanly() = runBlocking {
        val harness = FakePlaybackSessionHarness(missingPaths = setOf("/path/missing.mp4"))
        val media = listOf(PlayableMedia("/path/missing.mp4", "Missing", MediaKind.VIDEO, "entry-missing"))

        val opened = harness.openAt(media, 0)
        assertFalse(opened)
        assertEquals(PlaybackSessionState.ERROR, harness.state)
        assertNull(harness.loadedMedia)
    }

    @Test
    fun externalFallbackExplainsLimitationAndRejectsSeek() = runBlocking {
        val harness = FakePlaybackSessionHarness(
            capabilities = PlaybackCapabilities(
                canSeek = false,
                canMiniPlayer = false,
                canPause = false,
                isInternalPlayer = false,
                limitationDescription = "External system player does not support in-app seek or session control",
            ),
        )
        val media = listOf(PlayableMedia("/path/song.mp3", "Song", MediaKind.AUDIO, "audio-1"))
        val opened = harness.openAt(media, 0)
        assertTrue(opened)

        assertFalse(harness.capabilities.canSeek)
        assertEquals("External system player does not support in-app seek or session control", harness.capabilities.limitationDescription)

        // Attempting to seek returns false and does not alter position
        val seekResult = harness.seekTo(15_000L)
        assertFalse(seekResult)
        assertEquals(0L, harness.currentPositionMillis)
    }

    @Test
    fun closeResetsSessionToIdle() = runBlocking {
        val harness = FakePlaybackSessionHarness()
        val media = listOf(PlayableMedia("/path/test.mp4", "Test", MediaKind.VIDEO, "entry-1"))
        harness.openAt(media, 0, startPositionMillis = 12_000L)
        harness.simulatePrepareComplete(durationMillis = 60_000L)
        assertEquals(PlaybackSessionState.PLAYING, harness.state)

        harness.close()
        assertEquals(PlaybackSessionState.IDLE, harness.state)
        assertNull(harness.loadedMedia)
        assertNull(harness.captureSnapshot())
        assertEquals(0L, harness.currentPositionMillis)
    }

    @Test
    fun captureSnapshotReturnsAtomicEntryTimingAndGeneration() = runBlocking {
        val harness = FakePlaybackSessionHarness()
        assertNull(harness.captureSnapshot())

        val item1 = PlayableMedia("/path/item1.mp4", "Item 1", MediaKind.VIDEO, "entry-1")
        harness.openAt(listOf(item1), 0, startPositionMillis = 14_250L)
        val preparingSnap = harness.captureSnapshot()
        assertNotNull(preparingSnap)
        assertEquals("entry-1", preparingSnap.entryId)
        assertEquals(PlaybackSessionState.PREPARING, preparingSnap.state)

        harness.simulatePrepareComplete(durationMillis = 60_000L)
        val playingSnap = harness.captureSnapshot()
        assertNotNull(playingSnap)
        assertEquals("entry-1", playingSnap.entryId)
        assertEquals(14_250L, playingSnap.positionMillis)
        assertEquals(60_000L, playingSnap.durationMillis)
        assertEquals(60_000L, playingSnap.knownDurationMillis)
        assertEquals(harness.currentSessionGeneration, playingSnap.sessionGeneration)

        harness.stop()
        assertNull(harness.captureSnapshot())
    }
}

/**
 * Deterministic test harness implementing [PlaybackSessionControl] logic
 * to verify contract invariants (asynchronous prepare, out-of-range clamping,
 * entry switching, stale callback rejection, and capabilities).
 */
private class FakePlaybackSessionHarness(
    override val capabilities: PlaybackCapabilities = PlaybackCapabilities(),
    private val missingPaths: Set<String> = emptySet(),
) : PlaybackSessionControl {

    override var loadedMedia: PlayableMedia? = null
        private set
    override var state: PlaybackSessionState = PlaybackSessionState.IDLE
        private set
    override var currentPositionMillis: Long = 0L
        private set
    override var durationMillis: Long = 0L
        private set
    override val currentSessionGeneration: Long
        get() = generation

    var generation: Long = 0L
        private set
    private var pendingSeek: Long? = null

    override suspend fun openAt(
        items: List<PlayableMedia>,
        startIndex: Int,
        startPositionMillis: Long?,
    ): Boolean {
        val item = items.getOrNull(startIndex) ?: return false
        if (item.path in missingPaths) {
            state = PlaybackSessionState.ERROR
            loadedMedia = null
            pendingSeek = null
            return false
        }
        generation++
        loadedMedia = item
        currentPositionMillis = 0L
        durationMillis = 0L
        pendingSeek = startPositionMillis?.let { clampSeekPosition(it, 0L) }

        if (!capabilities.canSeek && !capabilities.isInternalPlayer) {
            state = PlaybackSessionState.PLAYING
            return true
        }

        state = PlaybackSessionState.PREPARING
        return true
    }

    fun simulatePrepareComplete(durationMillis: Long) {
        simulatePrepareCompleteForGeneration(generation, durationMillis)
    }

    fun simulatePrepareCompleteForGeneration(gen: Long, durationMillis: Long) {
        if (gen != generation) return // Reject stale callback
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
        val clamped = clampSeekPosition(positionMillis, durationMillis)
        return if (state == PlaybackSessionState.PREPARING) {
            pendingSeek = clamped
            true
        } else {
            currentPositionMillis = clamped
            true
        }
    }

    override suspend fun pause() {
        if (state == PlaybackSessionState.PLAYING) state = PlaybackSessionState.PAUSED
    }

    override suspend fun play() {
        if (state == PlaybackSessionState.PAUSED) state = PlaybackSessionState.PLAYING
    }

    override suspend fun stop() {
        state = PlaybackSessionState.IDLE
        loadedMedia = null
        pendingSeek = null
        currentPositionMillis = 0L
    }

    override fun close() {
        state = PlaybackSessionState.IDLE
        loadedMedia = null
        pendingSeek = null
        currentPositionMillis = 0L
        durationMillis = 0L
        generation++
    }
}
