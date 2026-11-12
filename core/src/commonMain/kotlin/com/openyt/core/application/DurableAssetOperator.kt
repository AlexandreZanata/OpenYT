package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperation
import com.openyt.core.domain.AssetOperationFailure
import com.openyt.core.domain.AssetOperationStatus
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AssetOperationRequest(
    val entryId: String,
    val kind: AssetKind,
    val sourcePath: String,
    val language: String? = null,
    val sourceType: String? = null,
    val timelineBasis: String? = null,
    val isRequired: Boolean = false,
    val expectedSizeBytes: Long? = null,
)

class DurableAssetOperator(
    val operationRepository: AssetOperationRepository,
    val assetRepository: OfflineAssetRepository,
    val stagingPort: AssetStagingPort,
    val fileInspector: MediaFileInspector,
    val storageReserveBytes: Long = 134217728L, // 128 MiB default
) : OfflineAssetStore {

    private val diskExecutionMutex = Mutex()
    private var idCounter = 0L

    fun generateOperationId(): String = "op-${++idCounter}-${currentTimeMillis()}"

    /**
     * Submits an asset operation request, deduplicating with any currently active operation.
     */
    suspend fun submit(request: AssetOperationRequest): AssetOperation {
        val existing = operationRepository.findPendingOperation(
            entryId = request.entryId,
            kind = request.kind,
            language = request.language,
            sourcePath = request.sourcePath,
        )
        if (existing != null) {
            return existing
        }

        val opId = generateOperationId()
        val op = AssetOperation(
            id = opId,
            entryId = request.entryId,
            kind = request.kind,
            sourcePath = request.sourcePath,
            language = request.language,
            sourceType = request.sourceType,
            timelineBasis = request.timelineBasis,
            isRequired = request.isRequired,
            expectedSizeBytes = request.expectedSizeBytes,
            status = AssetOperationStatus.QUEUED,
            attempt = 0,
            createdAtEpochMillis = currentTimeMillis(),
            updatedAtEpochMillis = currentTimeMillis(),
        )
        operationRepository.insertOperation(op)
        return op
    }

    /**
     * Executes an asset operation through the journaled staging, hashing, bounds checking,
     * atomic promotion, and metadata publication pipeline.
     * Disk-heavy jobs are serialized through [diskExecutionMutex].
     */
    suspend fun execute(
        operationId: String,
        mediaRevision: MediaRevision,
        allowedRoots: List<String>,
    ): Result<OfflineAsset> = diskExecutionMutex.withLock {
        val op = operationRepository.getOperation(operationId)
            ?: return Result.failure(IllegalArgumentException("Operation $operationId not found"))

        if (op.status == AssetOperationStatus.SUCCEEDED && op.assetId != null) {
            val existingAsset = assetRepository.loadAssetsForEntry(op.entryId).firstOrNull { it.id == op.assetId }
            if (existingAsset != null && existingAsset.isVerified) {
                return Result.success(existingAsset)
            }
        }

        if (op.status == AssetOperationStatus.CANCELED) {
            return Result.failure(IllegalStateException("Operation $operationId was canceled"))
        }

        // 1. Mark RUNNING
        var runningOp = op.copy(
            status = AssetOperationStatus.RUNNING,
            updatedAtEpochMillis = currentTimeMillis(),
        )
        operationRepository.updateOperation(runningOp)

        // 2. Validate receipt containment against allowed roots
        val receipt = CompanionArtifactReceipt(
            kind = op.kind,
            filePath = op.sourcePath,
            language = op.language,
            sourceType = op.sourceType,
            timelineBasis = op.timelineBasis,
            isRequired = op.isRequired,
        )
        val validation = CompanionArtifactValidator.validateReceipt(receipt, allowedRoots, fileInspector)
        if (validation !is ArtifactValidationResult.Valid) {
            val failure = when (validation) {
                is ArtifactValidationResult.FileMissing -> AssetOperationFailure.SourceMissing(op.sourcePath)
                is ArtifactValidationResult.FileEmpty -> AssetOperationFailure.VerificationFailed("Source file is empty")
                is ArtifactValidationResult.OutOfRoot -> AssetOperationFailure.VerificationFailed("Source path is outside allowed task roots")
                is ArtifactValidationResult.InvalidPath -> AssetOperationFailure.VerificationFailed("Invalid path: ${op.sourcePath}")
                is ArtifactValidationResult.Valid -> AssetOperationFailure.VerificationFailed("Validation rejected: $validation")
            }
            runningOp = runningOp.copy(status = AssetOperationStatus.FAILED, failure = failure, updatedAtEpochMillis = currentTimeMillis())
            operationRepository.updateOperation(runningOp)
            return Result.failure(IllegalStateException(failure.message))
        }

        val sourceSize = validation.sizeBytes
        val expectedSize = op.expectedSizeBytes ?: sourceSize

        // 3. Disk space and quota bounds check
        val availableSpace = stagingPort.getAvailableSpace()
        val requiredSpace = sourceSize + storageReserveBytes
        if (availableSpace < requiredSpace) {
            val failure = AssetOperationFailure.DiskFull(availableSpace, requiredSpace)
            runningOp = runningOp.copy(status = AssetOperationStatus.FAILED, failure = failure, updatedAtEpochMillis = currentTimeMillis())
            operationRepository.updateOperation(runningOp)
            return Result.failure(IllegalStateException(failure.message))
        }

        // 4. Prepare isolated staging directory
        val stagingDir = stagingPort.prepareStagingDirectory(operationId)
        val ext = op.sourcePath.substringAfterLast('.', "").ifEmpty { "bin" }
        val stagingFile = "$stagingDir/staging_asset.$ext"
        runningOp = runningOp.copy(stagingPath = stagingFile, updatedAtEpochMillis = currentTimeMillis())
        operationRepository.updateOperation(runningOp)

        // 5. Stage, stream, bound, and hash
        val stageResult = stagingPort.stageAndHash(
            operationId = operationId,
            sourcePath = op.sourcePath,
            stagingPath = stagingFile,
            byteLimit = expectedSize,
        )

        if (stageResult.isFailure) {
            stagingPort.cleanupStaging(operationId)
            val err = stageResult.exceptionOrNull()
            val failure = when {
                err is TruncatedCopyException -> AssetOperationFailure.TruncatedCopy(err.expectedBytes, err.actualBytes)
                err is OperationCanceledException -> AssetOperationFailure.Canceled
                err is SecurityException -> AssetOperationFailure.RevokedPermission(op.sourcePath)
                err is DiskFullException -> AssetOperationFailure.DiskFull(err.availableBytes, err.requiredBytes)
                else -> AssetOperationFailure.Unexpected(err?.message)
            }
            val targetStatus = if (failure is AssetOperationFailure.Canceled) AssetOperationStatus.CANCELED else AssetOperationStatus.FAILED
            runningOp = runningOp.copy(status = targetStatus, failure = failure, updatedAtEpochMillis = currentTimeMillis())
            operationRepository.updateOperation(runningOp)
            return Result.failure(err ?: IllegalStateException(failure.message))
        }

        val staged = stageResult.getOrThrow()

        // 6. Verify staged artifact
        if (staged.byteCount != expectedSize) {
            stagingPort.cleanupStaging(operationId)
            val failure = AssetOperationFailure.TruncatedCopy(expectedSize, staged.byteCount)
            runningOp = runningOp.copy(status = AssetOperationStatus.FAILED, failure = failure, updatedAtEpochMillis = currentTimeMillis())
            operationRepository.updateOperation(runningOp)
            return Result.failure(IllegalStateException(failure.message))
        }

        // 7. Atomic promotion to target locator
        val assetId = op.assetId ?: "asset-${operationId.substringAfter("op-").replace('-', '_')}"
        val targetLocator = "${op.entryId}/$assetId.$ext"
        val promoteResult = stagingPort.promote(stagingFile, targetLocator)
        if (promoteResult.isFailure) {
            stagingPort.cleanupStaging(operationId)
            val failure = AssetOperationFailure.Unexpected("Atomic promotion failed: ${promoteResult.exceptionOrNull()?.message}")
            runningOp = runningOp.copy(status = AssetOperationStatus.FAILED, failure = failure, updatedAtEpochMillis = currentTimeMillis())
            operationRepository.updateOperation(runningOp)
            return Result.failure(IllegalStateException(failure.message))
        }

        // 8. Commit to offline_asset table
        val asset = OfflineAsset(
            id = assetId,
            entryId = op.entryId,
            kind = op.kind,
            language = op.language,
            sourceType = op.sourceType,
            relativeLocator = targetLocator,
            byteCount = staged.byteCount,
            sha256 = staged.sha256,
            state = AssetState.VERIFIED,
            isRequired = op.isRequired,
            isPinned = false,
            mediaRevision = mediaRevision,
            createdAtEpochMillis = currentTimeMillis(),
            lastVerifiedEpochMillis = currentTimeMillis(),
        )
        assetRepository.insertAsset(asset)

        // 9. Update operation to SUCCEEDED and clean staging directory
        runningOp = runningOp.copy(
            status = AssetOperationStatus.SUCCEEDED,
            assetId = assetId,
            targetLocator = targetLocator,
            byteCount = staged.byteCount,
            sha256 = staged.sha256,
            failure = null,
            updatedAtEpochMillis = currentTimeMillis(),
        )
        operationRepository.updateOperation(runningOp)
        stagingPort.cleanupStaging(operationId)

        return Result.success(asset)
    }

    /**
     * Retries an interrupted or failed operation.
     * Deduplicates: if already succeeded with verified asset, does not duplicate.
     */
    suspend fun retry(
        operationId: String,
        mediaRevision: MediaRevision,
        allowedRoots: List<String>,
    ): Result<OfflineAsset> {
        val op = operationRepository.getOperation(operationId)
            ?: return Result.failure(IllegalArgumentException("Operation $operationId not found"))

        if (op.status == AssetOperationStatus.SUCCEEDED && op.assetId != null) {
            val existing = assetRepository.loadAssetsForEntry(op.entryId).firstOrNull { it.id == op.assetId }
            if (existing != null && existing.isVerified) {
                return Result.success(existing)
            }
        }

        val queuedOp = op.copy(
            status = AssetOperationStatus.QUEUED,
            failure = null,
            attempt = op.attempt + 1,
            updatedAtEpochMillis = currentTimeMillis(),
        )
        operationRepository.updateOperation(queuedOp)
        return execute(operationId, mediaRevision, allowedRoots)
    }

    /**
     * Cancels an active or queued operation and safely cleans up its staging directory.
     * Never deletes source media.
     */
    suspend fun cancel(operationId: String) {
        val op = operationRepository.getOperation(operationId) ?: return
        if (op.status.isActive) {
            val canceledOp = op.copy(
                status = AssetOperationStatus.CANCELED,
                failure = AssetOperationFailure.Canceled,
                updatedAtEpochMillis = currentTimeMillis(),
            )
            operationRepository.updateOperation(canceledOp)
            stagingPort.cleanupStaging(operationId)
        }
    }

    /**
     * Reconciles publication journal after crashes or process restarts:
     * - Reconciles any RUNNING operations to INTERRUPTED (or SUCCEEDED if promotion & DB commit already completed).
     * - Safely sweeps orphaned staging directories.
     * - Preserves user media and already published assets.
     */
    suspend fun reconcileJournal() = diskExecutionMutex.withLock {
        operationRepository.markRunningAsInterrupted()
        val activeOps = operationRepository.loadActiveOperations().map { it.id }.toSet()
        stagingPort.cleanupOrphanedStaging(activeOps)
    }

    override suspend fun storeConfirmedArtifact(
        entryId: String,
        receipt: CompanionArtifactReceipt,
        mediaRevision: MediaRevision,
        allowedRoots: List<String>,
    ): Result<OfflineAsset> {
        val request = AssetOperationRequest(
            entryId = entryId,
            kind = receipt.kind,
            sourcePath = receipt.filePath,
            language = receipt.language,
            sourceType = receipt.sourceType,
            timelineBasis = receipt.timelineBasis,
            isRequired = receipt.isRequired,
            expectedSizeBytes = receipt.byteCount.takeIf { it > 0 },
        )
        val op = submit(request)
        return execute(op.id, mediaRevision, allowedRoots)
    }

    override suspend fun getAssetsForEntry(entryId: String): List<OfflineAsset> =
        assetRepository.loadAssetsForEntry(entryId)

    private fun currentTimeMillis(): Long =
        kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds + 1700000000000L
}

class TruncatedCopyException(val expectedBytes: Long, val actualBytes: Long) :
    IllegalStateException("Truncated copy: expected $expectedBytes, actual $actualBytes")

class OperationCanceledException :
    IllegalStateException("Operation was canceled")

class DiskFullException(val availableBytes: Long, val requiredBytes: Long) :
    IllegalStateException("Disk full: $availableBytes available, $requiredBytes required")
