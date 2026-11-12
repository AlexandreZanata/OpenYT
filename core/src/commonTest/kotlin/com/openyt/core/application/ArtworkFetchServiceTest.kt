package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperationException
import com.openyt.core.domain.AssetOperationFailure
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkFetchServiceTest {

    private class FakeArtworkFetchPort : ArtworkFetchPort {
        val requestedUrls = mutableListOf<String>()
        var simulatedResult: Result<FetchedArtworkMetadata>? = null
        var delayMillis: Long = 0L

        override suspend fun fetchArtworkTo(
            url: String,
            destinationPath: String,
            maxSizeBytes: Long,
        ): Result<FetchedArtworkMetadata> {
            requestedUrls.add(url)
            if (delayMillis > 0) {
                delay(delayMillis)
            }
            return simulatedResult ?: Result.success(
                FetchedArtworkMetadata(
                    byteCount = 5120L,
                    mimeType = "image/jpeg",
                    formatExtension = "jpg",
                    sha256 = "sha256-artwork-fake",
                )
            )
        }
    }

    private class TestAssetStagingPort : AssetStagingPort {
        var simulatedAvailableSpace: Long = 10L * 1024 * 1024 * 1024
        val staged = mutableMapOf<String, StagedArtifact>()
        val promoted = mutableMapOf<String, String>()
        val cleaned = mutableSetOf<String>()

        override fun getAvailableSpace(): Long = simulatedAvailableSpace
        override suspend fun prepareStagingDirectory(operationId: String): String = "/staging/$operationId"
        override suspend fun stageAndHash(
            operationId: String,
            sourcePath: String,
            stagingPath: String,
            byteLimit: Long?,
            onBytesWritten: ((Long) -> Unit)?,
        ): Result<StagedArtifact> {
            val artifact = StagedArtifact(stagingPath, 1024L, "sha-staged")
            staged[stagingPath] = artifact
            return Result.success(artifact)
        }

        override suspend fun stageBytesAndHash(
            operationId: String,
            bytes: ByteArray,
            stagingPath: String,
        ): Result<StagedArtifact> {
            val artifact = StagedArtifact(stagingPath, bytes.size.toLong(), "sha-${bytes.size}")
            staged[stagingPath] = artifact
            return Result.success(artifact)
        }

        override suspend fun promote(stagingPath: String, targetLocator: String): Result<String> {
            val promotedPath = "/assets/$targetLocator"
            promoted[targetLocator] = promotedPath
            return Result.success(promotedPath)
        }

        override suspend fun cleanupStaging(operationId: String) {
            cleaned.add(operationId)
        }

        override suspend fun cleanupOrphanedStaging(activeOperationIds: Set<String>) {}
        override fun fileExists(path: String): Boolean = true
        override fun fileSize(path: String): Long = 1024L
        override suspend fun computeSha256(path: String): String? = "sha256-computed"
        override fun resolveTargetLocator(targetLocator: String): String = "/assets/$targetLocator"
    }

    private class FakeFileInspector : MediaFileInspector {
        override fun exists(path: String): Boolean = true
        override fun inspect(path: String): FileMetadata? =
            FileMetadata(sizeBytes = 1024L, lastModifiedEpochMillis = 1000L)
        override suspend fun computeSha256(path: String): String? = "sha-inspect"
    }

    private fun sampleRevision() = MediaRevision(sizeBytes = 1048576L, lastModifiedEpochMillis = 1000L)

    @Test
    fun fetchesAndPromotesArtworkAtomically() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort()
        val fetchPort = FakeArtworkFetchPort()
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val service = ArtworkFetchService(operator, fetchPort, assetRepo, stagingPort)
        val result = service.fetchArtwork(
            entryId = "entry-art-1",
            url = "https://example.com/thumbnails/video1.jpg",
            mediaRevision = sampleRevision(),
        )

        assertTrue(result.isSuccess)
        val asset = result.getOrThrow()
        assertEquals(AssetKind.ARTWORK, asset.kind)
        assertEquals(AssetState.VERIFIED, asset.state)
        assertEquals(5120L, asset.byteCount)
        assertEquals("sha256-artwork-fake", asset.sha256)
        assertTrue(asset.relativeLocator.startsWith("entry-art-1/art-"))
        assertTrue(asset.relativeLocator.endsWith(".jpg"))

        // Verified in repository
        val stored = assetRepo.loadAssetsForEntry("entry-art-1")
        assertEquals(1, stored.size)
        assertEquals(asset.id, stored.single().id)
    }

    @Test
    fun stripsCredentialsFromUrlBeforeCallingPort() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort()
        val fetchPort = FakeArtworkFetchPort()
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val service = ArtworkFetchService(operator, fetchPort, assetRepo, stagingPort)
        val credUrl = "https://secretUser:secretPass@cdn.example.com/art.png"

        val result = service.fetchArtwork("entry-cred", credUrl, sampleRevision())
        assertTrue(result.isSuccess)

        assertEquals(listOf("https://cdn.example.com/art.png"), fetchPort.requestedUrls)
        assertFalse(fetchPort.requestedUrls.first().contains("secretPass"))
    }

    @Test
    fun deduplicatesConcurrentFetchesForSameEntryAndUrl() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort()
        val fetchPort = FakeArtworkFetchPort().apply { delayMillis = 50L }
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val service = ArtworkFetchService(operator, fetchPort, assetRepo, stagingPort)
        val url = "https://example.com/thumb.jpg"

        val firstAsync = async { service.fetchArtwork("entry-dedup", url, sampleRevision()) }
        val secondAsync = async { service.fetchArtwork("entry-dedup", url, sampleRevision()) }

        val res1 = firstAsync.await()
        val res2 = secondAsync.await()

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertEquals(res1.getOrThrow().id, res2.getOrThrow().id)
        assertEquals(1, fetchPort.requestedUrls.size, "Must only invoke network fetch once for concurrent duplicates")
    }

    @Test
    fun failedFetchNeverDestroysExistingVerifiedArtwork() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort()
        val fetchPort = FakeArtworkFetchPort()
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        // Pre-existing verified artwork
        val existingVerified = OfflineAsset(
            id = "art-existing",
            entryId = "entry-preserve",
            kind = AssetKind.ARTWORK,
            relativeLocator = "entry-preserve/art-existing.jpg",
            byteCount = 4096L,
            sha256 = "existing-sha",
            state = AssetState.VERIFIED,
            mediaRevision = sampleRevision(),
        )
        assetRepo.insertAsset(existingVerified)

        // Simulate network failure
        fetchPort.simulatedResult = Result.failure(AssetOperationFailure.NetworkOffline().toException())

        val service = ArtworkFetchService(operator, fetchPort, assetRepo, stagingPort)
        val result = service.fetchArtwork("entry-preserve", "https://example.com/new.jpg", sampleRevision())

        assertTrue(result.isFailure)

        // Existing verified asset is STILL verified and intact
        val assets = assetRepo.loadAssetsForEntry("entry-preserve")
        assertEquals(1, assets.size)
        val preserved = assets.single()
        assertEquals("art-existing", preserved.id)
        assertEquals(AssetState.VERIFIED, preserved.state)
        assertEquals("existing-sha", preserved.sha256)
    }

    @Test
    fun cancellationCleansStagingAndPreservesExistingAssets() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort()
        val fetchPort = FakeArtworkFetchPort().apply { delayMillis = 1000L }
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val service = ArtworkFetchService(operator, fetchPort, assetRepo, stagingPort)
        val job = async { service.fetchArtwork("entry-cancel", "https://example.com/art.jpg", sampleRevision()) }

        delay(10)
        job.cancel()

        try {
            job.await()
        } catch (_: CancellationException) {}

        // Staging cleaned
        assertTrue(stagingPort.cleaned.isNotEmpty(), "Staging must be cleaned up on cancellation")
        assertTrue(assetRepo.loadAssetsForEntry("entry-cancel").isEmpty())
    }

    @Test
    fun rejectsDisallowedSchemesWithoutNetworkCall() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort()
        val fetchPort = FakeArtworkFetchPort()
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val service = ArtworkFetchService(operator, fetchPort, assetRepo, stagingPort)
        val result = service.fetchArtwork("entry-scheme", "file:///etc/shadow", sampleRevision())

        assertTrue(result.isFailure)
        assertTrue(fetchPort.requestedUrls.isEmpty(), "Network fetch port must NOT be called for invalid scheme")
    }

    @Test
    fun diskFullAbortsOperationPreservingReserve() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort().apply {
            simulatedAvailableSpace = 1024L // Less than byteCount + 128 MiB reserve
        }
        val fetchPort = FakeArtworkFetchPort()
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val service = ArtworkFetchService(operator, fetchPort, assetRepo, stagingPort)
        val result = service.fetchArtwork("entry-df", "https://example.com/art.jpg", sampleRevision())

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertIs<AssetOperationException>(ex)
        assertIs<AssetOperationFailure.DiskFull>(ex.failure)
        assertTrue(assetRepo.loadAssetsForEntry("entry-df").isEmpty())
    }
}
