package com.openyt.data

import com.openyt.core.application.ArtifactValidationResult
import com.openyt.core.application.CompanionArtifactValidator
import com.openyt.core.application.MediaFileInspector
import com.openyt.core.application.OfflineAssetRepository
import com.openyt.core.application.OfflineAssetStore
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * JVM/Android implementation of [OfflineAssetStore] that safely copies confirmed engine
 * companion artifacts into the application-owned assets directory.
 */
class JvmOfflineAssetStore(
    private val assetsDirectory: File,
    private val repository: OfflineAssetRepository,
    private val fileInspector: MediaFileInspector = JvmMediaFileInspector(),
) : OfflineAssetStore {

    init {
        assetsDirectory.mkdirs()
    }

    override suspend fun storeConfirmedArtifact(
        entryId: String,
        receipt: CompanionArtifactReceipt,
        mediaRevision: MediaRevision,
        allowedRoots: List<String>,
    ): Result<OfflineAsset> = withContext(Dispatchers.IO) {
        val validation = CompanionArtifactValidator.validateReceipt(receipt, allowedRoots, fileInspector)
        if (validation !is ArtifactValidationResult.Valid) {
            return@withContext Result.failure(
                IllegalArgumentException("Invalid artifact receipt: $validation (path: ${receipt.filePath})")
            )
        }

        val sourceFile = File(receipt.filePath)
        val ext = sourceFile.extension.ifEmpty { "bin" }
        val assetId = "asset-${UUID.randomUUID()}"
        val entryDir = File(assetsDirectory, entryId).apply { mkdirs() }
        val targetFile = File(entryDir, "$assetId.$ext")

        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val sha256 = fileInspector.computeSha256(targetFile.absolutePath)
                ?: return@withContext Result.failure(IllegalStateException("Failed to compute SHA-256 for copied asset"))

            val asset = OfflineAsset(
                id = assetId,
                entryId = entryId,
                kind = receipt.kind,
                language = receipt.language,
                sourceType = receipt.sourceType,
                relativeLocator = "$entryId/$assetId.$ext",
                byteCount = targetFile.length(),
                sha256 = sha256,
                state = AssetState.VERIFIED,
                isRequired = receipt.isRequired,
                isPinned = false,
                mediaRevision = mediaRevision,
                createdAtEpochMillis = System.currentTimeMillis(),
                lastVerifiedEpochMillis = System.currentTimeMillis(),
            )

            repository.insertAsset(asset)
            Result.success(asset)
        } catch (e: Exception) {
            targetFile.delete()
            Result.failure(e)
        }
    }

    override suspend fun getAssetsForEntry(entryId: String): List<OfflineAsset> =
        repository.loadAssetsForEntry(entryId)
}
