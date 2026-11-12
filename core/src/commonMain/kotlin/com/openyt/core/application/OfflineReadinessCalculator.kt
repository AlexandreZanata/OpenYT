package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.LibraryOperationFailure
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.MissingRequirement
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.OfflineReadiness
import com.openyt.core.domain.ReadinessStatus
import com.openyt.core.domain.RecoveryAction

/**
 * Computes deterministic offline readiness based strictly on verified evidence
 * rather than URL presence or display name.
 */
object OfflineReadinessCalculator {

    /**
     * Synchronously computes the current readiness status using file presence and
     * size/mtime hints. Hashing is performed off the UI thread via [verifyEntryMedia].
     */
    fun computeReadiness(
        entry: LibraryEntry,
        assets: List<OfflineAsset>,
        fileInspector: MediaFileInspector,
    ): OfflineReadiness {
        val entryId = entry.id

        // 1. Check media file accessibility
        if (!fileInspector.exists(entry.filePath)) {
            val failure = LibraryOperationFailure.MediaMissing(entry.filePath)
            return OfflineReadiness(
                entryId = entryId,
                status = ReadinessStatus.UNAVAILABLE,
                verifiedAssets = emptyList(),
                missingRequirements = listOf(
                    MissingRequirement(
                        kind = AssetKind.PRIMARY_MEDIA,
                        description = failure.message,
                        suggestedAction = failure.suggestedAction,
                    ),
                ),
                mediaRevision = entry.mediaRevision,
                isMediaAccessible = false,
                explanation = "Media file does not exist at path: ${entry.filePath}",
            )
        }

        val metadata = fileInspector.inspect(entry.filePath)
            ?: return OfflineReadiness(
                entryId = entryId,
                status = ReadinessStatus.UNAVAILABLE,
                verifiedAssets = emptyList(),
                missingRequirements = listOf(
                    MissingRequirement(
                        kind = AssetKind.PRIMARY_MEDIA,
                        description = "Cannot read metadata for media at: ${entry.filePath}",
                        suggestedAction = RecoveryAction.RELINK_MEDIA,
                    ),
                ),
                mediaRevision = entry.mediaRevision,
                isMediaAccessible = false,
                explanation = "Media file exists but is unreadable",
            )

        // 2. Old records with unknown asset state or unrecorded revision
        val recordedRevision = entry.mediaRevision
        if (recordedRevision == null) {
            return OfflineReadiness(
                entryId = entryId,
                status = ReadinessStatus.PENDING,
                verifiedAssets = emptyList(),
                missingRequirements = listOf(
                    MissingRequirement(
                        kind = AssetKind.PRIMARY_MEDIA,
                        description = "Media has not been verified (unknown asset state)",
                        suggestedAction = RecoveryAction.REVERIFY,
                    ),
                ),
                mediaRevision = null,
                isMediaAccessible = true,
                explanation = "Media is accessible but verification state is unknown",
            )
        }

        // 3. Changed bytes at the same path: compare size/mtime hints
        val hintsMatch = recordedRevision.matchesHints(
            otherSizeBytes = metadata.sizeBytes,
            otherMtimeMillis = metadata.lastModifiedEpochMillis,
        )

        if (!hintsMatch) {
            val failure = LibraryOperationFailure.MediaRevisionChanged(
                path = entry.filePath,
                expectedRevision = recordedRevision,
                actualSizeBytes = metadata.sizeBytes,
                actualMtimeMillis = metadata.lastModifiedEpochMillis,
            )
            return OfflineReadiness(
                entryId = entryId,
                status = ReadinessStatus.STALE,
                verifiedAssets = emptyList(),
                missingRequirements = listOf(
                    MissingRequirement(
                        kind = AssetKind.PRIMARY_MEDIA,
                        description = failure.message,
                        suggestedAction = failure.suggestedAction,
                    ),
                ),
                mediaRevision = recordedRevision,
                isMediaAccessible = true,
                explanation = "Media modified on disk; size or modification timestamp changed",
            )
        }

        // 4. Evaluate companion assets
        val verifiedAssets = mutableListOf<OfflineAsset>()
        val missingRequirements = mutableListOf<MissingRequirement>()

        for (asset in assets) {
            val revisionMatches = asset.mediaRevision.sizeBytes == recordedRevision.sizeBytes &&
                asset.mediaRevision.lastModifiedEpochMillis == recordedRevision.lastModifiedEpochMillis &&
                asset.mediaRevision.contentSha256 == recordedRevision.contentSha256

            if (!revisionMatches || asset.state == AssetState.STALE) {
                if (asset.isRequired) {
                    missingRequirements += MissingRequirement(
                        kind = asset.kind,
                        description = "Required asset ${asset.kind} is stale due to media revision change",
                        language = asset.language,
                        suggestedAction = RecoveryAction.REVERIFY,
                    )
                }
            } else if (asset.state == AssetState.VERIFIED) {
                verifiedAssets += asset
            } else if (asset.isRequired) {
                val action = when (asset.kind) {
                    AssetKind.ARTWORK -> RecoveryAction.FETCH_ARTWORK
                    AssetKind.SUBTITLE -> RecoveryAction.IMPORT_SUBTITLE
                    AssetKind.PRIMARY_MEDIA -> RecoveryAction.REVERIFY
                    AssetKind.METADATA -> RecoveryAction.RETRY_DOWNLOAD
                }
                missingRequirements += MissingRequirement(
                    kind = asset.kind,
                    description = "Required asset ${asset.kind} is ${asset.state.name.lowercase()}",
                    language = asset.language,
                    suggestedAction = action,
                )
            }
        }

        val hasValidMediaKind = entry.kind == MediaKind.SUBTITLE ||
            entry.kind == MediaKind.VIDEO ||
            entry.kind == MediaKind.AUDIO

        val finalStatus = when {
            !hasValidMediaKind -> ReadinessStatus.UNAVAILABLE
            missingRequirements.isNotEmpty() -> {
                val hasStale = missingRequirements.any { it.description.contains("stale", ignoreCase = true) }
                val hasPending = missingRequirements.any { it.description.contains("pending", ignoreCase = true) }
                when {
                    hasStale -> ReadinessStatus.STALE
                    hasPending -> ReadinessStatus.PENDING
                    else -> ReadinessStatus.INCOMPLETE
                }
            }
            else -> ReadinessStatus.READY
        }

        return OfflineReadiness(
            entryId = entryId,
            status = finalStatus,
            verifiedAssets = verifiedAssets,
            missingRequirements = missingRequirements,
            mediaRevision = recordedRevision,
            isMediaAccessible = true,
            explanation = if (finalStatus == ReadinessStatus.READY) "All required assets verified offline" else null,
        )
    }

    /**
     * Inspects the file and calculates the SHA-256 digest off the UI thread.
     */
    suspend fun verifyEntryMedia(
        entry: LibraryEntry,
        fileInspector: MediaFileInspector,
        verifyDigest: Boolean = true,
    ): Result<MediaRevision> {
        if (!fileInspector.exists(entry.filePath)) {
            return Result.failure(
                IllegalArgumentException("Media file not found at path: ${entry.filePath}")
            )
        }
        val metadata = fileInspector.inspect(entry.filePath)
            ?: return Result.failure(
                IllegalStateException("Cannot inspect file at path: ${entry.filePath}")
            )

        val digest = if (verifyDigest) {
            fileInspector.computeSha256(entry.filePath)
        } else {
            entry.mediaRevision?.contentSha256
        }

        val revision = MediaRevision(
            sizeBytes = metadata.sizeBytes,
            lastModifiedEpochMillis = metadata.lastModifiedEpochMillis,
            contentSha256 = digest,
        )
        return Result.success(revision)
    }
}
