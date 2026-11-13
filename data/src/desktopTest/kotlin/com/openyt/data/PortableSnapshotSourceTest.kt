package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PortableBlobRef
import com.openyt.core.application.PortableSnapshotCollector
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.SmartCollection
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyNote
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortableSnapshotSourceTest {

    @Test
    fun capturesConsistentSnapshotAcrossAllPortableTablesExcludingSecretsAndPaths() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OpenYtDatabase.Schema.create(driver)
        val db = OpenYtDatabase(driver)

        val downloadRepo = SqlDelightDownloadRepository(db)
        val studyRepo = SqlDelightStudyRepository(db)
        val playbackStore = SqlDelightPlaybackStore(db)
        val assetRepo = SqlDelightOfflineAssetRepository(db)
        val profileRepo = SqlDelightDownloadProfileRepository(db)

        val revision = MediaRevision(4096L, 1000L, "c".repeat(64))
        downloadRepo.insertLibraryEntry(
            LibraryEntry(
                id = "e1",
                title = "Course Lesson",
                kind = MediaKind.VIDEO,
                filePath = "/private/user/Videos/course-lesson.mp4",
                sizeBytes = 4096L,
                createdAtEpochMillis = 500L,
                thumbnailUrl = "https://user:pass@media.example.org/t.jpg?cookie=secret",
                source = "vimeo",
                tags = listOf("math", "algebra"),
                author = "Dr. Emmy",
                mediaRevision = revision,
                lastVerifiedEpochMillis = 1200L,
            ),
        )
        downloadRepo.insertCollection(Collection("col1", "Math"))
        downloadRepo.addEntryToCollection("col1", "e1")
        downloadRepo.insertSmartCollection(SmartCollection("sm1", "Videos", query = "Lesson", kind = MediaKind.VIDEO))
        // Legacy path-keyed checkpoint without entry_id still maps cleanly to e1 via filePath without leaking filePath!
        playbackStore.savePosition("/private/user/Videos/course-lesson.mp4", PlaybackCheckpoint(33_000L, 120_000L), entryId = null)

        studyRepo.upsertNote(StudyNote("n1", "e1", 10_000L, 12_000L, "Theorem 1", revision, 100L, 100L))
        studyRepo.upsertBookmark(StudyBookmark("b1", "e1", 20_000L, "Proof start", revision, 100L, 100L))
        studyRepo.upsertSavedRange(SavedStudyRange("r1", "e1", 5_000L, 15_000L, "Loop", revision, 100L, 100L))
        studyRepo.saveEntryStudyState(EntryStudyState("e1", isStudied = true, studiedAtEpochMillis = 200L, updatedAtEpochMillis = 200L))

        assetRepo.insertAsset(
            OfflineAsset(
                id = "a1",
                entryId = "e1",
                kind = AssetKind.SUBTITLE,
                language = "en",
                sourceType = "embedded",
                relativeLocator = "entries/e1/sub.en.vtt",
                byteCount = 512L,
                sha256 = "d".repeat(64),
                state = AssetState.VERIFIED,
                isRequired = false,
                isPinned = true,
                mediaRevision = revision,
                createdAtEpochMillis = 150L,
            ),
        )
        profileRepo.saveCustomProfile(
            DownloadProfile(
                id = "prof1",
                name = "Archive Profile",
                isBuiltIn = false,
                kind = MediaKind.AUDIO,
            ),
        )

        val snapshot = SqlDelightPortableBackupSnapshotSource(db).captureConsistentSnapshot()
        assertEquals(1, snapshot.entries.size)
        assertEquals(33_000L, snapshot.playbackByEntryId["e1"]?.positionMillis)
        assertEquals(listOf("Theorem 1"), snapshot.studyBundlesByEntryId["e1"]?.notes?.map { it.body })
        assertTrue(snapshot.studyBundlesByEntryId["e1"]?.studyState?.isStudied == true)
        assertEquals(listOf("prof1"), snapshot.customProfiles.map { it.id })

        val manifest = PortableSnapshotCollector.assembleManifest(
            snapshot = snapshot,
            bundleId = "snap-1",
            createdAtEpochMillis = 2000L,
            primaryBlobsByEntryId = mapOf(
                "e1" to PortableBlobRef("media/e1/course-lesson.mp4", 4096L, "c".repeat(64), "course-lesson.mp4", MediaKind.VIDEO),
            ),
            assetBlobsByAssetId = mapOf(
                "a1" to PortableBlobRef("assets/e1/sub.en.vtt", 512L, "d".repeat(64), "sub.en.vtt", MediaKind.SUBTITLE),
            ),
        )
        val json = com.openyt.core.application.PortableBackupCodec.encode(manifest)
        assertFalse(json.contains("/private/user/Videos"))
        assertFalse(json.contains("user:pass"))
        assertFalse(json.contains("cookie=secret"))
        assertTrue(json.contains("Archive Profile"))

        val goldenFile = listOf(
            java.io.File("core/src/commonTest/resources/fixtures/backup/golden_manifest_v1.json"),
            java.io.File("../core/src/commonTest/resources/fixtures/backup/golden_manifest_v1.json"),
        ).first { it.isFile }
        val goldenManifest = com.openyt.core.application.PortableBackupCodec.decode(goldenFile.readText()).getOrThrow()
        assertEquals(1, goldenManifest.formatVersion)
        assertEquals("golden-bundle-v1", goldenManifest.bundleId)
        assertEquals(1, goldenManifest.entries.size)
        assertEquals(1, goldenManifest.collections.size)
        assertEquals(1, goldenManifest.downloadProfiles.size)
    }
}
