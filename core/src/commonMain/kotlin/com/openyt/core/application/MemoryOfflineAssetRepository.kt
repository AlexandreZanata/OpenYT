package com.openyt.core.application

import com.openyt.core.domain.AssetState
import com.openyt.core.domain.OfflineAsset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe in-memory implementation of [OfflineAssetRepository] for fast tests and mocks.
 */
class MemoryOfflineAssetRepository : OfflineAssetRepository {
    private val mutex = Mutex()
    private val assets = mutableMapOf<String, OfflineAsset>()

    override suspend fun loadAssetsForEntry(entryId: String): List<OfflineAsset> = mutex.withLock {
        assets.values
            .filter { it.entryId == entryId }
            .sortedWith(compareBy({ it.kind }, { it.language ?: "" }, { it.id }))
    }

    override suspend fun loadAssetById(id: String): OfflineAsset? = mutex.withLock {
        assets[id]
    }

    override suspend fun loadAllAssets(): List<OfflineAsset> = mutex.withLock {
        assets.values.sortedByDescending { it.createdAtEpochMillis }
    }

    override suspend fun insertAsset(asset: OfflineAsset) = mutex.withLock {
        assets[asset.id] = asset
    }

    override suspend fun updateAssetState(
        id: String,
        state: AssetState,
        lastVerifiedEpochMillis: Long?,
        failureDetail: String?,
    ) = mutex.withLock {
        val existing = assets[id] ?: return@withLock
        assets[id] = existing.copy(
            state = state,
            lastVerifiedEpochMillis = lastVerifiedEpochMillis ?: existing.lastVerifiedEpochMillis,
            failureDetail = failureDetail ?: existing.failureDetail,
        )
    }

    override suspend fun updateAssetPin(id: String, isPinned: Boolean) = mutex.withLock {
        val existing = assets[id] ?: return@withLock
        assets[id] = existing.copy(isPinned = isPinned)
    }

    override suspend fun deleteAsset(id: String) = mutex.withLock {
        assets.remove(id)
        Unit
    }

    override suspend fun deleteAssetsForEntry(entryId: String) = mutex.withLock {
        val toRemove = assets.values.filter { it.entryId == entryId }.map { it.id }
        toRemove.forEach { assets.remove(it) }
    }

    override suspend fun invalidateAssetsForEntry(entryId: String, newState: AssetState) = mutex.withLock {
        val matching = assets.values.filter { it.entryId == entryId }
        matching.forEach { asset ->
            assets[asset.id] = asset.copy(state = newState)
        }
    }

    suspend fun clear() = mutex.withLock {
        assets.clear()
    }
}
