package com.openyt.android

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PortableBackupException
import com.openyt.core.application.PortableBackupFailure
import com.openyt.core.application.PortableExportOptions
import com.openyt.core.application.PortableRestoreConflictPolicy
import com.openyt.core.application.PortableRestoreOptions
import com.openyt.core.application.TimedSearchUseCase
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.Collection
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.StudyNote
import com.openyt.core.domain.TimedSearchResult
import com.openyt.data.AndroidPortableStreamBridge
import com.openyt.data.JvmOfflineAssetStore
import com.openyt.data.SqlDelightAssetOperationRepository
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightOfflineAssetRepository
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.createOpenYtDatabase
import com.openyt.data.createPortableBackupRuntime
import com.openyt.data.search.SqlDelightTimedSearchRepository
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPortableBackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sourceDbName = "openyt-android-backup-src.db"
    private val targetDbName = "openyt-android-backup-dst.db"
    private val workRoot = File(context.getExternalFilesDir(null), "downloads/android-backup-test")

    @After
    fun tearDown() {
        context.deleteDatabase(sourceDbName)
        context.deleteDatabase(targetDbName)
        workRoot.deleteRecursively()
    }

    @Test
    fun safContentUriExportAndRestorePreservesMediaContextAndFtsSearch() = runBlocking {
        workRoot.mkdirs()
        val srcDownloads = File(workRoot, "src-downloads").apply { mkdirs() }
        val srcAssets = File(workRoot, "src-assets").apply { mkdirs() }
        val srcStaging = File(workRoot, "src-staging").apply { mkdirs() }

        val dstDownloads = File(workRoot, "dst-downloads").apply { mkdirs() }
        val dstAssets = File(workRoot, "dst-assets").apply { mkdirs() }
        val dstStaging = File(workRoot, "dst-staging").apply { mkdirs() }

        val srcDb = createOpenYtDatabase(context, sourceDbName)
        val srcRepo = SqlDelightDownloadRepository(srcDb)
        val srcOfflineRepo = SqlDelightOfflineAssetRepository(srcDb)
        val srcAssetStore = JvmOfflineAssetStore(srcAssets, srcOfflineRepo, SqlDelightAssetOperationRepository(srcDb))
        val srcStudy = SqlDelightStudyRepository(srcDb)
        val srcPlayback = SqlDelightPlaybackStore(srcDb)

        val videoFile = File(srcDownloads, "android-lesson.mp4").apply {
            writeBytes("ANDROID-SAF-PORTABLE-STREAM-PAYLOAD".encodeToByteArray())
        }
        val videoSha = sha256(videoFile)
        val rev = MediaRevision(videoFile.length(), videoFile.lastModified(), videoSha)

        val subFile = File(srcAssets, "e-a1/sub.srt").apply {
            parentFile?.mkdirs()
            writeText("1\n00:00:02,000 --> 00:00:05,000\nAndroid portable search target\n", Charsets.UTF_8)
        }
        val subSha = sha256(subFile)

        srcRepo.insertLibraryEntry(
            LibraryEntry("e-a1", "Android Physics", MediaKind.VIDEO, videoFile.absolutePath, videoFile.length(), mediaRevision = rev, tags = listOf("android")),
        )
        srcRepo.insertCollection(Collection("col-a", "Mobile Study"))
        srcRepo.addEntryToCollection("col-a", "e-a1")
        srcOfflineRepo.insertAsset(
            OfflineAsset(
                id = "asset-a1",
                entryId = "e-a1",
                kind = AssetKind.SUBTITLE,
                language = "en",
                sourceType = "import",
                relativeLocator = "e-a1/sub.srt",
                byteCount = subFile.length(),
                sha256 = subSha,
                state = AssetState.VERIFIED,
                isRequired = false,
                isPinned = true,
                mediaRevision = rev,
                createdAtEpochMillis = 100L,
                lastVerifiedEpochMillis = 100L,
            ),
        )
        srcPlayback.savePosition(videoFile.absolutePath, PlaybackCheckpoint(12_345L, 60_000L, "e-a1"), "e-a1")
        srcStudy.upsertNote(StudyNote("note-a1", "e-a1", 3_000L, 6_000L, "Android SAF note", rev, 50L, 50L))
        srcStudy.saveEntryStudyState(EntryStudyState("e-a1", isStudied = true, studiedAtEpochMillis = 60L, updatedAtEpochMillis = 60L))

        val archiveFile = File(workRoot, "portable-saf.openyt-backup.zip").apply { createNewFile() }
        val archiveContentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archiveFile)
        val streamBridge = AndroidPortableStreamBridge(context)

        val srcRuntime = createPortableBackupRuntime(
            database = srcDb,
            stagingRoot = srcStaging,
            ownedAssetsRoot = srcAssets,
            streamBridge = streamBridge,
            defaultDestinationRoot = { srcDownloads.absolutePath },
            offlineAssetStore = srcAssetStore,
            subtitleIndexRepository = SqlDelightSubtitleIndexRepository(srcDb),
            appVersion = "android-test",
        )
        val exportReceipt = srcRuntime.controller.exportBackup(
            archiveLocator = archiveContentUri.toString(),
            options = PortableExportOptions(includeMediaFiles = true, includeOfflineAssets = true),
        ) {}.getOrThrow()
        assertEquals(1, exportReceipt.entryCount)
        assertTrue(archiveFile.length() > 0L)

        // Pre-seed collision in destination DB to confirm ID remapping + isolation
        val dstDb = createOpenYtDatabase(context, targetDbName)
        val dstRepo = SqlDelightDownloadRepository(dstDb)
        val existingPath = File(dstDownloads, "keep-existing.mp4").apply { writeText("existing") }.absolutePath
        dstRepo.insertLibraryEntry(
            LibraryEntry("e-a1", "Existing Preinstalled", MediaKind.VIDEO, existingPath, 8L),
        )
        val dstAssetStore = JvmOfflineAssetStore(
            dstAssets,
            SqlDelightOfflineAssetRepository(dstDb),
            SqlDelightAssetOperationRepository(dstDb),
        )
        val dstRuntime = createPortableBackupRuntime(
            database = dstDb,
            stagingRoot = dstStaging,
            ownedAssetsRoot = dstAssets,
            streamBridge = streamBridge,
            defaultDestinationRoot = { dstDownloads.absolutePath },
            offlineAssetStore = dstAssetStore,
            subtitleIndexRepository = SqlDelightSubtitleIndexRepository(dstDb),
            appVersion = "android-test",
        )

        val plan = dstRuntime.controller.prepareRestorePlan(
            archiveLocator = archiveContentUri.toString(),
            destinationRoot = dstDownloads.absolutePath,
            options = PortableRestoreOptions(PortableRestoreConflictPolicy.IMPORT_AS_NEW),
        ) {}.getOrThrow()
        val restoreReceipt = dstRuntime.controller.executeRestore(plan, plan.archiveSha256) {}.getOrThrow()
        assertEquals(1, restoreReceipt.entriesRestored)
        assertEquals(1, restoreReceipt.subtitlesReindexed)

        val reopenedDst = createOpenYtDatabase(context, targetDbName)
        val entries = SqlDelightDownloadRepository(reopenedDst).loadLibrary()
        assertEquals(2, entries.size)
        val restored = entries.first { it.title == "Android Physics" }
        assertNotEquals("e-a1", restored.id)
        assertTrue(File(restored.filePath).isFile)
        assertEquals(videoSha, sha256(File(restored.filePath)))

        val bundle = SqlDelightStudyRepository(reopenedDst).loadEntryStudyBundle(restored.id)
        assertEquals("Android SAF note", bundle.notes.single().body)
        assertTrue(bundle.studyState.isStudied)

        val fts = TimedSearchUseCase(SqlDelightTimedSearchRepository(reopenedDst))
            .execute("portable search target") as TimedSearchResult.Success
        assertEquals(restored.id, fts.items.single().entryId)
    }

    @Test
    fun rejectsHostilePathTraversalArchiveWithoutMutatingAndroidDb() = runBlocking {
        workRoot.mkdirs()
        val downloads = File(workRoot, "hostile-downloads").apply { mkdirs() }
        val assets = File(workRoot, "hostile-assets").apply { mkdirs() }
        val staging = File(workRoot, "hostile-staging").apply { mkdirs() }

        val hostileZip = File(workRoot, "hostile.zip")
        ZipOutputStream(FileOutputStream(hostileZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../escape.mp4"))
            zos.write("malicious".encodeToByteArray())
            zos.closeEntry()
        }
        val hostileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", hostileZip)

        val db = createOpenYtDatabase(context, targetDbName)
        val assetStore = JvmOfflineAssetStore(
            assets,
            SqlDelightOfflineAssetRepository(db),
            SqlDelightAssetOperationRepository(db),
        )
        val runtime = createPortableBackupRuntime(
            database = db,
            stagingRoot = staging,
            ownedAssetsRoot = assets,
            streamBridge = AndroidPortableStreamBridge(context),
            defaultDestinationRoot = { downloads.absolutePath },
            offlineAssetStore = assetStore,
            subtitleIndexRepository = SqlDelightSubtitleIndexRepository(db),
            appVersion = "android-test",
        )

        val result = runtime.controller.prepareRestorePlan(
            archiveLocator = hostileUri.toString(),
            destinationRoot = downloads.absolutePath,
            options = PortableRestoreOptions(),
        ) {}
        assertTrue(result.isFailure)
        val failure = (result.exceptionOrNull() as? PortableBackupException)?.failure
        assertNotNull(failure)
        assertTrue(
            failure is PortableBackupFailure.SecurityViolation ||
                failure is PortableBackupFailure.InvalidManifest ||
                failure is PortableBackupFailure.IoError,
        )
        assertTrue(SqlDelightDownloadRepository(db).loadLibrary().isEmpty())
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
