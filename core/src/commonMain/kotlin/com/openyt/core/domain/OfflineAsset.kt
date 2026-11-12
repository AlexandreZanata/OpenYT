package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class AssetKind {
    PRIMARY_MEDIA,
    ARTWORK,
    SUBTITLE,
    METADATA,
}

@Serializable
enum class AssetState {
    MISSING,
    PENDING,
    VERIFIED,
    STALE,
    FAILED,
}

@Serializable
data class MediaRevision(
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
    val contentSha256: String? = null,
) {
    /**
     * Checks whether file size and last-modified timestamp hint that the media
     * file may have changed. A hint mismatch indicates change; identical hints
     * schedule verification but are not proof of identical content without a digest.
     */
    fun matchesHints(otherSizeBytes: Long, otherMtimeMillis: Long): Boolean {
        return sizeBytes == otherSizeBytes && lastModifiedEpochMillis == otherMtimeMillis
    }

    /**
     * Confirms identity when content SHA-256 digest is verified.
     */
    fun matchesDigest(otherSha256: String?): Boolean {
        if (contentSha256 == null || otherSha256 == null) return false
        return contentSha256.equals(otherSha256, ignoreCase = true)
    }

    /**
     * Stable revision token representation.
     */
    val revisionToken: String
        get() = buildString {
            append("$sizeBytes-$lastModifiedEpochMillis")
            if (contentSha256 != null) {
                append("-$contentSha256")
            }
        }
}

@Serializable
data class OfflineAsset(
    val id: String,
    val entryId: String,
    val kind: AssetKind,
    val language: String? = null,
    val sourceType: String? = null,
    val relativeLocator: String,
    val byteCount: Long,
    val sha256: String,
    val state: AssetState = AssetState.PENDING,
    val isRequired: Boolean = true,
    val isPinned: Boolean = false,
    val mediaRevision: MediaRevision,
    val createdAtEpochMillis: Long = 0L,
    val lastVerifiedEpochMillis: Long? = null,
    val failureDetail: String? = null,
) {
    val isVerified: Boolean get() = state == AssetState.VERIFIED
}

fun List<OfflineAsset>.findVerifiedArtwork(): OfflineAsset? =
    firstOrNull { it.kind == AssetKind.ARTWORK && it.isVerified }

fun List<OfflineAsset>.findVerifiedSubtitles(): List<OfflineAsset> =
    filter { it.kind == AssetKind.SUBTITLE && it.isVerified }


@Serializable
enum class ReadinessStatus {
    READY,
    PENDING,
    INCOMPLETE,
    STALE,
    UNAVAILABLE,
}

@Serializable
enum class RecoveryAction {
    REVERIFY,
    RETRY_DOWNLOAD,
    FETCH_ARTWORK,
    IMPORT_SUBTITLE,
    RELINK_MEDIA,
    CLEANUP_STALE,
    DISMISS,
}

@Serializable
data class MissingRequirement(
    val kind: AssetKind,
    val description: String,
    val language: String? = null,
    val suggestedAction: RecoveryAction,
)

@Serializable
data class OfflineReadiness(
    val entryId: String,
    val status: ReadinessStatus,
    val verifiedAssets: List<OfflineAsset> = emptyList(),
    val missingRequirements: List<MissingRequirement> = emptyList(),
    val mediaRevision: MediaRevision? = null,
    val isMediaAccessible: Boolean = false,
    val explanation: String? = null,
) {
    val isReady: Boolean get() = status == ReadinessStatus.READY
    val isGreen: Boolean get() = status == ReadinessStatus.READY
}

sealed interface LibraryOperationFailure {
    val message: String
    val suggestedAction: RecoveryAction

    data class MediaMissing(
        val path: String,
        override val message: String = "Media file not found at path: $path",
        override val suggestedAction: RecoveryAction = RecoveryAction.RELINK_MEDIA,
    ) : LibraryOperationFailure

    data class MediaRevisionChanged(
        val path: String,
        val expectedRevision: MediaRevision,
        val actualSizeBytes: Long,
        val actualMtimeMillis: Long,
        override val message: String = "Media file modified at path: $path (size or modification time changed)",
        override val suggestedAction: RecoveryAction = RecoveryAction.REVERIFY,
    ) : LibraryOperationFailure

    data class AssetChecksumMismatch(
        val assetId: String,
        val expectedSha256: String,
        val actualSha256: String,
        override val message: String = "Asset $assetId checksum mismatch: expected $expectedSha256, got $actualSha256",
        override val suggestedAction: RecoveryAction = RecoveryAction.RETRY_DOWNLOAD,
    ) : LibraryOperationFailure

    data class AssetMissing(
        val assetId: String,
        val kind: AssetKind,
        val relativeLocator: String,
        override val message: String = "Asset $assetId ($kind) not found at locator: $relativeLocator",
        override val suggestedAction: RecoveryAction = RecoveryAction.RETRY_DOWNLOAD,
    ) : LibraryOperationFailure

    data class AssetCorrupted(
        val assetId: String,
        val detail: String,
        override val message: String = "Asset $assetId corrupted: $detail",
        override val suggestedAction: RecoveryAction = RecoveryAction.RETRY_DOWNLOAD,
    ) : LibraryOperationFailure

    data class DiskBudgetExceeded(
        val budgetBytes: Long,
        val requiredBytes: Long,
        val currentUsedBytes: Long,
        override val message: String = "Asset storage budget exceeded: used $currentUsedBytes of $budgetBytes (requires $requiredBytes)",
        override val suggestedAction: RecoveryAction = RecoveryAction.CLEANUP_STALE,
    ) : LibraryOperationFailure

    data class StorageReserveBreached(
        val reserveBytes: Long,
        val availableFreeBytes: Long,
        override val message: String = "Device storage reserve breached: available $availableFreeBytes bytes is below $reserveBytes bytes reserve",
        override val suggestedAction: RecoveryAction = RecoveryAction.CLEANUP_STALE,
    ) : LibraryOperationFailure

    data class PermissionDenied(
        val path: String,
        override val message: String = "Permission denied accessing path: $path",
        override val suggestedAction: RecoveryAction = RecoveryAction.RELINK_MEDIA,
    ) : LibraryOperationFailure
}
