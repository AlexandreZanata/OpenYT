package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaRevision
import kotlinx.coroutines.CancellationException

interface RecoveryLocationPickerPort {
    val canPickFolders: Boolean
    val canPickFiles: Boolean

    fun pickScanFolders(onPicked: (List<String>) -> Unit)
    fun pickReplacementFiles(onPicked: (List<String>) -> Unit)
}

interface LibraryRecoveryUiController {
    suspend fun analyzeHealth(verifyHashesWhenUncertain: Boolean = false): LibraryHealthReport

    suspend fun scanCandidates(
        entry: LibraryEntry,
        folderLocators: List<String>,
        explicitFileLocators: List<String>,
        onProgress: suspend (CandidateScanProgress) -> Unit = {},
    ): CandidateScanResult

    suspend fun buildRelinkPlan(
        entry: LibraryEntry,
        rankedCandidate: RankedRecoveryCandidate,
    ): Result<RelinkPlan>

    suspend fun verifyAndBuildManualPlan(
        entry: LibraryEntry,
        manualLocator: String,
    ): Result<RelinkPlan>

    suspend fun executeRelink(
        plan: RelinkPlan,
        confirmedContentChange: Boolean,
    ): Result<RelinkReceipt>

    suspend fun detectDuplicates(verifySameSize: Boolean = true): List<EnhancedDuplicateGroup>

    suspend fun studyItemCountsByEntry(): Map<String, Int>
}

class DefaultLibraryRecoveryUiController(
    private val downloadRepository: DownloadRepository,
    private val offlineAssetRepository: OfflineAssetRepository? = null,
    private val subtitleIndexRepository: SubtitleIndexRepository? = null,
    private val studyRepository: StudyRepository? = null,
    private val healthAnalyzer: LibraryHealthAnalyzer,
    private val candidateSourcePort: RecoveryCandidateSourcePort,
    private val candidateMatcher: RecoveryCandidateMatcher,
    private val relinkExecutor: RelinkExecutor,
    private val ownedRecoveryRootProvider: () -> String,
    private val nowProvider: () -> Long = {
        kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds + 1_700_000_000_000L
    },
) : LibraryRecoveryUiController {

    private var planSeq = 0L

    override suspend fun analyzeHealth(verifyHashesWhenUncertain: Boolean): LibraryHealthReport {
        val entries = downloadRepository.loadLibrary()
        val assets = offlineAssetRepository?.loadAllAssets().orEmpty()
        return healthAnalyzer.analyze(
            entries = entries,
            assets = assets,
            verifyHashesWhenUncertain = verifyHashesWhenUncertain,
        )
    }

    override suspend fun scanCandidates(
        entry: LibraryEntry,
        folderLocators: List<String>,
        explicitFileLocators: List<String>,
        onProgress: suspend (CandidateScanProgress) -> Unit,
    ): CandidateScanResult = candidateMatcher.findCandidatesForEntry(
        entry = entry,
        folderLocators = folderLocators,
        explicitFileLocators = explicitFileLocators,
        onProgress = onProgress,
    )

    override suspend fun buildRelinkPlan(
        entry: LibraryEntry,
        rankedCandidate: RankedRecoveryCandidate,
    ): Result<RelinkPlan> {
        val candidate = rankedCandidate.candidate
        // Always compute SHA-256 on user-selected candidate before building final confirmation plan
        // if not yet verified, so EXACT_CONTENT vs CHANGED_CONTENT_REPLACEMENT is authoritative.
        val verifiedCandidate = if (rankedCandidate.verifiedSha256 != null) {
            rankedCandidate
        } else {
            candidateMatcher.verifySingleCandidate(entry, candidate)
        }
        return buildPlanForCandidate(entry, verifiedCandidate)
    }

    override suspend fun verifyAndBuildManualPlan(
        entry: LibraryEntry,
        manualLocator: String,
    ): Result<RelinkPlan> {
        val trimmed = manualLocator.trim()
        if (trimmed.isBlank()) {
            return Result.failure(RelinkFailure.InvalidCandidate("File path or URI cannot be blank").toException())
        }
        val inspected = try {
            candidateSourcePort.inspectExplicitFiles(listOf(trimmed)).firstOrNull()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            return Result.failure(RelinkFailure.IoError(error.message ?: "Inspection failed").toException())
        } ?: return Result.failure(RelinkFailure.SourceMissing("Selected file was not found or is unreadable").toException())

        val verified = candidateMatcher.verifySingleCandidate(entry, inspected)
        return buildPlanForCandidate(entry, verified)
    }

    override suspend fun executeRelink(
        plan: RelinkPlan,
        confirmedContentChange: Boolean,
    ): Result<RelinkReceipt> = relinkExecutor.executeRelink(
        plan = plan,
        confirmationToken = plan.confirmationToken,
        confirmedContentChange = confirmedContentChange,
        ownedRecoveryRoot = ownedRecoveryRootProvider(),
    )

    override suspend fun detectDuplicates(verifySameSize: Boolean): List<EnhancedDuplicateGroup> {
        val entries = downloadRepository.loadLibrary()
        return candidateMatcher.detectDuplicates(entries, verifyUnverifiedSameSize = verifySameSize)
    }

    override suspend fun studyItemCountsByEntry(): Map<String, Int> {
        val repo = studyRepository ?: return emptyMap()
        val entries = downloadRepository.loadLibrary()
        return entries.associate { entry ->
            val bundle = repo.loadEntryStudyBundle(entry.id)
            val count = bundle.notes.size + bundle.bookmarks.size + bundle.savedRanges.size +
                if (bundle.studyState.isStudied) 1 else 0
            entry.id to count
        }
    }

    private suspend fun buildPlanForCandidate(
        entry: LibraryEntry,
        ranked: RankedRecoveryCandidate,
    ): Result<RelinkPlan> {
        val isSafUri = ranked.candidate.locator.startsWith("content://", ignoreCase = true)
        val studyBundle = studyRepository?.loadEntryStudyBundle(entry.id)
        val studyCount = studyBundle?.let {
            it.notes.size + it.bookmarks.size + it.savedRanges.size
        } ?: 0
        val indexedSubCount = subtitleIndexRepository?.loadTrackIndexesForEntry(entry.id)?.size ?: 0
        val assetCount = offlineAssetRepository?.loadAssetsForEntry(entry.id)?.size ?: 0

        val now = nowProvider()
        return RelinkPlanner.buildPlan(
            entry = entry,
            rankedCandidate = ranked,
            targetIsScopedContentUri = isSafUri,
            existingStudyItemCount = studyCount,
            existingIndexedSubtitleCount = indexedSubCount,
            existingOfflineAssetCount = assetCount,
            planId = "rel-${entry.id}-$now-${++planSeq}",
            nowEpochMillis = now,
        )
    }
}
