package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaRevision

enum class RelinkContentMode {
    IDENTICAL_CONTENT,
    CHANGED_CONTENT_REPLACEMENT,
}

enum class RelinkTransferStrategy {
    REFERENCE_LOCAL_PATH,
    COPY_SAF_TO_OWNED,
}

enum class RelinkWarningCode {
    REQUIRES_MANUAL_CONFIRMATION,
    STUDY_ANCHORS_WILL_BE_MARKED_STALE,
    SUBTITLE_INDEX_WILL_BE_INVALIDATED,
    OFFLINE_ASSETS_WILL_BE_MARKED_STALE,
    RECOMMENDED_PORTABLE_BACKUP_FIRST,
}

data class RelinkPlan(
    val planId: String,
    val entryId: String,
    val entryTitle: String,
    val previousPath: String,
    val previousRevision: MediaRevision?,
    val targetLocator: String,
    val targetDisplayName: String,
    val targetRevision: MediaRevision,
    val confidence: CandidateMatchConfidence,
    val contentMode: RelinkContentMode,
    val transferStrategy: RelinkTransferStrategy,
    val requiresManualConfirmation: Boolean,
    val warnsSubtitleInvalidation: Boolean,
    val warnsStaleStudyAnchors: Boolean,
    val recommendsBackupBeforeRelink: Boolean,
    val warnings: Set<RelinkWarningCode>,
    val confirmationToken: String,
    val createdAtEpochMillis: Long,
)

sealed class RelinkFailure(val userSafeCode: String, open val message: String) {
    data class InvalidCandidate(override val message: String) : RelinkFailure("INVALID_CANDIDATE", message)
    data class StalePlan(override val message: String) : RelinkFailure("STALE_PLAN", message)
    data class ConfirmationRequired(override val message: String) : RelinkFailure("CONFIRMATION_REQUIRED", message)
    data class PermissionDenied(override val message: String) : RelinkFailure("PERMISSION_DENIED", message)
    data class SourceMissing(override val message: String) : RelinkFailure("SOURCE_MISSING", message)
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long, val reserveBytes: Long) :
        RelinkFailure("INSUFFICIENT_SPACE", "Insufficient space for SAF recovery copy")
    data class Canceled(override val message: String = "Relink canceled") : RelinkFailure("CANCELED", message)
    data class IoError(override val message: String) : RelinkFailure("IO_ERROR", message)

    fun toException(): RelinkException = RelinkException(this)
}

class RelinkException(
    val failure: RelinkFailure,
    cause: Throwable? = null,
) : Exception(failure.message, cause)

/**
 * Pure deterministic relink planner enforcing:
 * - Strict distinction between cryptographically verified IDENTICAL_CONTENT and CHANGED_CONTENT_REPLACEMENT.
 * - Mandatory manual confirmation for heuristic or content-changing replacements.
 * - Clear warning propagation for subtitle index invalidation, stale study anchors, and optional NX5 backup recommendation.
 * - Stale-plan token validation before commit.
 */
object RelinkPlanner {

