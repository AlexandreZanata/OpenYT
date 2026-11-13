package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.PortableBackupCodec
import com.openyt.core.application.PortableBackupExportService
import com.openyt.core.application.PortableBackupFormat
import com.openyt.core.application.PortableExportOptions
import com.openyt.core.application.PortableOperationStatus
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.Collection
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.StudyNote
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortableExportLocalTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun exportsRealFilesManifestAndJournalIntoPortableZipArchive() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OpenYtDatabase.Schema.create(driver)
        val db = OpenYtDatabase(driver)

        val mediaDir = tempFolder.newFolder("media")
        val assetsRoot = tempFolder.newFolder("owned-assets")
        val stagingRoot = tempFolder.newFolder("staging")
        val exportOut = File(tempFolder.root, "library-backup.openyt-backup.zip")

        val videoFile = File(mediaDir, "lesson.mp4").apply {
            writeBytes(ByteArray(32_768) { (it % 251).toByte() })
        }
        val subtitleDir = File(assetsRoot, "e1").apply { mkdirs() }
        val subtitleFile = File(subtitleDir, "sub.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:03,000\nOlá mundo\n")
        }

        val mediaSha = sha256(videoFile.readBytes())
        val subSha = sha256(subtitleFile.readBytes())
        val revision = MediaRevision(videoFile.length(), videoFile.lastModified(), mediaSha)

        val repo = SqlDelightDownloadRepository(db)
        val studyRepo = SqlDelightStudyRepository(db)
        val assetRepo = SqlDelightOfflineAssetRepository(db)
        val playbackStore = SqlDelightPlaybackStore(db)
        val journalRepo = SqlDelightPortableOperationRepository(db)

        repo.insertLibraryEntry(
            LibraryEntry(
                id = "e1",
                title = "Aula 1",
                kind = MediaKind.VIDEO,
                filePath = videoFile.absolutePath,
                sizeBytes = videoFile.length(),
                createdAtEpochMillis = 100L,
                source = "youtube",
                tags = listOf("portuguese"),
                mediaRevision = revision,
            ),
        )
        repo.insertCollection(Collection("c1", "Estudos"))
        repo.addEntryToCollection("c1", "e1")
        playbackStore.savePosition(videoFile.absolutePath, PlaybackCheckpoint(12_345L, 60_000L, "e1"), "e1")
        studyRepo.upsertNote(StudyNote("n1", "e1", 2_000L, null, "Importante", revision, 100L, 100L))
        studyRepo.saveEntryStudyState(EntryStudyState("e1", isStudied = true, studiedAtEpochMillis = 120L, updatedAtEpochMillis = 120L))
        assetRepo.insertAsset(
            OfflineAsset(
                id = "a1",
                entryId = "e1",
                kind = AssetKind.SUBTITLE,
                language = "pt",
                sourceType = "manual",
                relativeLocator = "e1/sub.srt",
                byteCount = subtitleFile.length(),
                sha256 = subSha,
                state = AssetState.VERIFIED,
                isRequired = false,
                isPinned = true,
                mediaRevision = revision,
                createdAtEpochMillis = 110L,
            ),
        )

        val exportService = PortableBackupExportService(
            snapshotSource = SqlDelightPortableBackupSnapshotSource(db),
            writerPort = JvmPortableArchiveWriter(stagingRoot),
            storageAccess = JvmPortableStorageAccessPort(stagingRoot),
            journalRepository = journalRepo,
            ownedAssetLocatorResolver = { asset -> File(assetsRoot, asset.relativeLocator).canonicalPath },
            storageReserveBytes = 1024L,
            nowProvider = { System.currentTimeMillis() },
        )

        val receipt = exportService.exportBackup(
            archiveLocator = exportOut.absolutePath,
            options = PortableExportOptions(includeMediaFiles = true, includeOfflineAssets = true),
            appVersion = "4.1.0",
        ).getOrThrow()

        assertTrue(exportOut.isFile)
        assertEquals(1, receipt.entryCount)
        assertEquals(1, receipt.mediaFileCount)
        assertEquals(1, receipt.offlineAssetCount)
        assertEquals(PortableOperationStatus.SUCCEEDED, journalRepo.getOperation(receipt.operationId)?.status)

        // Read back raw ZIP and verify manifest + blob bytes
        val zipEntries = mutableMapOf<String, ByteArray>()
        ZipInputStream(exportOut.inputStream().buffered()).use { zipIn ->
            while (true) {
                val entry = zipIn.nextEntry ?: break
                zipEntries[entry.name] = zipIn.readBytes()
                zipIn.closeEntry()
            }
        }
        val manifestBytes = zipEntries[PortableBackupFormat.MANIFEST_ENTRY_NAME]!!
        val manifest = PortableBackupCodec.decode(manifestBytes).getOrThrow()
        assertEquals(mediaSha, manifest.entries.single().primaryMedia?.sha256)
        assertEquals(subSha, manifest.entries.single().offlineAssets.single().blob?.sha256)
        assertEquals(videoFile.readBytes().toList(), zipEntries[manifest.entries.single().primaryMedia?.archivePath]!!.toList())
        assertEquals(subtitleFile.readBytes().toList(), zipEntries[manifest.entries.single().offlineAssets.single().blob?.archivePath]!!.toList())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
