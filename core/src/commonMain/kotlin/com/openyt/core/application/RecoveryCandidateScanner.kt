package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CandidateMatchConfidence {
    EXACT_CONTENT,
    HEURISTIC_METADATA,
    MANUAL_SELECTION,
}

data class RecoveryCandidateScanPolicy(
    val maxDepth: Int = 8,
    val maxFiles: Int = 10_000,
) {
    init {
        require(maxDepth >= 0) { "maxDepth must be non-negative" }
        require(maxFiles > 0) { "maxFiles must be positive" }
    }
}

data class ScannedRecoveryCandidate(
    val locator: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
    val mediaKind: MediaKind? = null,
    val verifiedSha256: String? = null,
)

data class RankedRecoveryCandidate(
    val candidate: ScannedRecoveryCandidate,
    val confidence: CandidateMatchConfidence,
    val verifiedSha256: String? = null,
    val score: Int,
)

data class CandidateScanProgress(
    val filesScanned: Int,
    val hashesComputed: Int,
    val currentFileName: String = "",
)

data class RecoveryFolderScanOutput(
    val candidates: List<ScannedRecoveryCandidate>,
    val truncatedByLimit: Boolean = false,
    val skippedUnsafeLinks: Int = 0,
)

data class CandidateScanResult(
    val exactMatches: List<RankedRecoveryCandidate>,
    val heuristicMatches: List<RankedRecoveryCandidate>,
    val truncatedByLimit: Boolean = false,
    val skippedUnsafeLinks: Int = 0,
    val filesScanned: Int = 0,
)

enum class DuplicateMatchKind {
    EXACT_SHA256,
    HEURISTIC_METADATA,
}

data class EnhancedDuplicateGroup(
    val kind: DuplicateMatchKind,
    val sha256: String? = null,
    val entries: List<LibraryEntry>,
)

/**
 * Bounded, opt-in scanner & streaming SHA-256 verification port.
 */
interface RecoveryCandidateSourcePort {
    suspend fun scanExplicitFolders(
        folderLocators: List<String>,
        policy: RecoveryCandidateScanPolicy = RecoveryCandidateScanPolicy(),
        onProgress: suspend (CandidateScanProgress) -> Unit = {},
    ): RecoveryFolderScanOutput

    suspend fun inspectExplicitFiles(
        fileLocators: List<String>,
    ): List<ScannedRecoveryCandidate> = emptyList()

    suspend fun computeFileSha256(locator: String): String?
}

/**
 * Ranks user-selected recovery candidates against a library entry with:
 * - Strict sequential (`Mutex` concurrency=1) streaming SHA-256 verification.
 * - Explicit separation between cryptographic EXACT_CONTENT matches and HEURISTIC_METADATA hints.
 */
