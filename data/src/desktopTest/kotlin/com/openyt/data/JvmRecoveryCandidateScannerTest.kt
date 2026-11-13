package com.openyt.data

import com.openyt.core.application.CandidateMatchConfidence
import com.openyt.core.application.RecoveryCandidateMatcher
import com.openyt.core.application.RecoveryCandidateScanPolicy
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmRecoveryCandidateScannerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun enforcesDepthFileLimitAndSymlinkEscapeProtectionWhileMatchingExactSha() = runBlocking {
        val root = tempFolder.newFolder("scan-root")
        val outsideDir = tempFolder.newFolder("outside")
        val outsideSecret = File(outsideDir, "secret.mp4").apply { writeBytes("OUTSIDE".encodeToByteArray()) }

        val targetBytes = "REAL-LECTURE-MEDIA-CONTENT".encodeToByteArray()
        val matchFile = File(root, "sub/recovered_video.mp4").apply {
            parentFile.mkdirs()
            writeBytes(targetBytes)
        }
        val heuristicFile = File(root, "Lecture Title.mp4").apply {
            writeBytes("DIFFERENT-SIZE-OTHER".encodeToByteArray())
        }
        // Attempt symlink cycle / escape inside scan root (ignored silently if OS disallows symlinks)
        val symlinkPath = File(root, "escape-link").toPath()
        val createdSymlink = runCatching {
            Files.createSymbolicLink(symlinkPath, outsideDir.toPath())
            true
        }.getOrDefault(false)

        val expectedRev = MediaRevision(
            sizeBytes = targetBytes.size.toLong(),
            lastModifiedEpochMillis = 123L,
            contentSha256 = sha256(targetBytes),
        )
        val entry = LibraryEntry(
            id = "e1",
            title = "Lecture Title",
            kind = MediaKind.VIDEO,
            filePath = "/missing/Lecture Title.mp4",
            sizeBytes = expectedRev.sizeBytes,
            mediaRevision = expectedRev,
        )

        val scanner = JvmRecoveryCandidateScanner()
        val result = RecoveryCandidateMatcher(scanner).findCandidatesForEntry(
            entry = entry,
            folderLocators = listOf(root.absolutePath),
            policy = RecoveryCandidateScanPolicy(maxDepth = 4, maxFiles = 50),
        )

        assertEquals(1, result.exactMatches.size)
        assertEquals(matchFile.canonicalPath, File(result.exactMatches.single().candidate.locator).canonicalPath)
        assertEquals(CandidateMatchConfidence.EXACT_CONTENT, result.exactMatches.single().confidence)

        assertEquals(1, result.heuristicMatches.size)
        assertEquals(heuristicFile.canonicalPath, File(result.heuristicMatches.single().candidate.locator).canonicalPath)

        // Outside symlinked file must never appear in candidates
        val allLocators = (result.exactMatches + result.heuristicMatches).map { File(it.candidate.locator).canonicalPath }
        assertTrue(outsideSecret.canonicalPath !in allLocators)
        if (createdSymlink) {
            assertTrue(result.skippedUnsafeLinks >= 1)
        }

        // Enforce strict maxFiles truncation bound
        val truncated = scanner.scanExplicitFolders(
            listOf(root.absolutePath),
            policy = RecoveryCandidateScanPolicy(maxDepth = 4, maxFiles = 1),
        )
        assertEquals(1, truncated.candidates.size)
        assertTrue(truncated.truncatedByLimit)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
