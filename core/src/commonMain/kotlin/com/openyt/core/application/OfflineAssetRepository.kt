package com.openyt.core.application

import com.openyt.core.domain.AssetState
import com.openyt.core.domain.OfflineAsset

/**
 * Persistence port for owned offline companion assets (subtitles, artwork, metadata).
 *
 * Owned assets are stored in application data using generated IDs, separate
 * from Coil's cache and original media files. Removing a record must never
 * delete the user's original media files.
 */
interface OfflineAssetRepository {
    suspend fun loadAssetsForEntry(entryId: String): List<OfflineAsset>

    suspend fun loadAssetById(id: String): OfflineAsset?

    suspend fun loadAllAssets(): List<OfflineAsset>

    suspend fun insertAsset(asset: OfflineAsset)

    suspend fun updateAssetState(
        id: String,
        state: AssetState,
        lastVerifiedEpochMillis: Long? = null,
        failureDetail: String? = null,
    )

    suspend fun updateAssetPin(id: String, isPinned: Boolean)

    suspend fun deleteAsset(id: String)

    suspend fun deleteAssetsForEntry(entryId: String)

    suspend fun invalidateAssetsForEntry(entryId: String, newState: AssetState = AssetState.STALE)
}
