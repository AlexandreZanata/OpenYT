package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.CompanionArtifactReceipt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Publication may be retried after the asset commit but before the clip commit. */
class ClipCoverPublisher(private val store: OfflineAssetStore?, private val inspector: MediaFileInspector) {
    private val mutex = Mutex()

    suspend fun publish(output: ProcessedMedia) = mutex.withLock {
        val source = output.coverPath ?: return@withLock
        val assets = checkNotNull(store)
        val revision = checkNotNull(output.mediaRevision)
        val root = checkNotNull(output.artifactRoot)
        val digest = checkNotNull(output.coverSha256)
        check(inspector.computeSha256(source) == digest)
        for (asset in assets.getAssetsForEntry(output.id)) {
            if (asset.kind != AssetKind.ARTWORK || !asset.isVerified || asset.sha256 != digest || asset.mediaRevision != revision) continue
            val path = assets.resolveAssetPath(asset.relativeLocator)
            if (inspector.inspect(path)?.sizeBytes == asset.byteCount && inspector.computeSha256(path) == digest) return@withLock
        }
        assets.storeConfirmedArtifact(output.id, CompanionArtifactReceipt(AssetKind.ARTWORK, source), revision, listOf(root)).getOrThrow()
        Unit
    }
}
