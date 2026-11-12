package com.openyt.core.application

import com.openyt.core.domain.OfflineAsset

/**
 * Explicit user-driven maintenance of owned offline assets. Cleanup only removes
 * unpinned owned files and records; original user media is never touched.
 */
class OfflineAssetMaintenance(
    private val repository: OfflineAssetRepository,
    private val store: OfflineAssetStore,
) {

    suspend fun setPinned(entryId: String, assetId: String, isPinned: Boolean): Result<Unit> {
        val asset = repository.loadAssetById(assetId)
            ?: return Result.failure(IllegalArgumentException("Asset $assetId not found"))
        if (asset.entryId != entryId) {
            return Result.failure(IllegalArgumentException("Asset $assetId does not belong to entry $entryId"))
        }
        repository.updateAssetPin(assetId, isPinned)
        return Result.success(Unit)
    }

    suspend fun cleanupPreview(entryId: String): List<OfflineAsset> =
        repository.loadAssetsForEntry(entryId).filterNot { it.isPinned }

    suspend fun cleanup(entryId: String, assetIds: Set<String>): Result<Int> {
        if (assetIds.isEmpty()) return Result.success(0)
        val assets = repository.loadAssetsForEntry(entryId)
        var removed = 0
        for (asset in assets) {
            if (asset.id !in assetIds || asset.isPinned) continue
            if (!store.deleteOwnedAsset(asset)) {
                return Result.failure(IllegalStateException("Could not delete owned asset ${asset.id}"))
            }
            repository.deleteAsset(asset.id)
            removed++
        }
        return Result.success(removed)
    }
}
