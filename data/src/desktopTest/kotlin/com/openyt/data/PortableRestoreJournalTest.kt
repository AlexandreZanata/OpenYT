package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PortableBackupExportService
import com.openyt.core.application.PortableBackupFailure
import com.openyt.core.application.PortableBackupException
import com.openyt.core.application.PortableExportOptions
import com.openyt.core.application.PortableOperationStatus
import com.openyt.core.application.PortableRestoreExecutor
import com.openyt.core.application.PortableRestoreOptions
import com.openyt.core.application.PortableRestorePlanner
import com.openyt.core.application.SubtitleIndexRebuilder
import com.openyt.core.application.TimedSearchUseCase
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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PortableRestoreJournalTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun faultBeforeSqlCommitPreservesExistingLibraryAndRetriesWithoutOverwritingFiles() = runBlocking {
        // Export from source DB
        val sourceDb = OpenYtDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OpenYtDatabase.Schema.create(it) })
        val sourceMediaDir = tempFolder.newFolder("src-media")
        val sourceAssetsRoot = tempFolder.newFolder("src-assets")
        val sourceStaging = tempFolder.newFolder("src-staging")
        val archiveFile = File(tempFolder.root, "bundle.openyt-backup.zip")

        val videoBytes = ByteArray(16_384) { (it * 7).toByte() }
        val videoFile = File(sourceMediaDir, "lecture.mp4").apply { writeBytes(videoBytes) }
        val subDir = File(sourceAssetsRoot, "e1").apply { mkdirs() }
        val subFile = File(subDir, "sub.srt").apply {
            writeText(
                """
                1
                00:00:01,000 --> 00:00:04,000
                Resilience across atomic database restores
                """.trimIndent(),
            )
        }
        val videoSha = sha256(videoBytes)
        val subSha = sha256(subFile.readBytes())
        val rev = MediaRevision(videoFile.length(), videoFile.lastModified(), videoSha)

        val srcDownloads = SqlDelightDownloadRepository(sourceDb)
        val srcStudy = SqlDelightStudyRepository(sourceDb)
        val srcAssets = SqlDelightOfflineAssetRepository(sourceDb)
        val srcPlayback = SqlDelightPlaybackStore(sourceDb)
        val srcProfiles = SqlDelightDownloadProfileRepository(sourceDb)

        srcDownloads.insertLibraryEntry(
            LibraryEntry(
                id = "e1",
                title = "Portable Systems Lecture",
                kind = MediaKind.VIDEO,
                filePath = videoFile.absolutePath,
                sizeBytes = videoFile.length(),
                createdAtEpochMillis = 500L,
                source = "youtube",
                tags = listOf("systems", "sqlite"),
                mediaRevision = rev,
            ),
        )
        srcDownloads.insertCollection(Collection("col1", "My Course"))
        srcDownloads.addEntryToCollection("col1", "e1")
        srcDownloads.insertSmartCollection(SmartCollection("sm1", "Tagged Systems", tag = "systems"))
        srcPlayback.savePosition(videoFile.absolutePath, PlaybackCheckpoint(22_000L, 120_000L, "e1"), "e1")
        srcStudy.upsertNote(StudyNote("n1", "e1", 5_000L, 9_000L, "Key transaction point", rev, 100L, 100L))
        srcStudy.upsertBookmark(StudyBookmark("b1", "e1", 22_000L, "Checkpoint", rev, 100L, 100L))
        srcStudy.upsertSavedRange(SavedStudyRange("r1", "e1", 1_000L, 6_000L, "Drill", rev, 100L, 100L))
        srcStudy.saveEntryStudyState(EntryStudyState("e1", isStudied = true, studiedAtEpochMillis = 150L, updatedAtEpochMillis = 150L))
        srcAssets.insertAsset(
            OfflineAsset(
                id = "a1",
                entryId = "e1",
                kind = AssetKind.SUBTITLE,
                language = "en",
                sourceType = "manual",
                relativeLocator = "e1/sub.srt",
                byteCount = subFile.length(),
                sha256 = subSha,
                state = AssetState.VERIFIED,
                isRequired = false,
                isPinned = true,
                mediaRevision = rev,
                createdAtEpochMillis = 120L,
            ),
        )
        srcProfiles.saveCustomProfile(DownloadProfile(id = "prof-1", name = "Portable Profile", isBuiltIn = false))

        PortableBackupExportService(
            snapshotSource = SqlDelightPortableBackupSnapshotSource(sourceDb),
            writerPort = JvmPortableArchiveWriter(sourceStaging),
            storageAccess = JvmPortableStorageAccessPort(sourceStaging),
            journalRepository = SqlDelightPortableOperationRepository(sourceDb),
            ownedAssetLocatorResolver = { File(sourceAssetsRoot, it.relativeLocator).canonicalPath },
            storageReserveBytes = 1024L,
            nowProvider = { System.currentTimeMillis() },
        ).exportBackup(archiveFile.absolutePath, PortableExportOptions()).getOrThrow()

        // Target DB already has colliding IDs AND a preexisting "Restored/lecture.mp4" user file
        val targetDb = OpenYtDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { OpenYtDatabase.Schema.create(it) })
        val tgtDownloads = SqlDelightDownloadRepository(targetDb)
        tgtDownloads.insertLibraryEntry(
            LibraryEntry(id = "e1", title = "Existing Target Entry", kind = MediaKind.VIDEO, filePath = "/existing/orig.mp4"),
        )
        val targetDestRoot = tempFolder.newFolder("tgt-dest")
        val restoredFolder = File(targetDestRoot, "Restored").apply { mkdirs() }
        val existingDiskFile = File(restoredFolder, "lecture.mp4").apply { writeText("PREEXISTING USER FILE") }
        val targetAssetsRoot = tempFolder.newFolder("tgt-assets")
        val targetStaging = tempFolder.newFolder("tgt-staging")
        val targetJournal = SqlDelightPortableOperationRepository(targetDb)
        val storagePort = JvmPortableStorageAccessPort(targetStaging)

        val planner = PortableRestorePlanner(
            snapshotSource = SqlDelightPortableBackupSnapshotSource(targetDb),
            readerPort = JvmPortableArchiveReader(),
            storageAccess = storagePort,
            journalRepository = targetJournal,
            storageReserveBytes = 1024L,
            nowProvider = { System.currentTimeMillis() },
        )

        // 1. Plan & inject simulated crash BEFORE SQLite commit
        val crashedPlan = planner.prepareRestorePlan(archiveFile.absolutePath, targetDestRoot.absolutePath).getOrThrow()
        val faultExecutor = PortableRestoreExecutor(
            storageAccess = storagePort,
            journalRepository = targetJournal,
            publicationPort = SqlDelightPortableRestorePublicationPort(targetDb),
            faultInjectionBeforeCommit = { error("Simulated crash before DB commit") },
        )

        val crashResult = faultExecutor.executeRestore(crashedPlan, crashedPlan.archiveSha256, targetAssetsRoot.absolutePath)
        assertTrue(crashResult.isFailure)
        // Existing target entry preserved untouched; new entry NOT published atomically
        assertEquals(1, tgtDownloads.loadLibrary().size)
        assertEquals("Existing Target Entry", tgtDownloads.loadLibrary().single().title)
        assertEquals("PREEXISTING USER FILE", existingDiskFile.readText())
        assertEquals(PortableOperationStatus.FAILED, targetJournal.getOperation(crashedPlan.operationId)?.status)

        // 2. Plan mismatch / confirmation guard rejects stale or wrong digest
        val mismatchErr = faultExecutor.executeRestore(crashedPlan, "0".repeat(64), targetAssetsRoot.absolutePath)
            .exceptionOrNull() as PortableBackupException
        assertTrue(mismatchErr.failure is PortableBackupFailure.PlanMismatch)

        // 3. Re-plan & execute clean restore + rebuild FTS search index
        val cleanPlan = planner.prepareRestorePlan(archiveFile.absolutePath, targetDestRoot.absolutePath).getOrThrow()
        val subtitleRebuilder = SubtitleIndexRebuilder(
            indexRepository = SqlDelightSubtitleIndexRepository(targetDb),
            contentPort = com.openyt.data.search.JvmSubtitleContentAdapter(JvmMediaFileInspector()),
        )
        val cleanExecutor = PortableRestoreExecutor(
            storageAccess = storagePort,
            journalRepository = targetJournal,
            publicationPort = SqlDelightPortableRestorePublicationPort(targetDb),
            subtitleRebuilder = subtitleRebuilder,
            ownedAssetLocatorResolver = { File(targetAssetsRoot, it.relativeLocator).canonicalPath },
            nowProvider = { System.currentTimeMillis() },
        )
        val receipt = cleanExecutor.executeRestore(cleanPlan, cleanPlan.archiveSha256, targetAssetsRoot.absolutePath).getOrThrow()
        assertEquals(1, receipt.entriesRestored)
        assertEquals(1, receipt.notesRestored)
        assertEquals(1, receipt.bookmarksRestored)
        assertEquals(1, receipt.savedRangesRestored)
        assertEquals(1, receipt.collectionsRestored)
        assertEquals(1, receipt.customProfilesRestored)
        assertEquals(1, receipt.subtitlesReindexed)
        assertEquals(PortableOperationStatus.SUCCEEDED, targetJournal.getOperation(cleanPlan.operationId)?.status)

        // Preexisting file untouched; restored copy placed cleanly at non-overwriting path
        assertEquals("PREEXISTING USER FILE", existingDiskFile.readText())
        val allEntries = tgtDownloads.loadLibrary()
        assertEquals(2, allEntries.size)
        val restoredEntry = allEntries.first { it.id != "e1" }
        assertTrue(File(restoredEntry.filePath).isFile)
        assertNotEquals(existingDiskFile.canonicalPath, File(restoredEntry.filePath).canonicalPath)
        assertEquals(videoBytes.toList(), File(restoredEntry.filePath).readBytes().toList())

        // Playback position & study & FTS search all immediately functional on restored entry
        val tgtPlayback = SqlDelightPlaybackStore(targetDb)
        assertEquals(22_000L, tgtPlayback.position(restoredEntry.filePath, restoredEntry.id)?.positionMillis)

        val tgtStudy = SqlDelightStudyRepository(targetDb)
        val bundle = tgtStudy.loadEntryStudyBundle(restoredEntry.id)
        assertTrue(bundle.studyState.isStudied)
        assertEquals("Key transaction point", bundle.notes.single().body)
        assertEquals("Checkpoint", bundle.bookmarks.single().label)
        assertEquals("Drill", bundle.savedRanges.single().label)

        val searchUseCase = TimedSearchUseCase(com.openyt.data.search.SqlDelightTimedSearchRepository(targetDb))
        val searchResult = searchUseCase.execute("atomic database") as com.openyt.core.domain.TimedSearchResult.Success
        assertEquals(1, searchResult.items.size)
        assertEquals(restoredEntry.id, searchResult.items.single().entryId)

        // Idempotent retry protection: same plan cannot be applied twice after SUCCEEDED
        val secondApply = cleanExecutor.executeRestore(cleanPlan, cleanPlan.archiveSha256, targetAssetsRoot.absolutePath)
            .exceptionOrNull() as PortableBackupException
        assertTrue(secondApply.failure is PortableBackupFailure.PlanMismatch)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
