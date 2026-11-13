package com.openyt.data

import com.openyt.core.application.LibraryHealthAnalyzer
import com.openyt.core.application.MediaHealthStatus
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class JvmMediaHealthInspectorTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun distinguishesAvailableMissingChangedContentAndPermissionLostOnRealFilesystem() = runBlocking {
        val dir = tempFolder.newFolder("media")
        val okFile = File(dir, "ok.mp4").apply { writeBytes("ORIGINAL".encodeToByteArray()) }
        val changedFile = File(dir, "changed.mp4").apply { writeBytes("AFTER_EDIT".encodeToByteArray()) }
        val missingFile = File(dir, "missing.mp4")

        val okRev = MediaRevision(okFile.length(), okFile.lastModified(), sha256(okFile.readBytes()))
        val origBeforeBytes = "ORIGINAL_X".encodeToByteArray()
        val changedOrigRev = MediaRevision(
            sizeBytes = origBeforeBytes.size.toLong(),
            lastModifiedEpochMillis = changedFile.lastModified() - 5_000L,
            contentSha256 = sha256(origBeforeBytes),
        )

        val unreadableFile = File(dir, "locked.mp4").apply { writeBytes("LOCKED".encodeToByteArray()) }
        val lockedSupported = unreadableFile.setReadable(false)
        // Skip unreadable case when running as UID 0 in root containers where POSIX chmod 000 stays readable.
        val treatLockedAsUnreadable = lockedSupported && !unreadableFile.canRead()

        try {
            val entries = buildList {
                add(LibraryEntry("ok", "OK", MediaKind.VIDEO, okFile.absolutePath, sizeBytes = okFile.length(), mediaRevision = okRev))
                add(LibraryEntry("changed", "Changed", MediaKind.VIDEO, changedFile.absolutePath, sizeBytes = origBeforeBytes.size.toLong(), mediaRevision = changedOrigRev))
                add(LibraryEntry("missing", "Missing", MediaKind.VIDEO, missingFile.absolutePath, sizeBytes = 100L))
                if (treatLockedAsUnreadable) {
                    add(LibraryEntry("locked", "Locked", MediaKind.VIDEO, unreadableFile.absolutePath, sizeBytes = unreadableFile.length()))
                }
            }

            val report = LibraryHealthAnalyzer(JvmMediaHealthInspector()).analyze(entries, verifyHashesWhenUncertain = true)
            val byId = report.entries.associateBy { it.entry.id }
            assertEquals(MediaHealthStatus.AVAILABLE, byId.getValue("ok").status)
            assertEquals(MediaHealthStatus.CHANGED_CONTENT, byId.getValue("changed").status)
            assertEquals(MediaHealthStatus.MISSING, byId.getValue("missing").status)
            if (treatLockedAsUnreadable) {
                assertEquals(MediaHealthStatus.PERMISSION_LOST, byId.getValue("locked").status)
            }
        } finally {
            unreadableFile.setReadable(true)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
