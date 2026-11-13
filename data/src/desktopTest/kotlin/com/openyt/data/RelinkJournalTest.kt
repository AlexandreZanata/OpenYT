package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.application.CandidateMatchConfidence
import com.openyt.core.application.PlaybackCapabilities
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PlaybackSessionControl
import com.openyt.core.application.PlaybackSessionState
import com.openyt.core.application.PlayableMedia
import com.openyt.core.application.PortableOperationStatus
import com.openyt.core.application.RankedRecoveryCandidate
import com.openyt.core.application.RelinkContentMode
import com.openyt.core.application.RelinkExecutor
import com.openyt.core.application.RelinkPlanner
import com.openyt.core.application.ScannedRecoveryCandidate
import com.openyt.core.application.StudyAnchorInspector
import com.openyt.core.application.SubtitleIndexRebuilder
import com.openyt.core.application.TimedSearchUseCase
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.Collection
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.TimedSearchResult
import com.openyt.data.db.OpenYtDatabase
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelinkJournalTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun identicalContentRelinkStopsActivePlaybackAndPreservesEntryStudyPlaybackAndFts() = runBlocking {
        val db = OpenYtDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OpenYtDatabase.Schema.create(it) })
        val downloads = SqlDelightDownloadRepository(db)
        val study = SqlDelightStudyRepository(db)
        val playback = SqlDelightPlaybackStore(db)
        val assets = SqlDelightOfflineAssetRepository(db)
        val subIndexRepo = SqlDelightSubtitleIndexRepository(db)

        val origBytes = "VIDEO-CONTENT-EXACT-BYTES".encodeToByteArray()
        val origSha = sha256(origBytes)
        val origRev = MediaRevision(origBytes.size.toLong(), 100L, origSha)
        val oldPath = File(tempFolder.root, "deleted/old.mp4").absolutePath

        downloads.insertLibraryEntry(
            LibraryEntry("e1", "Relink Lecture", MediaKind.VIDEO, oldPath, sizeBytes = origBytes.size.toLong(), mediaRevision = origRev, tags = listOf("tag1")),
        )
        downloads.insertCollection(Collection("col1", "Algo"))
        downloads.addEntryToCollection("col1", "e1")
        playback.savePosition(oldPath, PlaybackCheckpoint(15_000L, 90_000L, "e1"), "e1")
        study.upsertNote(StudyNote("n1", "e1", 5_000L, 8_000L, "Keep note fresh", origRev, 10L, 10L))
        study.saveEntryStudyState(EntryStudyState("e1", isStudied = true, studiedAtEpochMillis = 20L, updatedAtEpochMillis = 20L))

        val subFile = File(tempFolder.root, "sub.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:03,000\nPreserved subtitle cue\n")
        }
        SubtitleIndexRebuilder(subIndexRepo, com.openyt.data.search.JvmSubtitleContentAdapter())
            .reindexTrack("trk1", "e1", subFile.absolutePath, "en", origSha)

        val newFile = File(tempFolder.newFolder("recovered-local"), "renamed.mp4").apply {
            writeBytes(origBytes)
        }
        val cand = RankedRecoveryCandidate(
            candidate = ScannedRecoveryCandidate(newFile.absolutePath, newFile.name, newFile.length(), newFile.lastModified(), MediaKind.VIDEO, origSha),
            confidence = CandidateMatchConfidence.EXACT_CONTENT,
            verifiedSha256 = origSha,
            score = 1_000,
        )
        val plan = RelinkPlanner.buildPlan(
            entry = downloads.loadLibrary().single(),
            rankedCandidate = cand,
            targetIsScopedContentUri = false,
            existingStudyItemCount = 1,
            existingIndexedSubtitleCount = 1,
            planId = "rel-identical-1",
            nowEpochMillis = 500L,
        ).getOrThrow()

        val activePlayer = FakePlaybackControl(PlayableMedia("Relink Lecture", oldPath, MediaKind.VIDEO, entryId = "e1"))
        val journalRepo = SqlDelightPortableOperationRepository(db)
        val executor = RelinkExecutor(
            transferPort = JvmRelinkTransferPort(tempFolder.newFolder("stage")),
            publicationPort = SqlDelightRelinkPublicationPort(db),
            journalRepository = journalRepo,
            playbackSessionControl = activePlayer,
            storageReserveBytes = 0L,
            nowProvider = { 600L },
        )

        val receipt = executor.executeRelink(plan, plan.confirmationToken, confirmedContentChange = false, tempFolder.root.absolutePath)
            .getOrThrow()

        assertEquals(1, activePlayer.stopCalls)
        assertEquals(RelinkContentMode.IDENTICAL_CONTENT, receipt.contentMode)
        assertFalse(receipt.subtitleIndexesInvalidated)
        assertEquals(PortableOperationStatus.SUCCEEDED, journalRepo.getOperation("rel-identical-1")?.status)

        val updatedEntry = downloads.loadLibrary().single()
        assertEquals("e1", updatedEntry.id)
        assertEquals(newFile.absolutePath, updatedEntry.filePath)
        assertEquals(listOf("tag1"), updatedEntry.tags)
        assertEquals(setOf("e1"), downloads.loadCollectionMembership()["col1"])
        assertEquals(15_000L, playback.position(newFile.absolutePath, "e1")?.positionMillis)

        // Study anchors stay valid & FTS stays intact
        val bundle = study.loadEntryStudyBundle("e1")
        assertFalse(StudyAnchorInspector.inspectBundle(bundle, 90_000L).hasAnchorWarnings)
        val search = TimedSearchUseCase(com.openyt.data.search.SqlDelightTimedSearchRepository(db)).execute("Preserved") as TimedSearchResult.Success
        assertEquals(1, search.items.size)
        assertEquals(0, assets.loadAssetsForEntry("e1").size)
    }

    @Test
    fun safReplacementCopiesIntoOwnedDirMarksAnchorsAssetsStaleAndInvalidatesFtsAtomically() = runBlocking {
        val db = OpenYtDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OpenYtDatabase.Schema.create(it) })
        val downloads = SqlDelightDownloadRepository(db)
        val study = SqlDelightStudyRepository(db)
        val assets = SqlDelightOfflineAssetRepository(db)
        val subIndexRepo = SqlDelightSubtitleIndexRepository(db)

        val oldFile = File(tempFolder.root, "old-cut.mp4").apply { writeBytes("OLD-CUT".encodeToByteArray()) }
        downloads.insertLibraryEntry(
            LibraryEntry("e2", "Edited Lecture", MediaKind.VIDEO, oldFile.absolutePath, sizeBytes = oldFile.length(), mediaRevision = null),
        )
        // Study note & bookmark & range created WITHOUT a previous mediaRevision must still become stale after replacement!
        study.upsertNote(StudyNote("n2", "e2", 1_000L, 2_000L, "Null rev note", null, 10L, 10L))
        study.upsertBookmark(StudyBookmark("b2", "e2", 3_000L, "Mark", null, 10L, 10L))
        study.upsertSavedRange(SavedStudyRange("r2", "e2", 1_000L, 4_000L, "Seg", null, 10L, 10L))
        assets.insertAsset(
            OfflineAsset("a2", "e2", AssetKind.ARTWORK, null, "remote", "e2/art.jpg", 10L, "s2", AssetState.VERIFIED, mediaRevision = MediaRevision(oldFile.length(), oldFile.lastModified(), null)),
        )
        val subFile = File(tempFolder.root, "old-sub.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:03,000\nObsolete transcript line\n")
        }
        SubtitleIndexRebuilder(subIndexRepo, com.openyt.data.search.JvmSubtitleContentAdapter())
            .reindexTrack("trk2", "e2", subFile.absolutePath, "en", "rev-old")

        val safBytes = "NEW-SAF-REENCODED-STREAM".encodeToByteArray()
        val safSha = sha256(safBytes)
        val safUri = "content://com.android.externalstorage/document/primary%3ALectures%2Fnew-cut.mp4"

        val baseBridge = FilePortableStreamBridge()
        val safBridge = object : PortableStreamBridge by baseBridge {
            override fun openInputStream(locator: String): InputStream =
                if (locator == safUri) ByteArrayInputStream(safBytes) else baseBridge.openInputStream(locator)
        }

        val cand = RankedRecoveryCandidate(
            candidate = ScannedRecoveryCandidate(safUri, "new-cut.mp4", safBytes.size.toLong(), 0L, MediaKind.VIDEO, safSha),
            confidence = CandidateMatchConfidence.HEURISTIC_METADATA,
            verifiedSha256 = safSha,
            score = 520,
        )
        val plan = RelinkPlanner.buildPlan(
            entry = downloads.loadLibrary().single(),
            rankedCandidate = cand,
            targetIsScopedContentUri = true,
            existingStudyItemCount = 3,
            existingIndexedSubtitleCount = 1,
            existingOfflineAssetCount = 1,
            planId = "rel-saf-fault",
            nowEpochMillis = 700L,
        ).getOrThrow()

        val ownedRecoveryRoot = tempFolder.newFolder("owned-recovery")
        val stagingRoot = tempFolder.newFolder("relink-stage")
        val journalRepo = SqlDelightPortableOperationRepository(db)

        val crashingExecutor = RelinkExecutor(
            transferPort = JvmRelinkTransferPort(stagingRoot, safBridge),
            publicationPort = SqlDelightRelinkPublicationPort(db),
            journalRepository = journalRepo,
            storageReserveBytes = 0L,
            nowProvider = { 800L },
            faultInjectionBeforeCommit = { error("Simulated mid-commit crash") },
        )
        val crashed = crashingExecutor.executeRelink(plan, plan.confirmationToken, confirmedContentChange = true, ownedRecoveryRoot.absolutePath)
        assertTrue(crashed.isFailure)
        assertEquals(oldFile.absolutePath, downloads.loadLibrary().single().filePath)
        assertEquals(PortableOperationStatus.FAILED, journalRepo.getOperation("rel-saf-fault")?.status)

        val cleanExecutor = RelinkExecutor(
            transferPort = JvmRelinkTransferPort(stagingRoot, safBridge),
            publicationPort = SqlDelightRelinkPublicationPort(db),
            journalRepository = journalRepo,
            storageReserveBytes = 0L,
            nowProvider = { 900L },
        )
        val receipt = cleanExecutor.executeRelink(plan, plan.confirmationToken, confirmedContentChange = true, ownedRecoveryRoot.absolutePath)
            .getOrThrow()

        assertTrue(receipt.copiedFromSaf)
        assertEquals(1, receipt.staleAssetsFlagged)
        assertTrue(receipt.subtitleIndexesInvalidated)
        assertTrue(File(receipt.finalPath).startsWith(ownedRecoveryRoot))
        assertEquals(safBytes.toList(), File(receipt.finalPath).readBytes().toList())
        assertTrue(oldFile.isFile, "Previous user file must NEVER be auto-deleted during relink")

        val updatedEntry = downloads.loadLibrary().single()
        assertEquals(receipt.finalPath, updatedEntry.filePath)
        assertEquals(safSha, updatedEntry.mediaRevision?.contentSha256)

        // Study annotations are preserved intact AND all flagged stale
        val bundle = study.loadEntryStudyBundle("e2")
        assertEquals(1, bundle.notes.size)
        assertEquals(1, bundle.bookmarks.size)
        assertEquals(1, bundle.savedRanges.size)
        val inspection = StudyAnchorInspector.inspectBundle(bundle, 90_000L)
        assertTrue(inspection.hasAnchorWarnings)
        assertEquals(1, inspection.notes.count { it.anchorHealth.isRevisionStale })
        assertEquals(1, inspection.bookmarks.count { it.anchorHealth.isRevisionStale })
        assertEquals(1, inspection.savedRanges.count { it.anchorHealth.isRevisionStale })

        // Offline asset marked STALE, FTS index wiped cleanly
        assertEquals(AssetState.STALE, assets.loadAssetsForEntry("e2").single().state)
        assertTrue(subIndexRepo.loadTrackIndexesForEntry("e2").isEmpty())
        val search = TimedSearchUseCase(com.openyt.data.search.SqlDelightTimedSearchRepository(db)).execute("Obsolete")
        assertFalse(search is TimedSearchResult.Success)
    }

    private class FakePlaybackControl(
        override var loadedMedia: PlayableMedia?,
    ) : PlaybackSessionControl {
        override var state: PlaybackSessionState = PlaybackSessionState.PLAYING
        override val capabilities: PlaybackCapabilities = PlaybackCapabilities()
        override val currentPositionMillis: Long = 10_000L
        override val durationMillis: Long = 90_000L
        var stopCalls = 0

        override suspend fun openAt(
            items: List<PlayableMedia>,
            startIndex: Int,
            startPositionMillis: Long?,
        ): Boolean {
            loadedMedia = items.getOrNull(startIndex)
            state = PlaybackSessionState.PLAYING
            return loadedMedia != null
        }

        override suspend fun seekTo(positionMillis: Long): Boolean = true
        override suspend fun pause() { state = PlaybackSessionState.PAUSED }
        override suspend fun play() { state = PlaybackSessionState.PLAYING }
        override suspend fun stop() {
            stopCalls++
            loadedMedia = null
            state = PlaybackSessionState.IDLE
        }
        override fun close() = Unit
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
