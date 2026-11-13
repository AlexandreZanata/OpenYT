package com.openyt.core.application

import com.openyt.core.domain.IndexReconcileSummary
import com.openyt.core.domain.SubtitleFormat
import com.openyt.core.domain.SubtitleIndexState
import com.openyt.core.domain.SubtitleParseException
import com.openyt.core.domain.SubtitleTrackIndexRecord
import com.openyt.core.domain.TimedCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubtitleIndexRebuilderTest {

    private class FakeSubtitleContentPort : SubtitleContentPort {
        val files = mutableMapOf<String, String>()
        val fileMetadata = mutableMapOf<String, FileMetadata>()
        val digests = mutableMapOf<String, String>()

        fun putTrack(path: String, content: String, sizeBytes: Long = content.length.toLong(), digest: String = "sha-$path") {
            files[path] = content
            fileMetadata[path] = FileMetadata(sizeBytes = sizeBytes, lastModifiedEpochMillis = 1000L)
            digests[path] = digest
        }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun inspect(path: String): FileMetadata? = fileMetadata[path]

        override suspend fun readContent(path: String, maxBytes: Long): String? {
            val content = files[path] ?: return null
            if (content.length > maxBytes) return null
            return content
        }

        override suspend fun computeDigest(path: String): String? = digests[path]
    }

    private class InMemorySubtitleIndexRepository : SubtitleIndexRepository {
        val tracks = mutableMapOf<String, SubtitleTrackIndexRecord>()
        val cues = mutableListOf<TimedCue>()
        val ftsCues = mutableListOf<TimedCue>()
        var onPersistBatch: (suspend (List<TimedCue>) -> Unit)? = null

        override suspend fun getTrackIndex(trackId: String): SubtitleTrackIndexRecord? = tracks[trackId]

        override suspend fun loadTrackIndexesForEntry(entryId: String): List<SubtitleTrackIndexRecord> =
            tracks.values.filter { it.entryId == entryId }

        override suspend fun loadAllTrackIndexes(): List<SubtitleTrackIndexRecord> =
            tracks.values.toList()

        override suspend fun saveTrackIndex(record: SubtitleTrackIndexRecord) {
            tracks[record.trackId] = record
        }

        override suspend fun updateIndexState(trackId: String, state: SubtitleIndexState, errorMessage: String?) {
            val current = tracks[trackId] ?: return
            tracks[trackId] = current.copy(state = state, errorMessage = errorMessage)
        }

        override suspend fun beginRevision(
            trackId: String,
            entryId: String,
            language: String?,
            mediaRevisionToken: String,
            trackRevision: String,
        ): Int {
            val current = tracks[trackId]
            val activeRev = current?.activeRevision ?: 0
            val nextRev = activeRev + 1

            // Purge any stale cues for nextRev
            cues.removeAll { it.trackId == trackId && it.originalIndex == nextRev }
            ftsCues.removeAll { it.trackId == trackId && it.originalIndex == nextRev }

            tracks[trackId] = SubtitleTrackIndexRecord(
                trackId = trackId,
                entryId = entryId,
                language = language ?: current?.language,
                mediaRevisionToken = mediaRevisionToken,
                trackRevision = trackRevision,
                activeRevision = activeRev, // retain usable old revision!
                state = SubtitleIndexState.INDEXING,
                totalCues = current?.totalCues ?: 0,
            )
            return nextRev
        }

        override suspend fun persistCues(cues: List<TimedCue>, trackId: String, entryId: String, revision: Int) {
            onPersistBatch?.invoke(cues)
            for (c in cues) {
                // Encode revision into originalIndex for this test double
                val tagged = c.copy(trackId = trackId, entryId = entryId, originalIndex = revision)
                this.cues.add(tagged)
                this.ftsCues.add(tagged)
            }
        }

        override suspend fun commitRevision(trackId: String, revision: Int, totalCues: Int, timebaseOffsetMs: Long) {
            val current = tracks[trackId] ?: return
            tracks[trackId] = current.copy(
                activeRevision = revision,
                state = SubtitleIndexState.READY,
                totalCues = totalCues,
                timebaseOffsetMs = timebaseOffsetMs,
                errorMessage = null,
            )
            // Clean older revisions
            cues.removeAll { it.trackId == trackId && it.originalIndex != revision }
            ftsCues.removeAll { it.trackId == trackId && it.originalIndex != revision }
        }

        override suspend fun abortRevision(trackId: String, revision: Int, failureMessage: String?) {
            // Delete revision staging rows
            cues.removeAll { it.trackId == trackId && it.originalIndex == revision }
            ftsCues.removeAll { it.trackId == trackId && it.originalIndex == revision }

            val current = tracks[trackId] ?: return
            val newState = if (current.activeRevision > 0) {
                if (failureMessage != null) SubtitleIndexState.STALE else SubtitleIndexState.READY
            } else {
                if (failureMessage != null) SubtitleIndexState.FAILED else SubtitleIndexState.PENDING
            }
            tracks[trackId] = current.copy(state = newState, errorMessage = failureMessage)
        }

        override suspend fun deleteTrackIndex(trackId: String) {
            tracks.remove(trackId)
            cues.removeAll { it.trackId == trackId }
            ftsCues.removeAll { it.trackId == trackId }
        }

        override suspend fun deleteTrackIndexesForEntry(entryId: String) {
            val toRemove = tracks.values.filter { it.entryId == entryId }.map { it.trackId }.toSet()
            for (tid in toRemove) {
                tracks.remove(tid)
            }
            cues.removeAll { it.entryId == entryId }
            ftsCues.removeAll { it.entryId == entryId }
        }

        override suspend fun invalidateEntry(entryId: String, newState: SubtitleIndexState) {
            for ((tid, r) in tracks.entries.toList()) {
                if (r.entryId == entryId) {
                    tracks[tid] = r.copy(state = newState)
                }
            }
        }

        override suspend fun loadActiveCues(trackId: String): List<TimedCue> {
            val current = tracks[trackId] ?: return emptyList()
            if (current.activeRevision <= 0) return emptyList()
            return cues.filter { it.trackId == trackId && it.originalIndex == current.activeRevision }
        }

        override suspend fun countCuesAndFts(trackId: String, revision: Int): Pair<Int, Int> {
            val c = cues.count { it.trackId == trackId && it.originalIndex == revision }
            val f = ftsCues.count { it.trackId == trackId && it.originalIndex == revision }
            return Pair(c, f)
        }

        override suspend fun cleanAbandonedRevisions(): IndexReconcileSummary {
            var interrupted = 0
            var cleanedCues = 0

            for ((tid, r) in tracks.entries.toList()) {
                if (r.state == SubtitleIndexState.INDEXING) {
                    val nextState = if (r.activeRevision > 0) SubtitleIndexState.STALE else SubtitleIndexState.FAILED
                    tracks[tid] = r.copy(state = nextState)
                    interrupted++
                }
                val orphan = cues.filter { it.trackId == tid && it.originalIndex != r.activeRevision }
                cleanedCues += orphan.size
                cues.removeAll(orphan)
                ftsCues.removeAll(orphan)
            }
            return IndexReconcileSummary(
                reconciledTracks = tracks.size,
                cleanedOrphanedCues = cleanedCues,
                cleanedOrphanedFtsRows = cleanedCues,
                interruptedJobsRecovered = interrupted,
            )
        }
    }

    private val sampleSrt = """
        1
        00:00:01,000 --> 00:00:04,000
        Olá mundo! Primeira legenda.

        2
        00:00:05,000 --> 00:00:08,000
        Segunda legenda com acentuação e café.
    """.trimIndent()

    @Test
    fun buildsInitialIndexRevisionAtomicallyAndPersistsCues() = runBlocking {
        val repo = InMemorySubtitleIndexRepository()
        val contentPort = FakeSubtitleContentPort().apply {
            putTrack("/path/sub.srt", sampleSrt)
        }
        val rebuilder = SubtitleIndexRebuilder(repo, contentPort)

        val result = rebuilder.reindexTrack(
            trackId = "track-1",
            entryId = "entry-1",
            subtitlePath = "/path/sub.srt",
            language = "pt",
            mediaRevisionToken = "media-rev-1",
        )

        assertTrue(result.isSuccess)
        val record = result.getOrThrow()
        assertEquals("track-1", record.trackId)
        assertEquals("entry-1", record.entryId)
        assertEquals(1, record.activeRevision)
        assertEquals(SubtitleIndexState.READY, record.state)
        assertEquals(2, record.totalCues)
        assertTrue(record.isReady)

        val activeCues = repo.loadActiveCues("track-1")
        assertEquals(2, activeCues.size)
        assertEquals("Olá mundo! Primeira legenda.", activeCues[0].plainText)
        assertEquals("Segunda legenda com acentuação e café.", activeCues[1].plainText)
    }

    @Test
    fun retainsUsableOldRevisionWhileBuildingNewRevision() = runBlocking {
        val repo = InMemorySubtitleIndexRepository()
        val contentPort = FakeSubtitleContentPort().apply {
            putTrack("/path/sub.srt", sampleSrt)
        }
        val rebuilder = SubtitleIndexRebuilder(repo, contentPort)

        // Initial build rev 1
        rebuilder.reindexTrack("track-1", "entry-1", "/path/sub.srt", "pt", "media-rev-1")
        val rev1 = repo.getTrackIndex("track-1")!!
        assertEquals(1, rev1.activeRevision)

        // Updated subtitle track with 3 cues
        val updatedSrt = sampleSrt + "\n\n3\n00:00:10,000 --> 00:00:12,000\nTerceira legenda."
        contentPort.putTrack("/path/sub.srt", updatedSrt, digest = "sha-v2")

        // Intercept during batch persistence to check revision state
        repo.onPersistBatch = {
            val midTrack = repo.getTrackIndex("track-1")!!
            assertEquals(1, midTrack.activeRevision, "Old revision 1 must remain active while rev 2 is staging")
            assertEquals(SubtitleIndexState.INDEXING, midTrack.state)
            val cuesWhileStaging = repo.loadActiveCues("track-1")
            assertEquals(2, cuesWhileStaging.size, "Active cues must only reflect rev 1 while rev 2 is staging")
        }

        val result = rebuilder.reindexTrack("track-1", "entry-1", "/path/sub.srt", "pt", "media-rev-1")
        assertTrue(result.isSuccess)
        val record = result.getOrThrow()
        assertEquals(2, record.activeRevision)
        assertEquals(3, record.totalCues)
        assertEquals(SubtitleIndexState.READY, record.state)

        val committedCues = repo.loadActiveCues("track-1")
        assertEquals(3, committedCues.size)
    }

    @Test
    fun duplicateRetryForIdenticalMediaAndTrackRevisionShortCircuits() = runBlocking {
        val repo = InMemorySubtitleIndexRepository()
        val contentPort = FakeSubtitleContentPort().apply {
            putTrack("/path/sub.srt", sampleSrt)
        }
        val rebuilder = SubtitleIndexRebuilder(repo, contentPort)

        // First index
        val res1 = rebuilder.reindexTrack("track-1", "entry-1", "/path/sub.srt", "pt", "media-rev-1").getOrThrow()

        // Immediate retry with same content and media token
        val res2 = rebuilder.reindexTrack("track-1", "entry-1", "/path/sub.srt", "pt", "media-rev-1").getOrThrow()

        assertEquals(res1.activeRevision, res2.activeRevision)
        assertEquals(1, res2.activeRevision)
        assertEquals(2, repo.cues.size, "Duplicate retry should not add duplicate cue rows")
    }

    @Test
    fun cancellationLeavesOldRevisionIntactAndCleansPartialRows() = runBlocking {
        val repo = InMemorySubtitleIndexRepository()
        val contentPort = FakeSubtitleContentPort().apply {
            putTrack("/path/sub.srt", sampleSrt)
        }
        val rebuilder = SubtitleIndexRebuilder(repo, contentPort, batchSize = 1)

        // First index
        rebuilder.reindexTrack("track-1", "entry-1", "/path/sub.srt", "pt", "media-rev-1")
        assertEquals(1, repo.getTrackIndex("track-1")!!.activeRevision)
        assertEquals(2, repo.loadActiveCues("track-1").size)

        // Update track
        contentPort.putTrack("/path/sub.srt", sampleSrt + "\n\n3\n00:00:10,000 --> 00:00:12,000\nExtra.", digest = "sha-cancel")

        var batchCount = 0
        var job: Job? = null
        repo.onPersistBatch = {
            batchCount++
            if (batchCount == 1) {
                job?.cancel(CancellationException("Simulated job cancellation"))
            }
        }

        job = launch {
            rebuilder.reindexTrack("track-1", "entry-1", "/path/sub.srt", "pt", "media-rev-1")
        }
        job.join()

        // Verify old revision 1 is preserved and staging rows for rev 2 were purged
        val track = repo.getTrackIndex("track-1")!!
        assertEquals(1, track.activeRevision)
        val activeCues = repo.loadActiveCues("track-1")
        assertEquals(2, activeCues.size)
        // No cues with revision 2 should remain
        val rev2Cues = repo.cues.filter { it.originalIndex == 2 }
        assertEquals(0, rev2Cues.size, "All cues from aborted revision 2 must be purged")
    }

    @Test
    fun malformedSubtitleFormatReportsParseFailureAndCleansStaging() = runBlocking {
        val repo = InMemorySubtitleIndexRepository()
        val contentPort = FakeSubtitleContentPort().apply {
            putTrack("/path/bad.vtt", "This is not a valid WebVTT file without header")
        }
        val rebuilder = SubtitleIndexRebuilder(repo, contentPort)

        val result = rebuilder.reindexTrack(
            trackId = "track-bad",
            entryId = "entry-1",
            subtitlePath = "/path/bad.vtt",
            language = "en",
            mediaRevisionToken = "media-rev-1",
            formatHint = SubtitleFormat.VTT,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubtitleParseException)
        val track = repo.getTrackIndex("track-bad")!!
        assertEquals(SubtitleIndexState.FAILED, track.state)
        assertEquals(0, repo.cues.size)
    }

    @Test
    fun fileExceedingMaxSizeReportsFailure() = runBlocking {
        val repo = InMemorySubtitleIndexRepository()
        val contentPort = FakeSubtitleContentPort().apply {
            putTrack("/path/huge.srt", sampleSrt, sizeBytes = 15 * 1024 * 1024L)
        }
        val rebuilder = SubtitleIndexRebuilder(repo, contentPort, maxTrackSizeBytes = 10 * 1024 * 1024L)

        val result = rebuilder.reindexTrack("track-huge", "entry-1", "/path/huge.srt", "pt", "media-rev-1")
        assertTrue(result.isFailure)
        val track = repo.getTrackIndex("track-huge")!!
        assertEquals(SubtitleIndexState.FAILED, track.state)
        assertTrue(track.errorMessage?.contains("exceeds maximum allowed size") == true)
    }

    @Test
    fun missingFileFailsGracefully() = runBlocking {
        val repo = InMemorySubtitleIndexRepository()
        val contentPort = FakeSubtitleContentPort()
        val rebuilder = SubtitleIndexRebuilder(repo, contentPort)

        val result = rebuilder.reindexTrack("track-missing", "entry-1", "/path/absent.srt", "en", "media-rev-1")
        assertTrue(result.isFailure)
        val track = repo.getTrackIndex("track-missing")!!
        assertEquals(SubtitleIndexState.FAILED, track.state)
    }

    @Test
    fun mediaInvalidationAndRemovalOperateCorrectly() = runBlocking {
        val repo = InMemorySubtitleIndexRepository()
        val contentPort = FakeSubtitleContentPort().apply {
            putTrack("/path/sub.srt", sampleSrt)
        }
        val rebuilder = SubtitleIndexRebuilder(repo, contentPort)

        rebuilder.reindexTrack("track-1", "entry-1", "/path/sub.srt", "pt", "media-rev-1")
        assertEquals(SubtitleIndexState.READY, repo.getTrackIndex("track-1")!!.state)

        // Invalidate for media change
        rebuilder.invalidateForMediaChange("entry-1")
        assertEquals(SubtitleIndexState.STALE, repo.getTrackIndex("track-1")!!.state)
        // Usable old index remains queryable
        assertEquals(2, repo.loadActiveCues("track-1").size)

        // Reindex with new media revision token
        val res = rebuilder.reindexTrack("track-1", "entry-1", "/path/sub.srt", "pt", "media-rev-2").getOrThrow()
        assertEquals(SubtitleIndexState.READY, res.state)
        assertEquals("media-rev-2", res.mediaRevisionToken)

        // Remove track index
        rebuilder.removeTrackIndex("track-1")
        assertEquals(null, repo.getTrackIndex("track-1"))
        assertEquals(0, repo.cues.size)
    }
}
