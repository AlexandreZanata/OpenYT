package com.openyt.android

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.core.application.MediaHealthStatus
import com.openyt.core.application.PlaybackCheckpoint
import com.openyt.core.application.RelinkContentMode
import com.openyt.core.domain.Collection
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.StudyNote
import com.openyt.data.AndroidMediaHealthInspector
import com.openyt.data.AndroidPortableStreamBridge
import com.openyt.data.AndroidRecoveryCandidateScanner
import com.openyt.data.SqlDelightDownloadRepository
import com.openyt.data.SqlDelightOfflineAssetRepository
import com.openyt.data.SqlDelightPlaybackStore
import com.openyt.data.SqlDelightStudyRepository
import com.openyt.data.SqlDelightSubtitleIndexRepository
import com.openyt.data.createLibraryRecoveryRuntime
import com.openyt.data.createOpenYtDatabase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLibraryRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "openyt-android-recovery-test.db"
    private val workRoot = File(context.getExternalFilesDir(null), "downloads/android-recovery-test")

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
        workRoot.deleteRecursively()
    }

    @Test
    fun healthReportAndSafCopyRelinkPreserveIdentityContextAndOriginals() = runBlocking {
        workRoot.mkdirs()
        val ownedDir = File(workRoot, "owned").apply { mkdirs() }
        val stagingDir = File(workRoot, "staging").apply { mkdirs() }
        val safSourceDir = File(workRoot, "external-picked").apply { mkdirs() }

        val payloadBytes = "ANDROID-RECOVERY-EXACT-VIDEO-BYTES-001".encodeToByteArray()
        val payloadSha = sha256(payloadBytes)
        val originalPath = File(ownedDir, "old-location.mp4").absolutePath
        val origRev = MediaRevision(payloadBytes.size.toLong(), 1_700_000_000_000L, payloadSha)

        val healthyFile = File(ownedDir, "healthy.mp4").apply { writeBytes(payloadBytes) }
        val healthyRev = MediaRevision(healthyFile.length(), healthyFile.lastModified(), payloadSha)

        val changedFile = File(ownedDir, "changed.mp4").apply {
            writeBytes("MODIFIED-CONTENT-V2".encodeToByteArray())
        }
        val expectedBeforeChange = MediaRevision(payloadBytes.size.toLong(), 1_699_000_000_000L, payloadSha)

        val db = createOpenYtDatabase(context, dbName)
        val downloadRepo = SqlDelightDownloadRepository(db)
        val offlineRepo = SqlDelightOfflineAssetRepository(db)
        val studyRepo = SqlDelightStudyRepository(db)
        val playbackStore = SqlDelightPlaybackStore(db)

        downloadRepo.insertLibraryEntry(
            LibraryEntry("e-missing", "Moved Android Video", MediaKind.VIDEO, originalPath, payloadBytes.size.toLong(), mediaRevision = origRev),
        )
        downloadRepo.insertLibraryEntry(
            LibraryEntry("e-perm", "Revoked SAF Entry", MediaKind.VIDEO, "content://com.android.externalstorage.documents/document/primary%3Amissing.mp4", 1024L),
        )
        downloadRepo.insertLibraryEntry(
            LibraryEntry("e-changed", "Changed File", MediaKind.VIDEO, changedFile.absolutePath, payloadBytes.size.toLong(), mediaRevision = expectedBeforeChange),
        )
        downloadRepo.insertLibraryEntry(
            LibraryEntry("e-ok", "Healthy File", MediaKind.VIDEO, healthyFile.absolutePath, healthyFile.length(), mediaRevision = healthyRev),
        )

        downloadRepo.insertCollection(Collection("col-r", "Recovered Lectures"))
        downloadRepo.addEntryToCollection("col-r", "e-missing")
        playbackStore.savePosition(originalPath, PlaybackCheckpoint(22_000L, 80_000L, "e-missing"), "e-missing")
        studyRepo.upsertNote(
            StudyNote("n-r1", "e-missing", 7_000L, 12_000L, "Preserved Android study note", origRev, 50L, 50L),
        )

        val runtime = createLibraryRecoveryRuntime(
            database = db,
            downloadRepository = downloadRepo,
            offlineAssetRepository = offlineRepo,
            subtitleIndexRepository = SqlDelightSubtitleIndexRepository(db),
            studyRepository = studyRepo,
            stagingRoot = stagingDir,
            ownedRecoveryRootProvider = { ownedDir.absolutePath },
            streamBridge = AndroidPortableStreamBridge(context),
            healthInspector = AndroidMediaHealthInspector(context),
            candidateSourcePort = AndroidRecoveryCandidateScanner(context),
        )

        // 1. Read-only health accurately distinguishes MISSING, PERMISSION_LOST, CHANGED_CONTENT, AVAILABLE
        val report = runtime.controller.analyzeHealth(verifyHashesWhenUncertain = true)
        val byId = report.entries.associateBy { it.entry.id }
        assertEquals(MediaHealthStatus.MISSING, byId.getValue("e-missing").status)
        assertEquals(MediaHealthStatus.PERMISSION_LOST, byId.getValue("e-perm").status)
        assertEquals(MediaHealthStatus.CHANGED_CONTENT, byId.getValue("e-changed").status)
        assertEquals(MediaHealthStatus.AVAILABLE, byId.getValue("e-ok").status)

        // 2. Relink via real Android FileProvider content:// URI (COPY_SAF_TO_OWNED)
        val pickedFile = File(safSourceDir, "recovered-clip.mp4").apply { writeBytes(payloadBytes) }
        val pickedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pickedFile)

        val plan = runtime.controller.verifyAndBuildManualPlan(
            entry = byId.getValue("e-missing").entry,
            manualLocator = pickedUri.toString(),
        ).getOrThrow()
        assertEquals(RelinkContentMode.IDENTICAL_CONTENT, plan.contentMode)
        assertFalse(plan.requiresManualConfirmation)

        val receipt = runtime.controller.executeRelink(plan, confirmedContentChange = false).getOrThrow()
        assertTrue(receipt.copiedFromSaf)
        assertFalse(receipt.finalPath.startsWith("content://"))
        assertTrue(File(receipt.finalPath).isFile)
        assertEquals(payloadSha, sha256(File(receipt.finalPath).readBytes()))

        // 3. Reopen Android SQLite DB & verify entry identity, collection, note, checkpoint, and intact source
        val reopenedDb = createOpenYtDatabase(context, dbName)
        val entries = SqlDelightDownloadRepository(reopenedDb).loadLibrary()
        assertEquals(4, entries.size)
        val relinked = entries.single { it.id == "e-missing" }
        assertEquals(receipt.finalPath, relinked.filePath)
        assertEquals(setOf("e-missing"), SqlDelightDownloadRepository(reopenedDb).loadCollectionMembership()["col-r"])

        val reopenedCheckpoint = SqlDelightPlaybackStore(reopenedDb).position(relinked.filePath, "e-missing")
        assertNotNull(reopenedCheckpoint)
        assertEquals(22_000L, reopenedCheckpoint!!.positionMillis)

        val reopenedBundle = SqlDelightStudyRepository(reopenedDb).loadEntryStudyBundle("e-missing")
        assertEquals("Preserved Android study note", reopenedBundle.notes.single().body)
        assertFalse(reopenedBundle.hasStaleAnchors)
        assertTrue(pickedFile.isFile)
        assertEquals(payloadSha, sha256(pickedFile.readBytes()))
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
