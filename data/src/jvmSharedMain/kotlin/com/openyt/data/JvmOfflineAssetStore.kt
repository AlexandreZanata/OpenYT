package com.openyt.data

import com.openyt.core.application.AssetOperationRepository
import com.openyt.core.application.DurableAssetOperator
import com.openyt.core.application.MediaFileInspector
import com.openyt.core.application.MemoryAssetOperationRepository
import com.openyt.core.application.OfflineAssetRepository
import com.openyt.core.application.OfflineAssetStore
import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import java.io.File

/**
 * JVM/Android implementation of [OfflineAssetStore] that executes durable, journaled,
 * staged, and crash-recoverable operations via [DurableAssetOperator].
 */
class JvmOfflineAssetStore(
    private val assetsDirectory: File,
    private val repository: OfflineAssetRepository,
    private val operationRepository: AssetOperationRepository = MemoryAssetOperationRepository(),
    private val fileInspector: MediaFileInspector = JvmMediaFileInspector(),
    val stagingPort: JvmAssetStagingPort = JvmAssetStagingPort(assetsDirectory),
    val storageReserveBytes: Long = com.openyt.core.domain.AppSettings.DEFAULT_STORAGE_RESERVE_BYTES,
    val assetBudgetBytes: Long = com.openyt.core.domain.AppSettings.DEFAULT_ASSET_BUDGET_BYTES,
    private val storageReserveBytesProvider: () -> Long = { storageReserveBytes },
    private val assetBudgetBytesProvider: () -> Long = { assetBudgetBytes },
) : OfflineAssetStore {

    val operator: DurableAssetOperator = DurableAssetOperator(
        operationRepository = operationRepository,
        assetRepository = repository,
        stagingPort = stagingPort,
        fileInspector = fileInspector,
        storageReserveBytes = storageReserveBytes,
        assetBudgetBytes = assetBudgetBytes,
        storageReserveBytesProvider = storageReserveBytesProvider,
        assetBudgetBytesProvider = assetBudgetBytesProvider,
    )

    override suspend fun storeConfirmedArtifact(
        entryId: String,
        receipt: CompanionArtifactReceipt,
        mediaRevision: MediaRevision,
        allowedRoots: List<String>,
    ): Result<OfflineAsset> =
        operator.storeConfirmedArtifact(entryId, receipt, mediaRevision, allowedRoots)

    override suspend fun getAssetsForEntry(entryId: String): List<OfflineAsset> =
        operator.getAssetsForEntry(entryId)

    override fun resolveAssetPath(relativeLocator: String): String =
        File(assetsDirectory, relativeLocator).absolutePath

    override suspend fun deleteOwnedAsset(asset: OfflineAsset): Boolean =
        operator.deleteOwnedAsset(asset)
}
