package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.ReadinessStatus
import com.openyt.core.domain.RecoveryAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineLibraryPresenterTest {

    private val revision = MediaRevision(sizeBytes = 1000L, lastModifiedEpochMillis = 100L)

    private val inspector = object : MediaFileInspector {
        override fun exists(path: String): Boolean = true
        override fun inspect(path: String): FileMetadata =
            FileMetadata(sizeBytes = 1000L, lastModifiedEpochMillis = 100L)
        override suspend fun computeSha256(path: String): String = "sha-media"
    }

    private fun entry(thumbnailUrl: String? = "https://remote.example/thumb.jpg") = LibraryEntry(
        id = "entry-1",
        title = "Sample",
        kind = MediaKind.VIDEO,
        filePath = "/media/video.mp4",
        sizeBytes = 1000L,
        thumbnailUrl = thumbnailUrl,
        mediaRevision = revision,
    )

    private fun artwork(
        id: String = "art-1",
        state: AssetState = AssetState.VERIFIED,
        isRequired: Boolean = false,
        isPinned: Boolean = false,
        bytes: Long = 200L,
    ) = OfflineAsset(
        id = id,
        entryId = "entry-1",
        kind = AssetKind.ARTWORK,
        relativeLocator = "entry-1/$id.jpg",
        byteCount = bytes,
        sha256 = "sha-$id",
        state = state,
        isRequired = isRequired,
        isPinned = isPinned,
        mediaRevision = revision,
    )

    private fun subtitle(
        id: String = "sub-1",
        isPinned: Boolean = false,
        bytes: Long = 300L,
    ) = OfflineAsset(
        id = id,
        entryId = "entry-1",
        kind = AssetKind.SUBTITLE,
        language = "en",
        relativeLocator = "entry-1/$id.srt",
        byteCount = bytes,
        sha256 = "sha-$id",
        state = AssetState.VERIFIED,
        isPinned = isPinned,
        mediaRevision = revision,
    )

    private class TrackingStore : OfflineAssetStore {
        val deleted = mutableSetOf<String>()

        override suspend fun storeConfirmedArtifact(
            entryId: String,
            receipt: CompanionArtifactReceipt,
            mediaRevision: MediaRevision,
            allowedRoots: List<String>,
        ): Result<OfflineAsset> = Result.failure(UnsupportedOperationException())

        override suspend fun getAssetsForEntry(entryId: String): List<OfflineAsset> = emptyList()

        override fun resolveAssetPath(relativeLocator: String): String = "/assets/$relativeLocator"

        override suspend fun deleteOwnedAsset(asset: OfflineAsset): Boolean {
            deleted += asset.relativeLocator
            return true
        }
    }

    @Test
    fun verifiedLocalArtworkIsPreferredOverRemoteUrl() {
        val state = OfflineLibraryPresenter.present(
            entry = entry(),
            assets = listOf(artwork()),
            remoteArtworkUrl = "https://remote.example/thumb.jpg",
            renderingCacheBytes = 50L,
            fileInspector = inspector,
            resolveAssetPath = { "/assets/$it" },
        )

        assertEquals("/assets/entry-1/art-1.jpg", state.artworkModel)
        assertEquals("/assets/entry-1/art-1.jpg", state.localArtworkPath)
        assertTrue(state.hasVerifiedLocalArtwork)
        assertEquals(ReadinessStatus.READY, state.readiness.status)
    }

    @Test
    fun remoteUrlAloneNeverClaimsVerifiedLocalArtwork() {
        val pendingArtwork = artwork(state = AssetState.PENDING, isRequired = true)
        val state = OfflineLibraryPresenter.present(
            entry = entry(),
            assets = listOf(pendingArtwork),
            remoteArtworkUrl = "https://remote.example/thumb.jpg",
            renderingCacheBytes = 0L,
            fileInspector = inspector,
            resolveAssetPath = { "/assets/$it" },
        )

        assertNull(state.localArtworkPath)
        assertFalse(state.hasVerifiedLocalArtwork)
        assertEquals("https://remote.example/thumb.jpg", state.artworkModel)
        assertEquals(ReadinessStatus.PENDING, state.readiness.status)
        assertFalse(state.readiness.isReady)
    }

    @Test
    fun missingRequiredArtworkSuggestsFetchAction() {
        val state = OfflineLibraryPresenter.present(
            entry = entry(),
            assets = listOf(artwork(state = AssetState.PENDING, isRequired = true)),
            remoteArtworkUrl = "https://remote.example/thumb.jpg",
            renderingCacheBytes = 0L,
            fileInspector = inspector,
            resolveAssetPath = { "/assets/$it" },
        )

        val missing = state.readiness.missingRequirements.single()
        assertEquals(AssetKind.ARTWORK, missing.kind)
        assertEquals(RecoveryAction.FETCH_ARTWORK, missing.suggestedAction)
    }

    @Test
    fun storageBreakdownSeparatesMediaAssetsAndRenderingCache() {
        val state = OfflineLibraryPresenter.present(
            entry = entry(),
            assets = listOf(artwork(bytes = 200L), subtitle(bytes = 300L)),
            remoteArtworkUrl = null,
            renderingCacheBytes = 50L,
            fileInspector = inspector,
            resolveAssetPath = { "/assets/$it" },
        )

        assertEquals(1000L, state.storage.originalMediaBytes)
        assertEquals(500L, state.storage.durableAssetBytes)
        assertEquals(50L, state.storage.renderingCacheBytes)
        assertEquals(550L, state.storage.managedBytes)
    }

    @Test
    fun pinAndUnpinUpdateAssetState() = runTest {
        val repository = MemoryOfflineAssetRepository()
        repository.insertAsset(artwork())
        val maintenance = OfflineAssetMaintenance(repository, TrackingStore())

        assertTrue(maintenance.setPinned("entry-1", "art-1", true).isSuccess)
        assertTrue(repository.loadAssetById("art-1")!!.isPinned)

        assertTrue(maintenance.setPinned("entry-1", "art-1", false).isSuccess)
        assertFalse(repository.loadAssetById("art-1")!!.isPinned)
    }

    @Test
    fun pinRejectsAssetFromAnotherEntry() = runTest {
        val repository = MemoryOfflineAssetRepository()
        repository.insertAsset(artwork())
        val maintenance = OfflineAssetMaintenance(repository, TrackingStore())

        assertTrue(maintenance.setPinned("other-entry", "art-1", true).isFailure)
        assertFalse(repository.loadAssetById("art-1")!!.isPinned)
    }

    @Test
    fun cleanupPreviewExcludesPinnedAssets() = runTest {
        val repository = MemoryOfflineAssetRepository()
        repository.insertAsset(artwork(id = "pinned", isPinned = true))
        repository.insertAsset(subtitle(id = "loose"))
        val maintenance = OfflineAssetMaintenance(repository, TrackingStore())

        val preview = maintenance.cleanupPreview("entry-1")
        assertEquals(listOf("loose"), preview.map { it.id })
    }

    @Test
    fun cleanupDeletesOnlyUnpinnedOwnedAssets() = runTest {
        val repository = MemoryOfflineAssetRepository()
        repository.insertAsset(artwork(id = "pinned", isPinned = true))
        repository.insertAsset(subtitle(id = "loose"))
        val store = TrackingStore()
        val maintenance = OfflineAssetMaintenance(repository, store)

        val preview = maintenance.cleanupPreview("entry-1").map { it.id }.toSet()
        val removed = maintenance.cleanup("entry-1", preview).getOrThrow()

        assertEquals(1, removed)
        assertEquals(setOf("pinned"), repository.loadAssetsForEntry("entry-1").map { it.id }.toSet())
        assertEquals(setOf("entry-1/loose.srt"), store.deleted)
    }

    @Test
    fun cleanupNeverRemovesPinnedEvenWhenRequestedExplicitly() = runTest {
        val repository = MemoryOfflineAssetRepository()
        repository.insertAsset(artwork(id = "pinned", isPinned = true))
        val store = TrackingStore()
        val maintenance = OfflineAssetMaintenance(repository, store)

        val removed = maintenance.cleanup("entry-1", setOf("art-1")).getOrThrow()

        assertEquals(0, removed)
        assertTrue(store.deleted.isEmpty())
        assertEquals(1, repository.loadAssetsForEntry("entry-1").size)
    }
}
