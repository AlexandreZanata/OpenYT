package com.openyt.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class AssetOperationStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED,
    INTERRUPTED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELED
}

@Serializable
sealed interface AssetOperationFailure {
    val message: String

    @Serializable
    data class DiskFull(
        val availableBytes: Long,
        val requiredBytes: Long,
        override val message: String = "Insufficient storage space: $availableBytes available, $requiredBytes required",
    ) : AssetOperationFailure

    @Serializable
    data class RevokedPermission(
        val path: String,
        override val message: String = "Storage permission revoked for path: $path",
    ) : AssetOperationFailure

    @Serializable
    data class TruncatedCopy(
        val expectedBytes: Long,
        val actualBytes: Long,
        override val message: String = "Copy truncated: expected $expectedBytes bytes, but wrote $actualBytes bytes",
    ) : AssetOperationFailure

    @Serializable
    data class SourceMissing(
        val path: String,
        override val message: String = "Source artifact not found at path: $path",
    ) : AssetOperationFailure

    @Serializable
    data class VerificationFailed(
        val reason: String,
        override val message: String = "Verification failed: $reason",
    ) : AssetOperationFailure

    @Serializable
    data object Canceled : AssetOperationFailure {
        override val message: String = "Asset operation was canceled"
    }

    @Serializable
    data class Unexpected(
        val detail: String?,
        override val message: String = "Unexpected error: ${detail ?: "unknown"}",
    ) : AssetOperationFailure
}

@Serializable
data class AssetOperation(
    val id: String,
    val entryId: String,
    val kind: AssetKind,
    val sourcePath: String,
    val language: String? = null,
    val sourceType: String? = null,
    val timelineBasis: String? = null,
    val isRequired: Boolean = false,
    val status: AssetOperationStatus = AssetOperationStatus.QUEUED,
    val stagingPath: String? = null,
    val targetLocator: String? = null,
    val assetId: String? = null,
    val byteCount: Long? = null,
    val expectedSizeBytes: Long? = null,
    val sha256: String? = null,
    val failure: AssetOperationFailure? = null,
    val attempt: Int = 0,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
)
