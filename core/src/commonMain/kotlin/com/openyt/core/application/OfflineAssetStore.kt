package com.openyt.core.application

import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset

/**
 * Port for copying and managing confirmed engine companion artifacts in owned application storage.
 */
interface OfflineAssetStore {
    /**
     * Stores a validated companion artifact in application storage using a generated ID,
     * computes its SHA-256 digest, and persists the [OfflineAsset] record.
     */
    suspend fun storeConfirmedArtifact(
        entryId: String,
        receipt: CompanionArtifactReceipt,
        mediaRevision: MediaRevision,
        allowedRoots: List<String>,
    ): Result<OfflineAsset>

    suspend fun getAssetsForEntry(entryId: String): List<OfflineAsset>
}
