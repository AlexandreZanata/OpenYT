package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.OfflineReadiness
import com.openyt.core.domain.findVerifiedArtwork

/**
 * Presentation-friendly view of a single owned offline asset.
 */
data class OfflineAssetView(
    val id: String,
    val kind: AssetKind,
    val language: String?,
    val byteCount: Long,
    val isPinned: Boolean,
    val state: AssetState,
) {
    val canCleanup: Boolean get() = !isPinned
}

/**
 * Storage totals kept separate so users can distinguish original media from
 * disposable rendering cache and durable owned assets.
 */
data class OfflineStorageBreakdown(
    val originalMediaBytes: Long,
    val durableAssetBytes: Long,
    val renderingCacheBytes: Long,
) {
    val managedBytes: Long get() = durableAssetBytes + renderingCacheBytes
}

/**
 * Deterministic snapshot of what is available offline for one library entry.
 */
data class OfflineLibraryState(
    val entryId: String,
    val readiness: OfflineReadiness,
    val localArtworkPath: String?,
    val remoteArtworkUrl: String?,
    val assets: List<OfflineAssetView>,
    val storage: OfflineStorageBreakdown,
) {
    /**
     * Verified owned artwork is always preferred. A remote URL is only a fallback
     * and never indicates offline readiness by itself.
     */
    val artworkModel: String? get() = localArtworkPath ?: remoteArtworkUrl
    val hasVerifiedLocalArtwork: Boolean get() = localArtworkPath != null
}

object OfflineLibraryPresenter {

    fun present(
        entry: LibraryEntry,
        assets: List<OfflineAsset>,
        remoteArtworkUrl: String?,
        renderingCacheBytes: Long,
        fileInspector: MediaFileInspector,
        resolveAssetPath: (String) -> String,
    ): OfflineLibraryState {
        val readiness = OfflineReadinessCalculator.computeReadiness(entry, assets, fileInspector)
        val verifiedArtwork = assets.findVerifiedArtwork()
        return OfflineLibraryState(
            entryId = entry.id,
            readiness = readiness,
            localArtworkPath = verifiedArtwork?.let { resolveAssetPath(it.relativeLocator) },
            remoteArtworkUrl = remoteArtworkUrl,
            assets = assets.map { it.toView() },
            storage = OfflineStorageBreakdown(
                originalMediaBytes = entry.sizeBytes ?: 0L,
                durableAssetBytes = assets.sumOf { it.byteCount },
                renderingCacheBytes = renderingCacheBytes,
            ),
        )
    }

    private fun OfflineAsset.toView() = OfflineAssetView(
        id = id,
        kind = kind,
        language = language,
        byteCount = byteCount,
        isPinned = isPinned,
        state = state,
    )
}
