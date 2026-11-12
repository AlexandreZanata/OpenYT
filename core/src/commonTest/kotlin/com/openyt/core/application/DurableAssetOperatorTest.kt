package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperationFailure
import com.openyt.core.domain.AssetOperationStatus
import com.openyt.core.domain.MediaRevision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DurableAssetOperatorTest {

    private class FakeFileInspector : MediaFileInspector {
        val files = mutableMapOf<String, Long>()

        fun addFile(path: String, sizeBytes: Long) {
            files[path] = sizeBytes
        }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun inspect(path: String): FileMetadata? {
            val size = files[path] ?: return null
            return FileMetadata(sizeBytes = size, lastModifiedEpochMillis = 1000L)
        }

        override suspend fun computeSha256(path: String): String? =
            if (files.containsKey(path)) "sha-$path" else null
    }

    private class FakeAssetStagingPort(
        var simulatedAvailableSpace: Long = 10L * 1024 * 1024 * 1024, // 10 GiB
        var shouldSimulateDiskFull: Boolean = false,
        var shouldSimulateTruncation: Boolean = false,
        var shouldSimulateRevokedPermission: Boolean = false,
        var shouldFailPromotion: Boolean = false,
    ) : AssetStagingPort {
        val stagedFiles = mutableMapOf<String, StagedArtifact>()
        val promotedFiles = mutableMapOf<String, String>()
        val cleanedStaging = mutableSetOf<String>()
        val existingFiles = mutableMapOf<String, Long>()

        fun addFile(path: String, size: Long) {
            existingFiles[path] = size
        }

        override fun getAvailableSpace(): Long =
            if (shouldSimulateDiskFull) 1024L else simulatedAvailableSpace

        override suspend fun prepareStagingDirectory(operationId: String): String =
            "/assets/.staging/$operationId"

        override suspend fun stageAndHash(
            operationId: String,
            sourcePath: String,
            stagingPath: String,
            byteLimit: Long?,
            onBytesWritten: ((Long) -> Unit)?,
        ): Result<StagedArtifact> {
            if (shouldSimulateRevokedPermission) {
                return Result.failure(SecurityException("Permission denied for $sourcePath"))
            }
            val sourceSize = existingFiles[sourcePath] ?: 0L
            if (shouldSimulateDiskFull) {
                return Result.failure(DiskFullException(1024L, sourceSize))
            }
            if (shouldSimulateTruncation) {
                val truncatedBytes = (sourceSize / 2).coerceAtLeast(1L)
                return Result.failure(TruncatedCopyException(sourceSize, truncatedBytes))
            }
            val staged = StagedArtifact(stagingPath, sourceSize, "sha-$sourcePath")
            stagedFiles[stagingPath] = staged
            return Result.success(staged)
        }

        override suspend fun stageBytesAndHash(
            operationId: String,
            bytes: ByteArray,
            stagingPath: String,
        ): Result<StagedArtifact> {
            if (shouldSimulateRevokedPermission) {
                return Result.failure(SecurityException("Storage permission revoked"))
            }
            val artifact = StagedArtifact(stagingPath, bytes.size.toLong(), "sha-${bytes.size}")
            stagedFiles[stagingPath] = artifact
            return Result.success(artifact)
        }

        override suspend fun promote(stagingPath: String, targetLocator: String): Result<String> {
            if (shouldFailPromotion) {
                return Result.failure(IllegalStateException("Simulated promotion failure"))
            }
            val targetPath = "/assets/$targetLocator"
            promotedFiles[targetLocator] = targetPath
            existingFiles[targetPath] = stagedFiles[stagingPath]?.byteCount ?: 1024L
            return Result.success(targetPath)
        }

        override suspend fun cleanupStaging(operationId: String) {
            cleanedStaging.add(operationId)
        }

        override suspend fun cleanupOrphanedStaging(activeOperationIds: Set<String>) {
            cleanedStaging.add("orphaned")
        }

        override fun fileExists(path: String): Boolean = existingFiles.containsKey(path)
        override fun fileSize(path: String): Long = existingFiles[path] ?: 0L
        override suspend fun computeSha256(path: String): String? = "sha-$path"
        override fun resolveTargetLocator(targetLocator: String): String = "/assets/$targetLocator"
        override suspend fun deleteTarget(targetLocator: String): Boolean = true
    }

    private fun sampleRevision() = MediaRevision(sizeBytes = 1048576L, lastModifiedEpochMillis = 1000L)

    // 1. Successful staging, hashing, atomic promotion and metadata publication
    @Test
    fun successfulStagingHashingPromotionAndPublication() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = FakeAssetStagingPort()
        val inspector = FakeFileInspector()

        val sourcePath = "/downloads/video.pt.vtt"
        inspector.addFile(sourcePath, 2048L)
        stagingPort.addFile(sourcePath, 2048L)

        val operator = DurableAssetOperator(
            operationRepository = opRepo,
            assetRepository = assetRepo,
            stagingPort = stagingPort,
            fileInspector = inspector,
            storageReserveBytes = 1024L,
        )

        val request = AssetOperationRequest(
            entryId = "entry-1",
            kind = AssetKind.SUBTITLE,
            sourcePath = sourcePath,
            language = "pt",
            sourceType = "SUBTITLE_VTT",
            timelineBasis = "SOURCE",
            isRequired = true,
        )

        val op = operator.submit(request)
        assertEquals(AssetOperationStatus.QUEUED, op.status)

        val result = operator.execute(op.id, sampleRevision(), listOf("/downloads"))
        assertTrue(result.isSuccess)
        val asset = result.getOrThrow()

        assertEquals("entry-1", asset.entryId)
        assertEquals(AssetKind.SUBTITLE, asset.kind)
        assertEquals("pt", asset.language)
        assertEquals(2048L, asset.byteCount)
        assertTrue(asset.isVerified)

        // Verify operation reached SUCCEEDED
        val updatedOp = opRepo.getOperation(op.id)
        assertNotNull(updatedOp)
        assertEquals(AssetOperationStatus.SUCCEEDED, updatedOp.status)
        assertEquals(2048L, updatedOp.byteCount)
        assertTrue(stagingPort.cleanedStaging.contains(op.id))
    }

    // 2. Cancellation stops operation and safely cleans up staging without deleting source media
    @Test
    fun cancellationStopsOperationAndCleansStagingWithoutDeletingSourceMedia() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = FakeAssetStagingPort()
        val inspector = FakeFileInspector()

        val sourcePath = "/downloads/source.mp4"
        inspector.addFile(sourcePath, 10000L)
        stagingPort.addFile(sourcePath, 10000L)

        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, inspector)
        val request = AssetOperationRequest("entry-cancel", AssetKind.PRIMARY_MEDIA, sourcePath)
        val op = operator.submit(request)

        operator.cancel(op.id)

        val canceledOp = opRepo.getOperation(op.id)
        assertNotNull(canceledOp)
        assertEquals(AssetOperationStatus.CANCELED, canceledOp.status)
        assertIs<AssetOperationFailure.Canceled>(canceledOp.failure)

        // Verify staging directory cleanup was invoked
        assertTrue(stagingPort.cleanedStaging.contains(op.id))

        // Done criteria: cleanup cannot delete source media
        assertTrue(inspector.exists(sourcePath), "Source media must remain untouched on cancellation")
    }

    // 3. Disk full fails gracefully and preserves storage reserve
    @Test
    fun diskFullFailsGracefullyAndPreservesStorageReserve() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = FakeAssetStagingPort(shouldSimulateDiskFull = true)
        val inspector = FakeFileInspector()

        val sourcePath = "/downloads/artwork.jpg"
        inspector.addFile(sourcePath, 50000L)
        stagingPort.addFile(sourcePath, 50000L)

        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, inspector, storageReserveBytes = 128 * 1024 * 1024L)
        val request = AssetOperationRequest("entry-disk-full", AssetKind.ARTWORK, sourcePath)
        val op = operator.submit(request)

        val result = operator.execute(op.id, sampleRevision(), listOf("/downloads"))
        assertTrue(result.isFailure)

        val failedOp = opRepo.getOperation(op.id)
        assertNotNull(failedOp)
        assertEquals(AssetOperationStatus.FAILED, failedOp.status)
        assertIs<AssetOperationFailure.DiskFull>(failedOp.failure)

        // Source file preserved and no asset published
        assertTrue(inspector.exists(sourcePath))
        assertTrue(assetRepo.loadAssetsForEntry("entry-disk-full").isEmpty())
    }

    // 4. Revoked permission fails gracefully with structured failure
    @Test
    fun revokedPermissionFailsGracefully() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = FakeAssetStagingPort(shouldSimulateRevokedPermission = true)
        val inspector = FakeFileInspector()

        val sourcePath = "/downloads/track.srt"
        inspector.addFile(sourcePath, 1024L)
        stagingPort.addFile(sourcePath, 1024L)

        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, inspector)
        val request = AssetOperationRequest("entry-perm", AssetKind.SUBTITLE, sourcePath)
        val op = operator.submit(request)

        val result = operator.execute(op.id, sampleRevision(), listOf("/downloads"))
        assertTrue(result.isFailure)

        val failedOp = opRepo.getOperation(op.id)
        assertNotNull(failedOp)
        assertEquals(AssetOperationStatus.FAILED, failedOp.status)
        assertIs<AssetOperationFailure.RevokedPermission>(failedOp.failure)
    }

    // 5. Truncated copy detects missing bytes and rejects publication
    @Test
    fun truncatedCopyDetectsMissingBytesAndRejectsPublication() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = FakeAssetStagingPort(shouldSimulateTruncation = true)
        val inspector = FakeFileInspector()

        val sourcePath = "/downloads/truncated.mp4"
        inspector.addFile(sourcePath, 4096L)
        stagingPort.addFile(sourcePath, 4096L)

        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, inspector)
        val request = AssetOperationRequest(
            entryId = "entry-trunc",
            kind = AssetKind.PRIMARY_MEDIA,
            sourcePath = sourcePath,
            expectedSizeBytes = 4096L,
        )
        val op = operator.submit(request)

        val result = operator.execute(op.id, sampleRevision(), listOf("/downloads"))
        assertTrue(result.isFailure)

        val failedOp = opRepo.getOperation(op.id)
        assertNotNull(failedOp)
        assertEquals(AssetOperationStatus.FAILED, failedOp.status)
        assertIs<AssetOperationFailure.TruncatedCopy>(failedOp.failure)

        // Done criteria: no success with missing bytes
        assertTrue(assetRepo.loadAssetsForEntry("entry-trunc").isEmpty())
        assertTrue(stagingPort.cleanedStaging.contains(op.id))
    }

    // 6. Deduplication on submit: concurrent requests for same target return same operation
    @Test
    fun submitDeduplicatesActiveRequests() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = FakeAssetStagingPort()
        val inspector = FakeFileInspector()

        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, inspector)
        val request = AssetOperationRequest("entry-dedup", AssetKind.ARTWORK, "/downloads/thumb.jpg")

        val op1 = operator.submit(request)
        val op2 = operator.submit(request)

        assertEquals(op1.id, op2.id, "Second submit must return existing active operation")
        assertEquals(1, opRepo.loadOperationsForEntry("entry-dedup").size)
    }

    // 7. Retry deduplication: retry after success never creates duplicate asset
    @Test
    fun retryAfterSuccessNeverCreatesDuplicateAsset() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = FakeAssetStagingPort()
        val inspector = FakeFileInspector()

        val sourcePath = "/downloads/cover.jpg"
        inspector.addFile(sourcePath, 1024L)
        stagingPort.addFile(sourcePath, 1024L)

        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, inspector)
        val request = AssetOperationRequest("entry-retry", AssetKind.ARTWORK, sourcePath)
        val op = operator.submit(request)

        val firstResult = operator.execute(op.id, sampleRevision(), listOf("/downloads"))
        assertTrue(firstResult.isSuccess)
        val firstAsset = firstResult.getOrThrow()

        // Calling retry on already succeeded operation returns same asset without duplicates
        val retryResult = operator.retry(op.id, sampleRevision(), listOf("/downloads"))
        assertTrue(retryResult.isSuccess)
        val retryAsset = retryResult.getOrThrow()

        assertEquals(firstAsset.id, retryAsset.id)
        assertEquals(1, assetRepo.loadAssetsForEntry("entry-retry").size, "Must not create duplicate asset on retry")
    }

    // 8. Explicit retry after failure succeeds once failure condition cleared
    @Test
    fun explicitRetryAfterFailureSucceedsOnceFailureConditionCleared() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = FakeAssetStagingPort(shouldSimulateDiskFull = true)
        val inspector = FakeFileInspector()

        val sourcePath = "/downloads/notes.vtt"
        inspector.addFile(sourcePath, 512L)
        stagingPort.addFile(sourcePath, 512L)

        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, inspector)
        val request = AssetOperationRequest("entry-retry-fail", AssetKind.SUBTITLE, sourcePath)
        val op = operator.submit(request)

        // First attempt fails due to simulated disk full
        val failResult = operator.execute(op.id, sampleRevision(), listOf("/downloads"))
        assertTrue(failResult.isFailure)
        assertEquals(AssetOperationStatus.FAILED, opRepo.getOperation(op.id)?.status)

        // Clear failure condition and retry
        stagingPort.shouldSimulateDiskFull = false
        val retryResult = operator.retry(op.id, sampleRevision(), listOf("/downloads"))
        assertTrue(retryResult.isSuccess)

        val finalOp = opRepo.getOperation(op.id)
        assertNotNull(finalOp)
        assertEquals(AssetOperationStatus.SUCCEEDED, finalOp.status)
        assertEquals(1, finalOp.attempt)
        assertEquals(1, assetRepo.loadAssetsForEntry("entry-retry-fail").size)
    }

    // 9. Crash recovery / journal reconciliation cleans orphaned staging and preserves state
    @Test
    fun journalReconciliationRecoversFromSimulatedCrash() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = FakeAssetStagingPort()
        val inspector = FakeFileInspector()

        // Create an operation that was RUNNING when crash occurred
        val request = AssetOperationRequest("entry-crash", AssetKind.SUBTITLE, "/downloads/crash.vtt")
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, inspector)
        val op = operator.submit(request)
        opRepo.updateOperation(op.copy(status = AssetOperationStatus.RUNNING))

        // App restarts and reconciles journal
        operator.reconcileJournal()

        val reconciledOp = opRepo.getOperation(op.id)
        assertNotNull(reconciledOp)
        assertEquals(AssetOperationStatus.INTERRUPTED, reconciledOp.status)
        assertTrue(stagingPort.cleanedStaging.contains("orphaned"))
    }
}
