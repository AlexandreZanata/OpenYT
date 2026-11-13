package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoveryCandidateScannerTest {

    @Test
    fun separatesExactContentMatchesFromHeuristicsAndEnforcesSequentialHashing() = runBlocking {
        val expectedSha = "abc123matchinghash"
        val entry = LibraryEntry(
            id = "e1",
            title = "Algorithm Analysis",
            kind = MediaKind.VIDEO,
            filePath = "/old/Algorithm Analysis.mp4",
            sizeBytes = 5000L,
            mediaRevision = MediaRevision(sizeBytes = 5000L, lastModifiedEpochMillis = 100L, contentSha256 = expectedSha),
        )

        var activeHashes = 0
        var maxConcurrentHashes = 0
        val hashes = mapOf(
            "/cand/renamed-exact.mp4" to expectedSha,
            "/cand/Algorithm Analysis.mp4" to "different-hash-same-size",
        )
        val candidates = listOf(
            ScannedRecoveryCandidate("/cand/renamed-exact.mp4", "renamed-exact.mp4", 5000L, 200L, MediaKind.VIDEO),
            ScannedRecoveryCandidate("/cand/Algorithm Analysis.mp4", "Algorithm Analysis.mp4", 5000L, 200L, MediaKind.VIDEO),
            ScannedRecoveryCandidate("/cand/unrelated.mp4", "unrelated.mp4", 1234L, 200L, MediaKind.VIDEO),
        )

        val fakePort = object : RecoveryCandidateSourcePort {
            override suspend fun scanExplicitFolders(
                folderLocators: List<String>,
                policy: RecoveryCandidateScanPolicy,
                onProgress: suspend (CandidateScanProgress) -> Unit,
            ) = RecoveryFolderScanOutput(candidates)

            override suspend fun computeFileSha256(locator: String): String? {
                activeHashes++
                maxConcurrentHashes = maxOf(maxConcurrentHashes, activeHashes)
                delay(5)
                activeHashes--
                return hashes[locator]
            }
        }

        val matcher = RecoveryCandidateMatcher(fakePort)
        val result = matcher.findCandidatesForEntry(entry, folderLocators = listOf("/cand"))

        assertEquals(1, maxConcurrentHashes)
        assertEquals(1, result.exactMatches.size)
        assertEquals("/cand/renamed-exact.mp4", result.exactMatches.single().candidate.locator)
        assertEquals(CandidateMatchConfidence.EXACT_CONTENT, result.exactMatches.single().confidence)

        assertEquals(1, result.heuristicMatches.size)
        assertEquals("/cand/Algorithm Analysis.mp4", result.heuristicMatches.single().candidate.locator)
        assertEquals(CandidateMatchConfidence.HEURISTIC_METADATA, result.heuristicMatches.single().confidence)
    }

    @Test
    fun detectDuplicatesSeparatesExactShaGroupsFromHeuristicTitleGroups() = runBlocking {
        val entries = listOf(
            LibraryEntry("1", "Alpha", MediaKind.VIDEO, "/a/1.mp4", sizeBytes = 100L, mediaRevision = MediaRevision(100L, 1L, "sha-same")),
            LibraryEntry("2", "Alpha Copy", MediaKind.VIDEO, "/a/2.mp4", sizeBytes = 100L, mediaRevision = MediaRevision(100L, 2L, "sha-same")),
            LibraryEntry("3", "Beta Lesson", MediaKind.VIDEO, "/a/3.mp4", sizeBytes = 200L, mediaRevision = MediaRevision(200L, 3L, "sha-b1")),
            LibraryEntry("4", "Beta Lesson", MediaKind.VIDEO, "/a/4.mp4", sizeBytes = 200L, mediaRevision = MediaRevision(200L, 4L, "sha-b2")),
        )
        val port = object : RecoveryCandidateSourcePort {
            override suspend fun scanExplicitFolders(
                folderLocators: List<String>,
                policy: RecoveryCandidateScanPolicy,
                onProgress: suspend (CandidateScanProgress) -> Unit,
            ) = RecoveryFolderScanOutput(emptyList())
            override suspend fun computeFileSha256(locator: String): String? = null
        }

        val groups = RecoveryCandidateMatcher(port).detectDuplicates(entries, verifyUnverifiedSameSize = false)
        assertEquals(2, groups.size)
        val exact = groups.first { it.kind == DuplicateMatchKind.EXACT_SHA256 }
        val heuristic = groups.first { it.kind == DuplicateMatchKind.HEURISTIC_METADATA }
        assertTrue(exact.entries.map { it.id }.toSet() == setOf("1", "2"))
        assertTrue(heuristic.entries.map { it.id }.toSet() == setOf("3", "4"))
    }
}
