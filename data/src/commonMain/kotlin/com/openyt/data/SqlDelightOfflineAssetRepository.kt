package com.openyt.data

import com.openyt.core.application.OfflineAssetRepository
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.data.db.Offline_asset
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlDelightOfflineAssetRepository(
    private val database: OpenYtDatabase,
) : OfflineAssetRepository {
    private val queries = database.downloadQueries

    override suspend fun loadAssetsForEntry(entryId: String): List<OfflineAsset> = withContext(Dispatchers.Default) {
        queries.selectAssetsForEntry(entryId).executeAsList().map { it.toModel() }
    }

    override suspend fun loadAssetById(id: String): OfflineAsset? = withContext(Dispatchers.Default) {
        queries.selectAssetById(id).executeAsOneOrNull()?.toModel()
    }

    override suspend fun loadAllAssets(): List<OfflineAsset> = withContext(Dispatchers.Default) {
        queries.selectAllAssets().executeAsList().map { it.toModel() }
    }

    override suspend fun insertAsset(asset: OfflineAsset) = withContext(Dispatchers.Default) {
        queries.insertAsset(
            id = asset.id,
            entry_id = asset.entryId,
            kind = asset.kind.name,
            language = asset.language,
            source_type = asset.sourceType,
            relative_locator = asset.relativeLocator,
            byte_count = asset.byteCount,
            sha256 = asset.sha256,
            state = asset.state.name,
            is_required = if (asset.isRequired) 1L else 0L,
            is_pinned = if (asset.isPinned) 1L else 0L,
            media_revision_size = asset.mediaRevision.sizeBytes,
            media_revision_mtime = asset.mediaRevision.lastModifiedEpochMillis,
            media_revision_sha256 = asset.mediaRevision.contentSha256,
            created_at = asset.createdAtEpochMillis,
            last_verified_at = asset.lastVerifiedEpochMillis,
            failure_detail = asset.failureDetail,
        )
        Unit
    }

    override suspend fun updateAssetState(
        id: String,
        state: AssetState,
        lastVerifiedEpochMillis: Long?,
        failureDetail: String?,
    ) = withContext(Dispatchers.Default) {
        queries.updateAssetState(
            state = state.name,
            last_verified_at = lastVerifiedEpochMillis,
            failure_detail = failureDetail,
            id = id,
        )
        Unit
    }

    override suspend fun updateAssetPin(id: String, isPinned: Boolean) = withContext(Dispatchers.Default) {
        queries.updateAssetPin(
            is_pinned = if (isPinned) 1L else 0L,
            id = id,
        )
        Unit
    }

    override suspend fun deleteAsset(id: String) = withContext(Dispatchers.Default) {
        queries.deleteAsset(id)
        Unit
    }

    override suspend fun deleteAssetsForEntry(entryId: String) = withContext(Dispatchers.Default) {
        queries.deleteAssetsForEntry(entryId)
        Unit
    }

    override suspend fun invalidateAssetsForEntry(entryId: String, newState: AssetState) = withContext(Dispatchers.Default) {
        queries.invalidateAssetsForEntry(
            state = newState.name,
            entry_id = entryId,
        )
        Unit
    }

    private fun Offline_asset.toModel(): OfflineAsset = OfflineAsset(
        id = id,
        entryId = entry_id,
        kind = runCatching { AssetKind.valueOf(kind) }.getOrDefault(AssetKind.PRIMARY_MEDIA),
        language = language,
        sourceType = source_type,
        relativeLocator = relative_locator,
        byteCount = byte_count,
        sha256 = sha256,
        state = runCatching { AssetState.valueOf(state) }.getOrDefault(AssetState.PENDING),
        isRequired = is_required == 1L,
        isPinned = is_pinned == 1L,
        mediaRevision = MediaRevision(
            sizeBytes = media_revision_size,
            lastModifiedEpochMillis = media_revision_mtime,
            contentSha256 = media_revision_sha256,
        ),
        createdAtEpochMillis = created_at,
        lastVerifiedEpochMillis = last_verified_at,
        failureDetail = failure_detail,
    )
}
