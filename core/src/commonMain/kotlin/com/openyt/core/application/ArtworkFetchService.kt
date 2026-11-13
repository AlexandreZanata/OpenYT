package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperationFailure
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates safe fetching of artwork images, deduplicating in-flight requests,
 * validating URLs, staging and promoting into owned storage, computing SHA-256 digests,
 * and registering verified assets in SQLite.
 *
 * Critical Invariants:
 * - A failed asset fetch never destroys or overwrites an already verified asset.
 * - Credential-bearing URLs are stripped of user/pass credentials.
 * - Concurrent duplicate requests for the same entry + URL are deduplicated.
 * - Staged files are isolated and cleaned up upon failure or cancellation.
 */
class ArtworkFetchService(
    private val operator: DurableAssetOperator,
    private val fetchPort: ArtworkFetchPort,
    private val assetRepository: OfflineAssetRepository,
    private val stagingPort: AssetStagingPort,
) {
    private val dedupMutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Result<OfflineAsset>>>()

    suspend fun fetchArtwork(
        entryId: String,
        url: String,
        mediaRevision: MediaRevision,
        isRequired: Boolean = false,
        maxSizeBytes: Long = ArtworkFetchPort.DEFAULT_MAX_ARTWORK_BYTES,
    ): Result<OfflineAsset> {
        // 1. Validate & sanitize URL
        val validation = SafeUrlSanitizer.validateArtworkUrl(url)
        if (validation.isFailure) {
            val ex = validation.exceptionOrNull()
            return Result.failure<OfflineAsset>(
                AssetOperationFailure.VerificationFailed(ex?.message ?: "Invalid artwork URL: $url")
                    .toException()
            )
        }
        val cleanUrl = validation.getOrThrow()
        val dedupKey = "$entryId:$cleanUrl"

        // 2. Deduplicate concurrent requests
        val (isOwner, deferred) = dedupMutex.withLock {
            val existing = inFlight[dedupKey]
            if (existing != null) {
                false to existing
            } else {
                val newDeferred = CompletableDeferred<Result<OfflineAsset>>()
                inFlight[dedupKey] = newDeferred
                true to newDeferred
            }
        }

        if (!isOwner) {
            return deferred.await()
        }

        var opId: String? = null
        try {
            val result = executeFetch(entryId, cleanUrl, mediaRevision, isRequired, maxSizeBytes) { createdOpId ->
                opId = createdOpId
            }

            dedupMutex.withLock {
                inFlight.remove(dedupKey)
            }
            deferred.complete(result)
            return result
        } catch (e: CancellationException) {
            opId?.let { stagingPort.cleanupStaging(it) }
            val canceledResult = Result.failure<OfflineAsset>(AssetOperationFailure.Canceled.toException())
            dedupMutex.withLock {
                inFlight.remove(dedupKey)
            }
            deferred.complete(canceledResult)
            throw e
        } catch (t: Throwable) {
            opId?.let { stagingPort.cleanupStaging(it) }
            val failureResult = Result.failure<OfflineAsset>(t)
            dedupMutex.withLock {
                inFlight.remove(dedupKey)
            }
            deferred.complete(failureResult)
            return failureResult
        }
    }

    private suspend fun executeFetch(
        entryId: String,
        cleanUrl: String,
        mediaRevision: MediaRevision,
        isRequired: Boolean,
        maxSizeBytes: Long,
        onOpIdCreated: (String) -> Unit,
    ): Result<OfflineAsset> {
        val opId = operator.generateOperationId()
        onOpIdCreated(opId)

        val stagingDir = stagingPort.prepareStagingDirectory(opId)
        val stagingPath = "$stagingDir/artwork_download.part"

        // Perform streaming fetch to isolated staging file
        val fetchResult = fetchPort.fetchArtworkTo(cleanUrl, stagingPath, maxSizeBytes)
        if (fetchResult.isFailure) {
            stagingPort.cleanupStaging(opId)
            return Result.failure(fetchResult.exceptionOrNull() ?: IllegalStateException("Artwork fetch failed"))
        }

        val metadata = fetchResult.getOrThrow()
        val ext = metadata.formatExtension.ifEmpty { "jpg" }
        val targetLocator = "$entryId/art-$opId.$ext"

        // Check bounds against storage reserve and the asset budget
        val available = stagingPort.getAvailableSpace()
        val required = metadata.byteCount + operator.effectiveStorageReserveBytes()
        if (available < required) {
            stagingPort.cleanupStaging(opId)
            return Result.failure(
                AssetOperationFailure.DiskFull(available, required).toException()
            )
        }
        val budgetCheck = operator.ensureWithinBudget(metadata.byteCount)
        if (budgetCheck.isFailure) {
            stagingPort.cleanupStaging(opId)
            return Result.failure(
                budgetCheck.exceptionOrNull() ?: IllegalStateException("Asset budget check failed")
            )
        }

        // Promote staged file atomically into owned storage
        val promoteResult = stagingPort.promote(stagingPath, targetLocator)
        if (promoteResult.isFailure) {
            stagingPort.cleanupStaging(opId)
            return Result.failure(promoteResult.exceptionOrNull() ?: IllegalStateException("Promotion failed"))
        }

        // Register in offline asset repository
        val assetId = "art-$opId"
        val now = currentTimeMillis()
        val offlineAsset = OfflineAsset(
            id = assetId,
            entryId = entryId,
            kind = AssetKind.ARTWORK,
            language = null,
            sourceType = "FETCHED",
            relativeLocator = targetLocator,
            byteCount = metadata.byteCount,
            sha256 = metadata.sha256,
            state = AssetState.VERIFIED,
            isRequired = isRequired,
            isPinned = false,
            mediaRevision = mediaRevision,
            createdAtEpochMillis = now,
            lastVerifiedEpochMillis = now,
            failureDetail = null,
        )

        assetRepository.insertAsset(offlineAsset)
        stagingPort.cleanupStaging(opId)
        return Result.success(offlineAsset)
    }

    private fun currentTimeMillis(): Long =
        kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds + 1700000000000L
}
