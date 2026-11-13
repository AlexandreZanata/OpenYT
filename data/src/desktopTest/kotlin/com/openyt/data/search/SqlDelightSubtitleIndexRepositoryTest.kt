package com.openyt.data.search

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperation
import com.openyt.core.domain.AssetOperationStatus
import com.openyt.core.domain.SubtitleIndexState
import com.openyt.core.domain.SubtitleTrackIndexRecord
import com.openyt.core.domain.TimedCue
import com.openyt.data.SqlDelightAssetOperationRepository
import com.openyt.data.SqlDelightOfflineAssetRepository
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightSubtitleIndexRepositoryTest {

    @Test
    fun schemaUpgradeFromV21ToV22PreservesExistingDataAndCreatesSubtitleIndexTables() = runBlocking {
        val file = File.createTempFile("openyt-v21-to-v22-", ".db").apply { delete() }
        try {
            createVersionTwentyOneDatabase(file)

            val database = createOpenYtDatabase(file.absolutePath)

            // Verify user_version reached current schema version (at least 22)
            val version = readUserVersion(file)
            assertEquals(OpenYtDatabase.Schema.version, version)
            assertTrue(version >= 22L)

            // Verify existing v21 data is preserved
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val existingAssets = assetRepo.loadAssetsForEntry("entry-v21")
            assertEquals(1, existingAssets.size)
            assertEquals("asset-v21", existingAssets.single().id)

            val opRepo = SqlDelightAssetOperationRepository(database)
            val op = opRepo.getOperation("op-v21")
            assertNotNull(op)
            assertEquals("op-v21", op.id)
            assertEquals(AssetOperationStatus.SUCCEEDED, op.status)

            // Verify new subtitle index repository works on upgraded database
            val indexRepo = SqlDelightSubtitleIndexRepository(database)
            val trackRecord = SubtitleTrackIndexRecord(
                trackId = "track-migrated-1",
                entryId = "entry-v21",
                language = "pt",
                mediaRevisionToken = "media-rev-v21",
                trackRevision = "track-sha-v21",
                activeRevision = 1,
                state = SubtitleIndexState.READY,
                totalCues = 1,
            )
            indexRepo.saveTrackIndex(trackRecord)

            val loaded = indexRepo.getTrackIndex("track-migrated-1")
            assertNotNull(loaded)
            assertEquals("track-migrated-1", loaded.trackId)
            assertEquals(SubtitleIndexState.READY, loaded.state)
        } finally {
            file.delete()
        }
    }

    @Test
    fun atomicRevisionCommitRetainsUsableOldIndexUntilCommit() = runBlocking {
        val file = File.createTempFile("openyt-atomic-rev-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val repo = SqlDelightSubtitleIndexRepository(database)

            // Build revision 1 with 2 cues
            val rev1 = repo.beginRevision(
                trackId = "track-1",
                entryId = "entry-1",
                language = "pt",
                mediaRevisionToken = "media-v1",
                trackRevision = "sha-v1",
            )
            assertEquals(1, rev1)

            val cuesRev1 = listOf(
                TimedCue(
                    cueId = "cue-1",
                    trackId = "track-1",
                    entryId = "entry-1",
                    startMs = 1000L,
                    endMs = 3000L,
                    displayText = "Primeira linha da legenda",
                    plainText = "Primeira linha da legenda",
                    originalIndex = 1,
                ),
                TimedCue(
                    cueId = "cue-2",
                    trackId = "track-1",
                    entryId = "entry-1",
                    startMs = 4000L,
                    endMs = 7000L,
                    displayText = "Segunda linha com música e café",
                    plainText = "Segunda linha com música e café",
                    originalIndex = 2,
                ),
            )
            repo.persistCues(cuesRev1, "track-1", "entry-1", rev1)
            repo.commitRevision("track-1", rev1, totalCues = 2, timebaseOffsetMs = 0L)

            val activeCuesRev1 = repo.loadActiveCues("track-1")
            assertEquals(2, activeCuesRev1.size)
            assertEquals("cue-1", activeCuesRev1[0].cueId)

            // Now begin revision 2 with 3 new cues
            val rev2 = repo.beginRevision(
                trackId = "track-1",
                entryId = "entry-1",
                language = "pt",
                mediaRevisionToken = "media-v1",
                trackRevision = "sha-v2",
            )
            assertEquals(2, rev2)

            // While revision 2 is being staged, active revision must still be 1
            val trackDuringStaging = repo.getTrackIndex("track-1")!!
            assertEquals(1, trackDuringStaging.activeRevision, "Active revision must remain 1 during revision 2 staging")
            assertEquals(SubtitleIndexState.INDEXING, trackDuringStaging.state)

            // Querying active cues must return revision 1 cues ONLY (no partial results from rev 2)
            val cuesWhileRev2Staging = repo.loadActiveCues("track-1")
            assertEquals(2, cuesWhileRev2Staging.size, "Active cues must only expose committed revision 1")
            assertEquals("cue-1", cuesWhileRev2Staging[0].cueId)

            val cuesRev2 = listOf(
                TimedCue(
                    cueId = "cue-v2-1",
                    trackId = "track-1",
                    entryId = "entry-1",
                    startMs = 1000L,
                    endMs = 2500L,
                    displayText = "Nova primeira linha",
                    plainText = "Nova primeira linha",
                    originalIndex = 1,
                ),
                TimedCue(
                    cueId = "cue-v2-2",
                    trackId = "track-1",
                    entryId = "entry-1",
                    startMs = 3000L,
                    endMs = 5000L,
                    displayText = "Nova segunda linha",
                    plainText = "Nova segunda linha",
                    originalIndex = 2,
                ),
                TimedCue(
                    cueId = "cue-v2-3",
                    trackId = "track-1",
                    entryId = "entry-1",
                    startMs = 6000L,
                    endMs = 9000L,
                    displayText = "Nova terceira linha com coração",
                    plainText = "Nova terceira linha com coração",
                    originalIndex = 3,
                ),
            )
            repo.persistCues(cuesRev2, "track-1", "entry-1", rev2)

            // Both revisions exist in database during staging, but only rev 1 is active
            val countsRev1 = repo.countCuesAndFts("track-1", rev1)
            assertEquals(Pair(2, 2), countsRev1)
            val countsRev2 = repo.countCuesAndFts("track-1", rev2)
            assertEquals(Pair(3, 3), countsRev2)

            // Commit revision 2
            repo.commitRevision("track-1", rev2, totalCues = 3, timebaseOffsetMs = 0L)

            val committedTrack = repo.getTrackIndex("track-1")!!
            assertEquals(2, committedTrack.activeRevision)
            assertEquals(SubtitleIndexState.READY, committedTrack.state)
            assertEquals(3, committedTrack.totalCues)

            val activeCuesCommitted = repo.loadActiveCues("track-1")
            assertEquals(3, activeCuesCommitted.size)
            assertEquals("cue-v2-1", activeCuesCommitted[0].cueId)
            assertEquals("cue-v2-3", activeCuesCommitted[2].cueId)

            // Older revision 1 cues must be cleaned
            val oldRev1Counts = repo.countCuesAndFts("track-1", rev1)
            assertEquals(Pair(0, 0), oldRev1Counts)
        } finally {
            file.delete()
        }
    }

    @Test
    fun reconcileCuesAndFtsRowCountsMatchExactly() = runBlocking {
        val file = File.createTempFile("openyt-reconcile-counts-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val repo = SqlDelightSubtitleIndexRepository(database)

            val rev = repo.beginRevision("track-count", "entry-1", "en", "media-token", "track-rev")
            val cues = (1..50).map { i ->
                TimedCue(
                    cueId = "cue-$i",
                    trackId = "track-count",
                    entryId = "entry-1",
                    startMs = i * 1000L,
                    endMs = i * 1000L + 800L,
                    displayText = "Line $i in audio track",
                    plainText = "Line $i in audio track",
                    originalIndex = i,
                )
            }
            repo.persistCues(cues, "track-count", "entry-1", rev)

            val counts = repo.countCuesAndFts("track-count", rev)
            assertEquals(50, counts.first, "B-tree cue count must match 50")
            assertEquals(50, counts.second, "FTS virtual table count must match 50")
            assertEquals(counts.first, counts.second, "B-tree cue count and FTS row count must reconcile 1:1")
        } finally {
            file.delete()
        }
    }

    @Test
    fun crashMidReindexCleansAbandonedRevisionRowsWithoutTouchingMedia() = runBlocking {
        val file = File.createTempFile("openyt-crash-reindex-", ".db").apply { delete() }
        val dummyMedia = File.createTempFile("dummy-video-", ".mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val repo = SqlDelightSubtitleIndexRepository(database)

            // Track 1: has active revision 1 committed
            val t1Rev1 = repo.beginRevision("track-1", "entry-1", "pt", "media-v1", "sha-v1")
            val t1CuesRev1 = listOf(
                TimedCue("t1-c1", "track-1", "entry-1", 1000L, 2000L, "T1 C1", "T1 C1", 1),
                TimedCue("t1-c2", "track-1", "entry-1", 3000L, 4000L, "T1 C2", "T1 C2", 2),
            )
            repo.persistCues(t1CuesRev1, "track-1", "entry-1", t1Rev1)
            repo.commitRevision("track-1", t1Rev1, 2, 0L)

            // Simulate crash mid-reindex on Track 1 for revision 2:
            val t1Rev2 = repo.beginRevision("track-1", "entry-1", "pt", "media-v1", "sha-v2")
            val t1CuesRev2 = (1..5).map { i ->
                TimedCue("t1-rev2-$i", "track-1", "entry-1", i * 1000L, i * 1000L + 500L, "Staging $i", "Staging $i", i)
            }
            repo.persistCues(t1CuesRev2, "track-1", "entry-1", t1Rev2)
            // Process crashes! State left in INDEXING, rev 2 not committed.

            // Track 2: brand new track undergoing initial indexing (active revision was 0):
            val t2Rev1 = repo.beginRevision("track-2", "entry-1", "en", "media-v1", "sha-t2")
            val t2Cues = (1..3).map { i ->
                TimedCue("t2-$i", "track-2", "entry-1", i * 1000L, i * 1000L + 500L, "T2 $i", "T2 $i", i)
            }
            repo.persistCues(t2Cues, "track-2", "entry-1", t2Rev1)
            // Process crashes! State left in INDEXING, rev 1 not committed.

            val mediaSizeBefore = dummyMedia.length()
            val mediaBytesBefore = dummyMedia.readBytes()

            // Run reconciliation
            val summary = repo.cleanAbandonedRevisions()
            assertEquals(2, summary.reconciledTracks)
            assertEquals(2, summary.interruptedJobsRecovered)
            // 5 cues from track 1 rev 2 + 3 cues from track 2 rev 1 = 8 orphaned cues cleaned
            assertEquals(8, summary.cleanedOrphanedCues)
            assertEquals(8, summary.cleanedOrphanedFtsRows)

            // Track 1 should be restored to STALE with its original revision 1 intact
            val t1After = repo.getTrackIndex("track-1")!!
            assertEquals(1, t1After.activeRevision)
            assertEquals(SubtitleIndexState.STALE, t1After.state)
            val t1ActiveCues = repo.loadActiveCues("track-1")
            assertEquals(2, t1ActiveCues.size)
            assertEquals("t1-c1", t1ActiveCues[0].cueId)

            // Track 2 should be transitioned to FAILED (since it had no prior active revision)
            val t2After = repo.getTrackIndex("track-2")!!
            assertEquals(0, t2After.activeRevision)
            assertEquals(SubtitleIndexState.FAILED, t2After.state)
            val t2ActiveCues = repo.loadActiveCues("track-2")
            assertEquals(0, t2ActiveCues.size)

            // Confirm dummy media is completely untouched
            assertTrue(dummyMedia.exists())
            assertEquals(mediaSizeBefore, dummyMedia.length())
            assertTrue(dummyMedia.readBytes().contentEquals(mediaBytesBefore))
        } finally {
            file.delete()
            dummyMedia.delete()
        }
    }

    @Test
    fun deleteTrackCleansCueAndFtsRowsWithoutTouchingMediaFile() = runBlocking {
        val file = File.createTempFile("openyt-delete-track-", ".db").apply { delete() }
        val dummyMedia = File.createTempFile("dummy-video-", ".mp4").apply {
            writeBytes(byteArrayOf(10, 20, 30, 40))
        }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val repo = SqlDelightSubtitleIndexRepository(database)

            val rev = repo.beginRevision("track-del", "entry-1", "pt", "media-v1", "sha-del")
            val cues = listOf(
                TimedCue("cue-1", "track-del", "entry-1", 1000L, 2000L, "Texto 1", "Texto 1", 1),
                TimedCue("cue-2", "track-del", "entry-1", 2500L, 3500L, "Texto 2", "Texto 2", 2),
            )
            repo.persistCues(cues, "track-del", "entry-1", rev)
            repo.commitRevision("track-del", rev, 2, 0L)

            assertEquals(2, repo.loadActiveCues("track-del").size)

            val mediaBytesBefore = dummyMedia.readBytes()

            // Delete track index
            repo.deleteTrackIndex("track-del")

            assertNull(repo.getTrackIndex("track-del"))
            assertEquals(0, repo.loadActiveCues("track-del").size)
            assertEquals(Pair(0, 0), repo.countCuesAndFts("track-del", rev))

            // Verify media file is untouched
            assertTrue(dummyMedia.exists())
            assertTrue(dummyMedia.readBytes().contentEquals(mediaBytesBefore))
        } finally {
            file.delete()
            dummyMedia.delete()
        }
    }

    @Test
    fun deleteTrackIndexesForEntryCleansAllAssociatedTracks() = runBlocking {
        val file = File.createTempFile("openyt-delete-entry-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val repo = SqlDelightSubtitleIndexRepository(database)

            // Two tracks for entry-1
            val revPt = repo.beginRevision("track-pt", "entry-1", "pt", "media-v1", "sha-pt")
            repo.persistCues(listOf(TimedCue("pt-1", "track-pt", "entry-1", 1000L, 2000L, "Em pt", "Em pt", 1)), "track-pt", "entry-1", revPt)
            repo.commitRevision("track-pt", revPt, 1, 0L)

            val revEn = repo.beginRevision("track-en", "entry-1", "en", "media-v1", "sha-en")
            repo.persistCues(listOf(TimedCue("en-1", "track-en", "entry-1", 1000L, 2000L, "In en", "In en", 1)), "track-en", "entry-1", revEn)
            repo.commitRevision("track-en", revEn, 1, 0L)

            // One track for entry-2
            val revOther = repo.beginRevision("track-other", "entry-2", "es", "media-v2", "sha-other")
            repo.persistCues(listOf(TimedCue("other-1", "track-other", "entry-2", 1000L, 2000L, "En es", "En es", 1)), "track-other", "entry-2", revOther)
            repo.commitRevision("track-other", revOther, 1, 0L)

            assertEquals(2, repo.loadTrackIndexesForEntry("entry-1").size)
            assertEquals(1, repo.loadTrackIndexesForEntry("entry-2").size)

            repo.deleteTrackIndexesForEntry("entry-1")

            assertEquals(0, repo.loadTrackIndexesForEntry("entry-1").size)
            assertEquals(0, repo.loadActiveCues("track-pt").size)
            assertEquals(0, repo.loadActiveCues("track-en").size)

            // entry-2 should remain unaffected
            assertEquals(1, repo.loadTrackIndexesForEntry("entry-2").size)
            assertEquals(1, repo.loadActiveCues("track-other").size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun entryInvalidationTransitionsAllTracksToStale() = runBlocking {
        val file = File.createTempFile("openyt-invalidate-", ".db").apply { delete() }
        try {
            val database = createOpenYtDatabase(file.absolutePath)
            val repo = SqlDelightSubtitleIndexRepository(database)

            val rev = repo.beginRevision("track-1", "entry-1", "pt", "media-v1", "sha-1")
            repo.persistCues(listOf(TimedCue("c-1", "track-1", "entry-1", 1000L, 2000L, "Text", "Text", 1)), "track-1", "entry-1", rev)
            repo.commitRevision("track-1", rev, 1, 0L)

            assertEquals(SubtitleIndexState.READY, repo.getTrackIndex("track-1")!!.state)

            repo.invalidateEntry("entry-1", SubtitleIndexState.STALE)

            val track = repo.getTrackIndex("track-1")!!
            assertEquals(SubtitleIndexState.STALE, track.state)
            // Usable old cues remain available
            assertEquals(1, repo.loadActiveCues("track-1").size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun reopenDatabasePreservesIndexRevisionsAndCues() = runBlocking {
        val file = File.createTempFile("openyt-reopen-", ".db").apply { delete() }
        try {
            val db1 = createOpenYtDatabase(file.absolutePath)
            val repo1 = SqlDelightSubtitleIndexRepository(db1)

            val rev = repo1.beginRevision("track-persist", "entry-p", "pt", "media-p", "sha-p")
            val cues = listOf(
                TimedCue("cue-p1", "track-persist", "entry-p", 1000L, 3000L, "Texto persistente 1", "Texto persistente 1", 1),
                TimedCue("cue-p2", "track-persist", "entry-p", 4000L, 7000L, "Texto persistente 2", "Texto persistente 2", 2),
            )
            repo1.persistCues(cues, "track-persist", "entry-p", rev)
            repo1.commitRevision("track-persist", rev, 2, 500L)

            // Reopen database
            val db2 = createOpenYtDatabase(file.absolutePath)
            val repo2 = SqlDelightSubtitleIndexRepository(db2)

            val loadedTrack = repo2.getTrackIndex("track-persist")
            assertNotNull(loadedTrack)
            assertEquals("track-persist", loadedTrack.trackId)
            assertEquals("entry-p", loadedTrack.entryId)
            assertEquals(SubtitleIndexState.READY, loadedTrack.state)
            assertEquals(1, loadedTrack.activeRevision)
            assertEquals(2, loadedTrack.totalCues)
            assertEquals(500L, loadedTrack.timebaseOffsetMs)

            val loadedCues = repo2.loadActiveCues("track-persist")
            assertEquals(2, loadedCues.size)
            assertEquals("cue-p1", loadedCues[0].cueId)
            assertEquals("Texto persistente 1", loadedCues[0].plainText)
            assertEquals("Texto persistente 2", loadedCues[1].plainText)

            val counts = repo2.countCuesAndFts("track-persist", 1)
            assertEquals(Pair(2, 2), counts)
        } finally {
            file.delete()
        }
    }

    private fun readUserVersion(file: File): Long =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    if (result.next()) result.getLong(1) else 0L
                }
            }
        }

    private fun createVersionTwentyOneDatabase(file: File) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE download_task (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        id TEXT NOT NULL UNIQUE,
                        source_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        author TEXT,
                        source TEXT NOT NULL,
                        url TEXT NOT NULL,
                        webpage_url TEXT NOT NULL,
                        duration_seconds INTEGER,
                        thumbnail_url TEXT,
                        kind TEXT NOT NULL,
                        format_id TEXT,
                        destination_directory TEXT NOT NULL,
                        status TEXT NOT NULL,
                        percent REAL NOT NULL DEFAULT 0,
                        speed_bytes_per_second INTEGER,
                        eta_seconds INTEGER,
                        file_path TEXT,
                        size_bytes INTEGER,
                        failure_kind TEXT,
                        failure_detail TEXT,
                        created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE library_entry (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        file_path TEXT NOT NULL,
                        size_bytes INTEGER,
                        created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = 1")
            }
        }

        // Migrate to version 21
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        OpenYtDatabase.Schema.migrate(driver, 1, 21)
        driver.close()

        // Insert version 21 data
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO offline_asset(
                        id, entry_id, kind, language, source_type, relative_locator, byte_count,
                        sha256, state, is_required, is_pinned, media_revision_size, media_revision_mtime,
                        media_revision_sha256, created_at, last_verified_at, failure_detail
                    ) VALUES (
                        'asset-v21', 'entry-v21', 'SUBTITLE', 'en', 'EXTRACTED', 'entry-v21/asset-v21.srt',
                        1024, 'dummy-sha-v21', 'VERIFIED', 1, 0, 5000, 1000, 'media-sha', 1000, 1000, NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO asset_operation(
                        id, entry_id, kind, source_path, language, source_type, timeline_basis,
                        is_required, status, staging_path, target_locator, asset_id, byte_count,
                        expected_size_bytes, sha256, error, attempt, created_at, updated_at
                    ) VALUES (
                        'op-v21', 'entry-v21', 'SUBTITLE', '/tmp/sub.srt', 'en', 'EXTRACTED', 'SOURCE',
                        0, 'SUCCEEDED', NULL, 'entry-v21/asset-v21.srt', 'asset-v21', 1024,
                        1024, 'dummy-sha-v21', NULL, 1, 1000, 2000
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("PRAGMA user_version = 21")
            }
        }
    }
}