    fun buildPlan(
        entry: LibraryEntry,
        rankedCandidate: RankedRecoveryCandidate,
        targetIsScopedContentUri: Boolean,
        existingStudyItemCount: Int = 0,
        existingIndexedSubtitleCount: Int = 0,
        existingOfflineAssetCount: Int = 0,
        planId: String,
        nowEpochMillis: Long,
    ): Result<RelinkPlan> {
        val candidate = rankedCandidate.candidate
        if (candidate.locator.isBlank() || candidate.sizeBytes < 0L) {
            return Result.failure(
                RelinkFailure.InvalidCandidate("Candidate locator is blank or invalid").toException(),
            )
        }

        val sha = (rankedCandidate.verifiedSha256 ?: candidate.verifiedSha256)?.lowercase()
        val expectedSha = entry.mediaRevision?.contentSha256?.lowercase()
        val expectedSize = entry.mediaRevision?.sizeBytes
        val isIdenticalContent = rankedCandidate.confidence == CandidateMatchConfidence.EXACT_CONTENT &&
            !sha.isNullOrBlank() &&
            !expectedSha.isNullOrBlank() &&
            expectedSize != null &&
            candidate.sizeBytes == expectedSize &&
            sha == expectedSha

        val contentMode = if (isIdenticalContent) {
            RelinkContentMode.IDENTICAL_CONTENT
        } else {
            RelinkContentMode.CHANGED_CONTENT_REPLACEMENT
        }

        val transferStrategy = if (targetIsScopedContentUri) {
            RelinkTransferStrategy.COPY_SAF_TO_OWNED
        } else {
            RelinkTransferStrategy.REFERENCE_LOCAL_PATH
        }

        val requiresManualConfirmation = contentMode != RelinkContentMode.IDENTICAL_CONTENT ||
            rankedCandidate.confidence != CandidateMatchConfidence.EXACT_CONTENT

        val warnings = linkedSetOf<RelinkWarningCode>()
        if (requiresManualConfirmation) {
            warnings += RelinkWarningCode.REQUIRES_MANUAL_CONFIRMATION
        }
        val warnsStudy = contentMode == RelinkContentMode.CHANGED_CONTENT_REPLACEMENT && existingStudyItemCount > 0
        val warnsSubtitles = contentMode == RelinkContentMode.CHANGED_CONTENT_REPLACEMENT && existingIndexedSubtitleCount > 0
        val warnsAssets = contentMode == RelinkContentMode.CHANGED_CONTENT_REPLACEMENT && existingOfflineAssetCount > 0
        val recommendsBackup = warnsStudy || warnsSubtitles || (contentMode == RelinkContentMode.CHANGED_CONTENT_REPLACEMENT && existingOfflineAssetCount > 0)

        if (warnsStudy) warnings += RelinkWarningCode.STUDY_ANCHORS_WILL_BE_MARKED_STALE
        if (warnsSubtitles) warnings += RelinkWarningCode.SUBTITLE_INDEX_WILL_BE_INVALIDATED
        if (warnsAssets) warnings += RelinkWarningCode.OFFLINE_ASSETS_WILL_BE_MARKED_STALE
        if (recommendsBackup) warnings += RelinkWarningCode.RECOMMENDED_PORTABLE_BACKUP_FIRST

        val targetRev = MediaRevision(
            sizeBytes = candidate.sizeBytes,
            lastModifiedEpochMillis = candidate.lastModifiedEpochMillis,
            contentSha256 = sha,
        )
        val confirmationToken = buildConfirmationToken(
            entryId = entry.id,
            previousPath = entry.filePath,
            targetLocator = candidate.locator,
            targetRevision = targetRev,
            contentMode = contentMode,
        )

        return Result.success(
            RelinkPlan(
                planId = planId,
                entryId = entry.id,
                entryTitle = entry.title,
                previousPath = entry.filePath,
                previousRevision = entry.mediaRevision,
                targetLocator = candidate.locator,
                targetDisplayName = candidate.displayName,
                targetRevision = targetRev,
                confidence = rankedCandidate.confidence,
                contentMode = contentMode,
                transferStrategy = transferStrategy,
                requiresManualConfirmation = requiresManualConfirmation,
                warnsSubtitleInvalidation = warnsSubtitles,
                warnsStaleStudyAnchors = warnsStudy,
                recommendsBackupBeforeRelink = recommendsBackup,
                warnings = warnings,
                confirmationToken = confirmationToken,
                createdAtEpochMillis = nowEpochMillis,
            ),
        )
    }

    fun validatePlanCurrent(
        plan: RelinkPlan,
        currentEntry: LibraryEntry?,
        confirmationToken: String,
        confirmedContentChange: Boolean,
        observedTargetRevision: MediaRevision? = null,
    ): Result<Unit> {
        if (currentEntry == null) {
            return Result.failure(RelinkFailure.StalePlan("Target library entry no longer exists").toException())
        }
        if (currentEntry.filePath != plan.previousPath) {
            return Result.failure(RelinkFailure.StalePlan("Library entry path changed since planning").toException())
        }
        if (confirmationToken != plan.confirmationToken) {
            return Result.failure(RelinkFailure.ConfirmationRequired("Relink confirmation token mismatch").toException())
        }
        if (plan.requiresManualConfirmation && !confirmedContentChange) {
            return Result.failure(RelinkFailure.ConfirmationRequired("Explicit confirmation is required for replacement relink").toException())
        }
        if (observedTargetRevision != null) {
            if (observedTargetRevision.sizeBytes != plan.targetRevision.sizeBytes) {
                return Result.failure(RelinkFailure.StalePlan("Selected recovery file size changed before commit").toException())
            }
            val expectedSha = plan.targetRevision.contentSha256
            val actualSha = observedTargetRevision.contentSha256
            if (!expectedSha.isNullOrBlank() && !actualSha.isNullOrBlank() && !expectedSha.equals(actualSha, ignoreCase = true)) {
                return Result.failure(RelinkFailure.StalePlan("Selected recovery file digest changed before commit").toException())
            }
        }
        return Result.success(Unit)
    }

    private fun buildConfirmationToken(
        entryId: String,
        previousPath: String,
        targetLocator: String,
        targetRevision: MediaRevision,
        contentMode: RelinkContentMode,
    ): String {
        val raw = "$entryId|${previousPath.hashCode()}|${targetLocator.hashCode()}|${targetRevision.sizeBytes}|${targetRevision.contentSha256.orEmpty()}|${contentMode.name}"
        var acc = 1125899906842597L
        for (ch in raw) {
            acc = acc * 31L + ch.code.toLong()
        }
        return "rel-$entryId-${acc.toULong().toString(16)}"
    }
}
