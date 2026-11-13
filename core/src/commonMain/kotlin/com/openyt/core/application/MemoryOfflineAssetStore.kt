package com.openyt.core.application

import com.openyt.core.domain.AssetState
import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset

/**
 * Thread-safe in-memory [OfflineAssetStore] for unit testing.
 */
class MemoryOfflineAssetStore(
    private val repository: OfflineAssetRepository = MemoryOfflineAssetRepository(),
    private val fileInspector: MediaFileInspector,
) : OfflineAssetStore {
    private var idCounter = 0

    override suspend fun storeConfirmedArtifact(
        entryId: String,
        receipt: CompanionArtifactReceipt,
        mediaRevision: MediaRevision,
        allowedRoots: List<String>,
    ): Result<OfflineAsset> {
        val validation = CompanionArtifactValidator.validateReceipt(receipt, allowedRoots, fileInspector)
        if (validation !is ArtifactValidationResult.Valid) {
            return Result.failure(IllegalArgumentException("Invalid artifact receipt: $validation"))
        }

        val id = "asset-${++idCounter}"
        val ext = receipt.filePath.substringAfterLast('.', "")
        val relativeLocator = "$entryId/$id${if (ext.isNotEmpty()) ".$ext" else ""}"
        val sha256 = fileInspector.computeSha256(receipt.filePath) ?: "digest-$id"

        val asset = OfflineAsset(
            id = id,
            entryId = entryId,
            kind = receipt.kind,
            language = receipt.language,
            sourceType = receipt.sourceType,
            relativeLocator = relativeLocator,
            byteCount = validation.sizeBytes,
            sha256 = sha256,
            state = AssetState.VERIFIED,
            isRequired = receipt.isRequired,
            isPinned = false,
            mediaRevision = mediaRevision,
            createdAtEpochMillis = 1000L,
            lastVerifiedEpochMillis = 1000L,
        )
        repository.insertAsset(asset)
        return Result.success(asset)
    }

    override suspend fun getAssetsForEntry(entryId: String): List<OfflineAsset> =
        repository.loadAssetsForEntry(entryId)

    override fun resolveAssetPath(relativeLocator: String): String =
        "/memory/assets/$relativeLocator"

    override suspend fun deleteOwnedAsset(asset: OfflineAsset): Boolean = true
}
