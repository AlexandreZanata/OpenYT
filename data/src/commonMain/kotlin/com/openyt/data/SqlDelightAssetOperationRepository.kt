package com.openyt.data

import com.openyt.core.application.AssetOperationRepository
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperation
import com.openyt.core.domain.AssetOperationFailure
import com.openyt.core.domain.AssetOperationStatus
import com.openyt.data.db.Asset_operation
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlDelightAssetOperationRepository(
    private val database: OpenYtDatabase,
) : AssetOperationRepository {
    private val queries = database.downloadQueries

    override suspend fun insertOperation(operation: AssetOperation) = withContext(Dispatchers.Default) {
        queries.insertOperation(
            id = operation.id,
            entry_id = operation.entryId,
            kind = operation.kind.name,
            source_path = operation.sourcePath,
            language = operation.language,
            source_type = operation.sourceType,
            timeline_basis = operation.timelineBasis,
            is_required = if (operation.isRequired) 1L else 0L,
            status = operation.status.name,
            staging_path = operation.stagingPath,
            target_locator = operation.targetLocator,
            asset_id = operation.assetId,
            byte_count = operation.byteCount,
            expected_size_bytes = operation.expectedSizeBytes,
            sha256 = operation.sha256,
            error = serializeFailure(operation.failure),
            attempt = operation.attempt.toLong(),
            created_at = operation.createdAtEpochMillis,
            updated_at = operation.updatedAtEpochMillis,
        )
        Unit
    }

    override suspend fun updateOperation(operation: AssetOperation) = withContext(Dispatchers.Default) {
        queries.updateOperation(
            status = operation.status.name,
            staging_path = operation.stagingPath,
            target_locator = operation.targetLocator,
            asset_id = operation.assetId,
            byte_count = operation.byteCount,
            expected_size_bytes = operation.expectedSizeBytes,
            sha256 = operation.sha256,
            error = serializeFailure(operation.failure),
            attempt = operation.attempt.toLong(),
            updated_at = operation.updatedAtEpochMillis,
            id = operation.id,
        )
        Unit
    }

    override suspend fun getOperation(id: String): AssetOperation? = withContext(Dispatchers.Default) {
        queries.selectOperationById(id).executeAsOneOrNull()?.toModel()
    }

    override suspend fun loadOperationsForEntry(entryId: String): List<AssetOperation> = withContext(Dispatchers.Default) {
        queries.selectOperationsForEntry(entryId).executeAsList().map { it.toModel() }
    }

    override suspend fun loadActiveOperations(): List<AssetOperation> = withContext(Dispatchers.Default) {
        queries.selectActiveOperations().executeAsList().map { it.toModel() }
    }

    override suspend fun findPendingOperation(
        entryId: String,
        kind: AssetKind,
        language: String?,
        sourcePath: String,
    ): AssetOperation? = withContext(Dispatchers.Default) {
        queries.findPendingOperation(
            entry_id = entryId,
            kind = kind.name,
            language = language,
            source_path = sourcePath,
        ).executeAsOneOrNull()?.toModel()
    }

    override suspend fun markRunningAsInterrupted() = withContext(Dispatchers.Default) {
        queries.markRunningOperationsInterrupted()
        Unit
    }

    override suspend fun deleteOperation(id: String) = withContext(Dispatchers.Default) {
        queries.deleteOperation(id)
        Unit
    }

    override suspend fun deleteOperationsForEntry(entryId: String) = withContext(Dispatchers.Default) {
        queries.deleteOperationsForEntry(entryId)
        Unit
    }

    private fun Asset_operation.toModel(): AssetOperation = AssetOperation(
        id = id,
        entryId = entry_id,
        kind = runCatching { AssetKind.valueOf(kind) }.getOrDefault(AssetKind.PRIMARY_MEDIA),
        sourcePath = source_path,
        language = language,
        sourceType = source_type,
        timelineBasis = timeline_basis,
        isRequired = is_required == 1L,
        status = runCatching { AssetOperationStatus.valueOf(status) }.getOrDefault(AssetOperationStatus.FAILED),
        stagingPath = staging_path,
        targetLocator = target_locator,
        assetId = asset_id,
        byteCount = byte_count,
        expectedSizeBytes = expected_size_bytes,
        sha256 = sha256,
        failure = deserializeFailure(error),
        attempt = attempt.toInt(),
        createdAtEpochMillis = created_at,
        updatedAtEpochMillis = updated_at,
    )

    companion object {
        fun serializeFailure(failure: AssetOperationFailure?): String? = when (failure) {
            null -> null
            is AssetOperationFailure.DiskFull -> "DISK_FULL:${failure.availableBytes}:${failure.requiredBytes}:${failure.message}"
            is AssetOperationFailure.RevokedPermission -> "REVOKED_PERMISSION:${failure.path}:${failure.message}"
            is AssetOperationFailure.TruncatedCopy -> "TRUNCATED_COPY:${failure.expectedBytes}:${failure.actualBytes}:${failure.message}"
            is AssetOperationFailure.SourceMissing -> "SOURCE_MISSING:${failure.path}:${failure.message}"
            is AssetOperationFailure.VerificationFailed -> "VERIFICATION_FAILED:${failure.reason}"
            is AssetOperationFailure.Canceled -> "CANCELED"
            is AssetOperationFailure.Unexpected -> "UNEXPECTED:${failure.detail ?: ""}:${failure.message}"
        }

        fun deserializeFailure(raw: String?): AssetOperationFailure? {
            if (raw == null) return null
            val parts = raw.split(':', limit = 4)
            return when (parts.getOrNull(0)) {
                "DISK_FULL" -> AssetOperationFailure.DiskFull(
                    availableBytes = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
                    requiredBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                    message = parts.getOrNull(3) ?: "",
                )
                "REVOKED_PERMISSION" -> AssetOperationFailure.RevokedPermission(
                    path = parts.getOrNull(1) ?: "",
                    message = parts.getOrNull(2) ?: "",
                )
                "TRUNCATED_COPY" -> AssetOperationFailure.TruncatedCopy(
                    expectedBytes = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
                    actualBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                    message = parts.getOrNull(3) ?: "",
                )
                "SOURCE_MISSING" -> AssetOperationFailure.SourceMissing(
                    path = parts.getOrNull(1) ?: "",
                    message = parts.getOrNull(2) ?: "",
                )
                "VERIFICATION_FAILED" -> AssetOperationFailure.VerificationFailed(
                    reason = parts.drop(1).joinToString(":"),
                )
                "CANCELED" -> AssetOperationFailure.Canceled
                "UNEXPECTED" -> AssetOperationFailure.Unexpected(
                    detail = parts.getOrNull(1)?.ifEmpty { null },
                    message = parts.getOrNull(2) ?: raw,
                )
                else -> AssetOperationFailure.Unexpected(raw, raw)
            }
        }
    }
}