class RecoveryCandidateMatcher(
    private val sourcePort: RecoveryCandidateSourcePort,
) {
    private val hashMutex = Mutex()

    suspend fun findCandidatesForEntry(
        entry: LibraryEntry,
        folderLocators: List<String> = emptyList(),
        explicitFileLocators: List<String> = emptyList(),
        policy: RecoveryCandidateScanPolicy = RecoveryCandidateScanPolicy(),
        onProgress: suspend (CandidateScanProgress) -> Unit = {},
    ): CandidateScanResult {
        val folderOutput = if (folderLocators.isNotEmpty()) {
            sourcePort.scanExplicitFolders(folderLocators, policy, onProgress)
        } else {
            RecoveryFolderScanOutput(emptyList())
        }
        val explicitFiles = if (explicitFileLocators.isNotEmpty()) {
            sourcePort.inspectExplicitFiles(explicitFileLocators)
        } else {
            emptyList()
        }
        val allCandidates = (explicitFiles + folderOutput.candidates).distinctBy { it.locator }

        val ranked = evaluateCandidatesForEntry(
            entry = entry,
            candidates = allCandidates,
            onProgress = onProgress,
        )
        return CandidateScanResult(
            exactMatches = ranked.filter { it.confidence == CandidateMatchConfidence.EXACT_CONTENT },
            heuristicMatches = ranked.filter { it.confidence != CandidateMatchConfidence.EXACT_CONTENT },
            truncatedByLimit = folderOutput.truncatedByLimit,
            skippedUnsafeLinks = folderOutput.skippedUnsafeLinks,
            filesScanned = allCandidates.size,
        )
    }

    suspend fun verifySingleCandidate(
        entry: LibraryEntry,
        candidate: ScannedRecoveryCandidate,
    ): RankedRecoveryCandidate {
        val expectedRev = entry.mediaRevision
        val sha = if (candidate.verifiedSha256 != null) {
            candidate.verifiedSha256.lowercase()
        } else {
            hashSequential(candidate.locator)
        }
        val isExact = expectedRev?.contentSha256 != null &&
            sha != null &&
            candidate.sizeBytes == expectedRev.sizeBytes &&
            sha.equals(expectedRev.contentSha256, ignoreCase = true)

        return RankedRecoveryCandidate(
            candidate = candidate.copy(verifiedSha256 = sha ?: candidate.verifiedSha256),
            confidence = if (isExact) {
                CandidateMatchConfidence.EXACT_CONTENT
            } else {
                CandidateMatchConfidence.MANUAL_SELECTION
            },
            verifiedSha256 = sha,
            score = if (isExact) 1_000 else computeHeuristicScore(entry, candidate),
        )
    }

    suspend fun evaluateCandidatesForEntry(
        entry: LibraryEntry,
        candidates: List<ScannedRecoveryCandidate>,
        onProgress: suspend (CandidateScanProgress) -> Unit = {},
    ): List<RankedRecoveryCandidate> {
        val expectedRev = entry.mediaRevision
        val expectedSize = expectedRev?.sizeBytes ?: entry.sizeBytes
        val expectedSha = expectedRev?.contentSha256?.lowercase()

        val exact = mutableListOf<RankedRecoveryCandidate>()
        val heuristic = mutableListOf<RankedRecoveryCandidate>()
        var hashesComputed = 0

        for ((idx, candidate) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            var verifiedSha = candidate.verifiedSha256?.lowercase()
            if (expectedSha != null && expectedSize != null && candidate.sizeBytes == expectedSize && verifiedSha == null) {
                verifiedSha = hashSequential(candidate.locator)
                hashesComputed++
                onProgress(
                    CandidateScanProgress(
                        filesScanned = idx + 1,
                        hashesComputed = hashesComputed,
                        currentFileName = candidate.displayName,
                    ),
                )
            }

            if (expectedSha != null &&
                expectedSize != null &&
                candidate.sizeBytes == expectedSize &&
                verifiedSha != null &&
                verifiedSha == expectedSha
            ) {
                exact += RankedRecoveryCandidate(
                    candidate = candidate.copy(verifiedSha256 = verifiedSha),
                    confidence = CandidateMatchConfidence.EXACT_CONTENT,
                    verifiedSha256 = verifiedSha,
                    score = 1_000,
                )
            } else {
                val score = computeHeuristicScore(entry, candidate)
                if (score > 0) {
                    heuristic += RankedRecoveryCandidate(
                        candidate = candidate.copy(verifiedSha256 = verifiedSha),
                        confidence = CandidateMatchConfidence.HEURISTIC_METADATA,
                        verifiedSha256 = verifiedSha,
                        score = score,
                    )
                }
            }
        }

        return exact + heuristic.sortedByDescending { it.score }
    }

    suspend fun detectDuplicates(
        entries: List<LibraryEntry>,
        verifyUnverifiedSameSize: Boolean = true,
    ): List<EnhancedDuplicateGroup> {
        val shaByEntryId = mutableMapOf<String, String>()
        entries.forEach { entry ->
            entry.mediaRevision?.contentSha256?.takeIf { it.isNotBlank() }?.let { sha ->
                shaByEntryId[entry.id] = sha.lowercase()
            }
        }

        if (verifyUnverifiedSameSize) {
            val sizeBuckets = entries
                .filter { it.sizeBytes != null && it.sizeBytes > 0L && it.id !in shaByEntryId }
                .groupBy { it.sizeBytes }
            for ((size, group) in sizeBuckets) {
                val allSameSize = entries.filter { (it.mediaRevision?.sizeBytes ?: it.sizeBytes) == size }
                if (allSameSize.size < 2) continue
                for (entry in group) {
                    currentCoroutineContext().ensureActive()
                    val sha = hashSequential(entry.filePath)
                    if (!sha.isNullOrBlank()) {
                        shaByEntryId[entry.id] = sha.lowercase()
                    }
                }
            }
        }

        val groups = mutableListOf<EnhancedDuplicateGroup>()
        val exactGroupedIds = mutableSetOf<String>()

        shaByEntryId.entries
            .groupBy({ it.value }, { it.key })
            .filter { it.value.size > 1 }
            .forEach { (sha, ids) ->
                val members = entries.filter { it.id in ids }
                if (members.size > 1) {
                    exactGroupedIds += ids
                    groups += EnhancedDuplicateGroup(
                        kind = DuplicateMatchKind.EXACT_SHA256,
                        sha256 = sha,
                        entries = members,
                    )
                }
            }

        val remaining = entries.filter { it.id !in exactGroupedIds }
        remaining
            .groupBy { heuristicDuplicateKey(it) }
            .filter { it.key != null && it.value.size > 1 }
            .values
            .forEach { members ->
                groups += EnhancedDuplicateGroup(
                    kind = DuplicateMatchKind.HEURISTIC_METADATA,
                    sha256 = null,
                    entries = members,
                )
            }

        return groups
    }

    private suspend fun hashSequential(locator: String): String? = hashMutex.withLock {
        try {
            currentCoroutineContext().ensureActive()
            sourcePort.computeFileSha256(locator)?.lowercase()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        }
    }

    private fun computeHeuristicScore(entry: LibraryEntry, candidate: ScannedRecoveryCandidate): Int {
        var score = 0
        if (entry.kind == candidate.mediaKind || candidate.mediaKind == null) {
            score += 10
        } else {
            return 0
        }
        val expectedSize = entry.mediaRevision?.sizeBytes ?: entry.sizeBytes
        if (expectedSize != null && expectedSize > 0L && candidate.sizeBytes == expectedSize) {
            score += 400
        } else if (expectedSize != null && expectedSize > 0L) {
            val deltaRatio = kotlin.math.abs(candidate.sizeBytes - expectedSize).toDouble() / expectedSize.toDouble()
            if (deltaRatio <= 0.05) score += 120
        }

        val entryFileName = entry.filePath.substringAfterLast('/').substringAfterLast('\\')
        val normEntryFile = normalizeForMatch(entryFileName.substringBeforeLast('.'))
        val normTitle = normalizeForMatch(entry.title)
        val normCand = normalizeForMatch(candidate.displayName.substringBeforeLast('.'))

        if (normCand.isNotBlank() && (normCand == normEntryFile || normCand == normTitle)) {
            score += 350
        } else if (normCand.isNotBlank() &&
            ((normEntryFile.isNotBlank() && (normCand.contains(normEntryFile) || normEntryFile.contains(normCand))) ||
                (normTitle.isNotBlank() && (normCand.contains(normTitle) || normTitle.contains(normCand))))
        ) {
            score += 180
        }
        val origExt = entryFileName.substringAfterLast('.', "").lowercase()
        val candExt = candidate.displayName.substringAfterLast('.', "").lowercase()
        if (origExt.isNotBlank() && origExt == candExt) {
            score += 40
        }
        return if (score >= 180) score else 0
    }

    private fun heuristicDuplicateKey(entry: LibraryEntry): String? {
        val normTitle = normalizeForMatch(entry.title)
        val size = entry.mediaRevision?.sizeBytes ?: entry.sizeBytes
        return when {
            normTitle.isNotBlank() && size != null -> "t:$normTitle|s:$size"
            normTitle.isNotBlank() -> "t:$normTitle|k:${entry.kind.name}"
            else -> null
        }
    }

    private fun normalizeForMatch(raw: String): String =
        raw.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
