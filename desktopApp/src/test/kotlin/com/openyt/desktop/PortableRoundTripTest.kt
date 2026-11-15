package com.openyt.desktop

import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PortableExportOptions
import com.openyt.core.application.PortableRestoreConflictPolicy
import com.openyt.core.application.PortableRestoreOptions
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
import com.openyt.core.domain.TimedSearchResult
import com.openyt.data.FilePortableStreamBridge
import com.openyt.data.JvmOfflineAssetStore
import com.openyt.data.PortableStreamBridge
import com.openyt.data.SqlDelightAssetOperationRepository
import com.openyt.data.SqlDelightDownloadProfileRepository
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightOfflineAssetRepository
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.createPortableBackupRuntime
import com.openyt.data.search.SqlDelightTimedSearchRepository
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PortableRoundTripTest {

    @Test
    fun desktopExportRestoresIntoIsolatedTargetViaChunkedNonSeekableSafBridge() = runBlocking {
        val tempRoot = createTempDirectory("openyt-roundtrip").toFile()
        try {
            val sourceRoot = File(tempRoot, "source").apply { mkdirs() }
            val sourceDownloads = File(sourceRoot, "downloads").apply { mkdirs() }
            val sourceAssetsDir = File(sourceRoot, "assets").apply { mkdirs() }
            val sourceDb = createOpenYtDatabase(File(sourceRoot, "openyt.db").absolutePath)

            val sourceRepo = SqlDelightDownloadRepository(sourceDb)
            val sourceOfflineRepo = SqlDelightOfflineAssetRepository(sourceDb)
            val sourceAssetStore = JvmOfflineAssetStore(
                assetsDirectory = sourceAssetsDir,
                repository = sourceOfflineRepo,
                operationRepository = SqlDelightAssetOperationRepository(sourceDb),
            )
            val sourceProfileRepo = SqlDelightDownloadProfileRepository(sourceDb)
            val sourcePlaybackStore = SqlDelightPlaybackStore(sourceDb)
            val sourceStudyRepo = SqlDelightStudyRepository(sourceDb)

            val videoFile = File(sourceDownloads, "lecture-01.mkv").apply {
                writeBytes("FAKE-MKV-PAYLOAD-STREAM".encodeToByteArray())
            }
            val subRelative = "e-1/sub-1.srt"
            val subFile = File(sourceAssetsDir, subRelative).apply {
                parentFile.mkdirs()
                writeText(
                    "1\n00:00:05,000 --> 00:00:09,000\nPortable cue roundtrip alpha\n",
                    Charsets.UTF_8,
                )
            }
            val subSha = sha256(subFile)
            val videoRev = MediaRevision(
                sizeBytes = videoFile.length(),
                lastModifiedEpochMillis = videoFile.lastModified(),
                contentSha256 = sha256(videoFile),
            )

            val sourceEntry = LibraryEntry(
                id = "e-1",
                title = "System Design Lecture",
                kind = MediaKind.VIDEO,
                filePath = videoFile.absolutePath,
                author = "Prof. Ada",
                source = "youtube",
                createdAtEpochMillis = 1_700_000_001_000L,
                sizeBytes = videoFile.length(),
                tags = listOf("systems", "architecture"),
                mediaRevision = videoRev,
            )
            sourceRepo.insertLibraryEntry(sourceEntry)
            sourceRepo.insertCollection(Collection(id = "col-1", name = "Courses"))
            sourceRepo.addEntryToCollection(collectionId = "col-1", entryId = "e-1")
            sourceRepo.insertSmartCollection(
                SmartCollection(id = "sc-1", name = "Recent Videos", query = "Design", kind = MediaKind.VIDEO),
            )
            sourceOfflineRepo.insertAsset(
                OfflineAsset(
                    id = "asset-sub-1",
                    entryId = "e-1",
                    kind = AssetKind.SUBTITLE,
                    language = "en",
                    sourceType = "import",
                    relativeLocator = subRelative,
                    byteCount = subFile.length(),
                    sha256 = subSha,
                    state = AssetState.VERIFIED,
                    isRequired = false,
                    isPinned = true,
                    mediaRevision = videoRev,
                    createdAtEpochMillis = 1_700_000_002_000L,
                    lastVerifiedEpochMillis = 1_700_000_002_000L,
                ),
            )
            sourcePlaybackStore.savePosition(
                videoFile.absolutePath,
                PlaybackCheckpoint(42_000L, 120_000L, "e-1"),
                "e-1",
            )
            sourceStudyRepo.upsertNote(
                StudyNote(
                    id = "note-1",
                    entryId = "e-1",
                    timestampMillis = 5_000L,
                    endMillis = 9_000L,
                    body = "Review consensus notes",
                    mediaRevision = videoRev,
                    createdAtEpochMillis = 100L,
                    updatedAtEpochMillis = 100L,
                ),
            )
            sourceStudyRepo.upsertBookmark(
                StudyBookmark(
                    id = "bm-1",
                    entryId = "e-1",
                    timestampMillis = 42_000L,
                    label = "Checkpoint",
                    mediaRevision = videoRev,
                    createdAtEpochMillis = 101L,
                    updatedAtEpochMillis = 101L,
                ),
            )
            sourceStudyRepo.upsertSavedRange(
                SavedStudyRange(
                    id = "rng-1",
                    entryId = "e-1",
                    startMillis = 5_000L,
                    endMillis = 15_000L,
                    label = "Key segment",
                    mediaRevision = videoRev,
                    createdAtEpochMillis = 102L,
                    updatedAtEpochMillis = 102L,
                ),
            )
            sourceStudyRepo.saveEntryStudyState(
                EntryStudyState(entryId = "e-1", isStudied = true, studiedAtEpochMillis = 200L, updatedAtEpochMillis = 200L),
            )
            sourceProfileRepo.saveCustomProfile(
                DownloadProfile(
                    id = "prof-custom-1",
                    name = "Course 720p",
                    isBuiltIn = false,
                    kind = MediaKind.VIDEO,
                    maxHeight = 720,
                    preferMp4Avc = true,
                ),
            )

            val archiveFile = File(tempRoot, "transfer/portable.openyt-backup.zip").apply {
                parentFile.mkdirs()
            }
            val sourceRuntime = createPortableBackupRuntime(
                database = sourceDb,
                stagingRoot = File(sourceRoot, "staging"),
                ownedAssetsRoot = sourceAssetsDir,
                streamBridge = FilePortableStreamBridge(),
                defaultDestinationRoot = { sourceDownloads.absolutePath },
                offlineAssetStore = sourceAssetStore,
                subtitleIndexRepository = SqlDelightSubtitleIndexRepository(sourceDb),
                appVersion = "1.0.0-test",
            )
            val exportReceipt = sourceRuntime.controller.exportBackup(
                archiveLocator = archiveFile.absolutePath,
                options = PortableExportOptions(includeMediaFiles = true, includeOfflineAssets = true),
            ) {}.getOrThrow()
            assertEquals(1, exportReceipt.entryCount)
            assertEquals(1, exportReceipt.mediaFileCount)
            assertEquals(1, exportReceipt.offlineAssetCount)

            // Target simulates a clean device importing over a non-seekable SAF stream pipe
            val targetRoot = File(tempRoot, "target").apply { mkdirs() }
            val targetDownloads = File(targetRoot, "downloads").apply { mkdirs() }
            val targetAssetsDir = File(targetRoot, "assets").apply { mkdirs() }
            val targetDb = createOpenYtDatabase(File(targetRoot, "openyt.db").absolutePath)
            val targetRepo = SqlDelightDownloadRepository(targetDb)
            targetRepo.insertLibraryEntry(
                LibraryEntry(
                    id = "e-1",
                    title = "Existing Preinstalled Video",
                    kind = MediaKind.VIDEO,
                    filePath = File(targetDownloads, "existing.mkv").apply { writeText("keep-me") }.absolutePath,
                    createdAtEpochMillis = 1_699_000_000_000L,
                ),
            )

            val fileBridge = FilePortableStreamBridge()
            val safSimulatedBridge = object : PortableStreamBridge by fileBridge {
                override fun openInputStream(locator: String): InputStream {
                    val delegate = fileBridge.openInputStream(locator)
                    return object : FilterInputStream(delegate) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            return super.read(b, off, minOf(len, 137))
                        }
                    }
                }
            }
            val targetOfflineRepo = SqlDelightOfflineAssetRepository(targetDb)
            val targetAssetStore = JvmOfflineAssetStore(
                assetsDirectory = targetAssetsDir,
                repository = targetOfflineRepo,
                operationRepository = SqlDelightAssetOperationRepository(targetDb),
            )
            val targetSubIndexRepo = SqlDelightSubtitleIndexRepository(targetDb)
            val targetRuntime = createPortableBackupRuntime(
                database = targetDb,
                stagingRoot = File(targetRoot, "staging"),
                ownedAssetsRoot = targetAssetsDir,
                streamBridge = safSimulatedBridge,
                defaultDestinationRoot = { targetDownloads.absolutePath },
                offlineAssetStore = targetAssetStore,
                subtitleIndexRepository = targetSubIndexRepo,
                appVersion = "1.0.0-test",
            )

            val plan = targetRuntime.controller.prepareRestorePlan(
                archiveLocator = archiveFile.absolutePath,
                destinationRoot = targetDownloads.absolutePath,
                options = PortableRestoreOptions(conflictPolicy = PortableRestoreConflictPolicy.IMPORT_AS_NEW),
            ) {}.getOrThrow()
            assertEquals(exportReceipt.archiveSha256, plan.archiveSha256)

            val restoreReceipt = targetRuntime.controller.executeRestore(
                plan = plan,
                confirmedArchiveSha256 = plan.archiveSha256,
            ) {}.getOrThrow()
            assertEquals(1, restoreReceipt.entriesRestored)
            assertEquals(1, restoreReceipt.subtitlesReindexed)

            val targetEntries = targetRepo.loadLibrary()
            assertEquals(2, targetEntries.size)
            val originalKept = targetEntries.first { it.id == "e-1" }
            assertEquals("Existing Preinstalled Video", originalKept.title)

            val restoredEntry = targetEntries.first { it.title == "System Design Lecture" }
            assertNotEquals("e-1", restoredEntry.id)
            assertTrue(File(restoredEntry.filePath).isFile)
            assertEquals(setOf("systems", "architecture"), restoredEntry.tags.toSet())

            val restoredBundle = SqlDelightStudyRepository(targetDb).loadEntryStudyBundle(restoredEntry.id)
            assertEquals(1, restoredBundle.notes.size)
            assertEquals("Review consensus notes", restoredBundle.notes.single().body)
            assertTrue(restoredBundle.studyState.isStudied)

            val restoredCheckpoint = SqlDelightPlaybackStore(targetDb)
                .position(restoredEntry.filePath, restoredEntry.id)
            assertNotNull(restoredCheckpoint)
            assertEquals(42_000L, restoredCheckpoint.positionMillis)

            val profiles = SqlDelightDownloadProfileRepository(targetDb).loadProfiles()
            assertTrue(profiles.any { it.name == "Course 720p" })

            val searchUseCase = TimedSearchUseCase(SqlDelightTimedSearchRepository(targetDb))
            val result = searchUseCase.execute("roundtrip alpha") as TimedSearchResult.Success
            assertEquals(1, result.items.size)
            assertEquals(restoredEntry.id, result.items.single().entryId)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(file.readBytes())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
