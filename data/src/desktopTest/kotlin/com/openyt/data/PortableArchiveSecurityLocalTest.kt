package com.openyt.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.openyt.core.application.PortableBackupCodec
import com.openyt.core.application.PortableBackupException
import com.openyt.core.application.PortableBackupFailure
import com.openyt.core.application.PortableBackupFormat
import com.openyt.core.application.PortableBackupManifest
import com.openyt.core.application.PortableBlobRef
import com.openyt.core.application.PortableEntryRecord
import com.openyt.core.application.PortableRestorePlanner
import com.openyt.core.domain.MediaKind
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortableArchiveSecurityLocalTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun rejectsRealHostileZipsTraversalBombOversizedManifestTamperAndExtraEntries() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OpenYtDatabase.Schema.create(driver)
        val db = OpenYtDatabase(driver)

        val stagingRoot = tempFolder.newFolder("staging")
        val destRoot = tempFolder.newFolder("dest")
        val victimFile = File(tempFolder.root, "victim.txt").apply { writeText("DO_NOT_OVERWRITE") }

        fun makePlanner(maxExpandedBytes: Long = 8L * 1024 * 1024) = PortableRestorePlanner(
            snapshotSource = SqlDelightPortableBackupSnapshotSource(db),
            readerPort = JvmPortableArchiveReader(),
            storageAccess = JvmPortableStorageAccessPort(stagingRoot),
            journalRepository = SqlDelightPortableOperationRepository(db),
            storageReserveBytes = 1024L,
            maxExpandedArchiveBytes = maxExpandedBytes,
            nowProvider = { System.currentTimeMillis() },
        )

        // 1. Zip slip traversal archive
        val traversalZip = File(tempFolder.root, "traversal.zip")
        ZipOutputStream(traversalZip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../../victim.txt"))
            z.write("PWNED".toByteArray())
            z.closeEntry()
        }
        val travErr = makePlanner().prepareRestorePlan(traversalZip.absolutePath, destRoot.absolutePath)
            .exceptionOrNull() as PortableBackupException
        assertTrue(travErr.failure is PortableBackupFailure.SecurityViolation)
        assertEquals("DO_NOT_OVERWRITE", victimFile.readText())
        assertEquals(0, stagingRoot.listFiles()?.size ?: 0)

        // 2. Oversized manifest (> 16 MiB)
        val hugeManifestZip = File(tempFolder.root, "huge-manifest.zip")
        ZipOutputStream(hugeManifestZip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry(PortableBackupFormat.MANIFEST_ENTRY_NAME))
            val chunk = ByteArray(1024 * 1024) { ' '.code.toByte() }
            repeat(17) { z.write(chunk) }
            z.closeEntry()
        }
        val hugeErr = makePlanner().prepareRestorePlan(hugeManifestZip.absolutePath, destRoot.absolutePath)
            .exceptionOrNull() as PortableBackupException
        assertTrue(hugeErr.failure is PortableBackupFailure.SizeLimitExceeded)
        assertEquals(0, stagingRoot.listFiles()?.size ?: 0)

        // 3. Decompression bomb exceeding staging cap in stream
        val bombZip = File(tempFolder.root, "bomb.zip")
        ZipOutputStream(bombZip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("media/e1/bomb.mp4"))
            val zeros = ByteArray(1024 * 1024)
            repeat(4) { z.write(zeros) } // 4 MiB uncompressed -> tiny compressed ZIP
            z.closeEntry()
        }
        val bombErr = makePlanner(maxExpandedBytes = 512L * 1024L)
            .prepareRestorePlan(bombZip.absolutePath, destRoot.absolutePath)
            .exceptionOrNull() as PortableBackupException
        assertTrue(bombErr.failure is PortableBackupFailure.SizeLimitExceeded)
        assertEquals(0, stagingRoot.listFiles()?.size ?: 0)

        // 4. Tampered media bytes vs manifest SHA-256
        val goodPayload = "valid media bytes".toByteArray()
        val tamperedPayload = "CORRUPT media!".toByteArray()
        val manifest = PortableBackupManifest(
            bundleId = "tamper-test",
            createdAtEpochMillis = 1L,
            entries = listOf(
                PortableEntryRecord(
                    exportId = "e1",
                    title = "Tampered",
                    kind = MediaKind.AUDIO,
                    primaryMedia = PortableBlobRef(
                        archivePath = "media/e1/audio.m4a",
                        byteCount = goodPayload.size.toLong(),
                        sha256 = sha256(goodPayload),
                        originalFileName = "audio.m4a",
                    ),
                ),
            ),
        )
        val manifestJson = PortableBackupCodec.encode(manifest)
        val tamperZip = File(tempFolder.root, "tampered.zip")
        ZipOutputStream(tamperZip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry(PortableBackupFormat.MANIFEST_ENTRY_NAME))
            z.write(manifestJson.toByteArray(StandardCharsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("media/e1/audio.m4a"))
            z.write(tamperedPayload)
            z.closeEntry()
        }
        val tamperErr = makePlanner().prepareRestorePlan(tamperZip.absolutePath, destRoot.absolutePath)
            .exceptionOrNull() as PortableBackupException
        assertTrue(tamperErr.failure is PortableBackupFailure.ChecksumMismatch)
        assertEquals(0, stagingRoot.listFiles()?.size ?: 0)

        // 5. Case-insensitive collision in ZIP entries
        val dupZip = File(tempFolder.root, "dup-case.zip")
        ZipOutputStream(dupZip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("media/e1/Track.mp3"))
            z.write(goodPayload)
            z.closeEntry()
            z.putNextEntry(ZipEntry("media/e1/track.mp3"))
            z.write(goodPayload)
            z.closeEntry()
        }
        val dupErr = makePlanner().prepareRestorePlan(dupZip.absolutePath, destRoot.absolutePath)
            .exceptionOrNull() as PortableBackupException
        assertTrue(dupErr.failure is PortableBackupFailure.SecurityViolation)
        assertEquals(0, stagingRoot.listFiles()?.size ?: 0)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
