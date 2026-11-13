package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperationException
import com.openyt.core.domain.AssetOperationFailure
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleImportServiceTest {

    private class TestAssetStagingPort : AssetStagingPort {
        var simulatedAvailableSpace: Long = 10L * 1024 * 1024 * 1024
        var simulateRevokedPermission: Boolean = false
        val existingFiles = mutableMapOf<String, Long>()
        val staged = mutableMapOf<String, StagedArtifact>()
        val promoted = mutableMapOf<String, String>()
        val cleaned = mutableSetOf<String>()

        fun addFile(path: String, size: Long) {
            existingFiles[path] = size
        }

        override fun getAvailableSpace(): Long = simulatedAvailableSpace
        override suspend fun prepareStagingDirectory(operationId: String): String = "/staging/$operationId"

        override suspend fun stageAndHash(
            operationId: String,
            sourcePath: String,
            stagingPath: String,
            byteLimit: Long?,
            onBytesWritten: ((Long) -> Unit)?,
        ): Result<StagedArtifact> {
            if (simulateRevokedPermission) {
                return Result.failure(SecurityException("Permission denied for $sourcePath"))
            }
            val size = existingFiles[sourcePath] ?: 1024L
            val artifact = StagedArtifact(stagingPath, size, "sha-sub-$size")
            staged[stagingPath] = artifact
            return Result.success(artifact)
        }

        override suspend fun stageBytesAndHash(
            operationId: String,
            bytes: ByteArray,
            stagingPath: String,
        ): Result<StagedArtifact> {
            if (simulateRevokedPermission) {
                return Result.failure(SecurityException("Permission denied for $stagingPath"))
            }
            val artifact = StagedArtifact(stagingPath, bytes.size.toLong(), "sha-bytes-${bytes.size}")
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
        override fun fileExists(path: String): Boolean = existingFiles.containsKey(path)
        override fun fileSize(path: String): Long = existingFiles[path] ?: 0L
        override suspend fun computeSha256(path: String): String? = "sha256-computed"
        override fun resolveTargetLocator(targetLocator: String): String = "/assets/$targetLocator"
        override suspend fun deleteTarget(targetLocator: String): Boolean = true
    }

    private class FakeFileInspector : MediaFileInspector {
        override fun exists(path: String): Boolean = true
        override fun inspect(path: String): FileMetadata? =
            FileMetadata(sizeBytes = 1024L, lastModifiedEpochMillis = 1000L)
        override suspend fun computeSha256(path: String): String? = "sha-inspect"
    }

    private fun sampleRevision() = MediaRevision(sizeBytes = 1048576L, lastModifiedEpochMillis = 1000L)

    @Test
    fun importsValidSrtContentSuccessfully() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort()
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val service = SubtitleImportService(operator, assetRepo, stagingPort, FakeFileInspector())
        val srtBytes = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello world!
        """.trimIndent().encodeToByteArray()

        val result = service.importSubtitleContent(
            entryId = "entry-sub-1",
            bytes = srtBytes,
            filenameHint = "captions.srt",
            language = "en",
            mediaRevision = sampleRevision(),
        )

        assertTrue(result.isSuccess)
        val asset = result.getOrThrow()
        assertEquals(AssetKind.SUBTITLE, asset.kind)
        assertEquals("en", asset.language)
        assertEquals("IMPORTED", asset.sourceType)
        assertEquals(AssetState.VERIFIED, asset.state)
        assertTrue(asset.relativeLocator.startsWith("entry-sub-1/sub-"))
        assertTrue(asset.relativeLocator.endsWith(".srt"))

        val stored = assetRepo.loadAssetsForEntry("entry-sub-1")
        assertEquals(1, stored.size)
        assertEquals(asset.id, stored.single().id)
    }

    @Test
    fun importsValidVttContentSuccessfully() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort()
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val service = SubtitleImportService(operator, assetRepo, stagingPort, FakeFileInspector())
        val vttBytes = """
            WEBVTT

            00:01.000 --> 00:04.000
            Web VTT captions!
        """.trimIndent().encodeToByteArray()

        val result = service.importSubtitleContent(
            entryId = "entry-vtt-1",
            bytes = vttBytes,
            filenameHint = "track.vtt",
            language = "pt",
            mediaRevision = sampleRevision(),
        )

        assertTrue(result.isSuccess)
        val asset = result.getOrThrow()
        assertEquals(AssetKind.SUBTITLE, asset.kind)
        assertEquals("pt", asset.language)
        assertTrue(asset.relativeLocator.endsWith(".vtt"))
    }

    @Test
    fun rejectsInvalidSubtitleEncodingOrFormat() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort()
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val service = SubtitleImportService(operator, assetRepo, stagingPort, FakeFileInspector())
        val invalidBytes = byteArrayOf(0x00, 0x12, 0x34) // binary data

        val result = service.importSubtitleContent(
            entryId = "entry-inv",
            bytes = invalidBytes,
            filenameHint = "bad.srt",
            mediaRevision = sampleRevision(),
        )

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertIs<AssetOperationException>(ex)
        assertIs<AssetOperationFailure.InvalidSubtitle>(ex.failure)
        assertTrue(assetRepo.loadAssetsForEntry("entry-inv").isEmpty())
    }

    @Test
    fun rejectsMissingSourceFile() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort()
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val service = SubtitleImportService(operator, assetRepo, stagingPort, FakeFileInspector())
        val result = service.importSubtitleFile(
            entryId = "entry-missing",
            sourcePath = "/nonexistent/path/subs.srt",
            mediaRevision = sampleRevision(),
        )

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertIs<AssetOperationException>(ex)
        assertIs<AssetOperationFailure.SourceMissing>(ex.failure)
    }

    @Test
    fun handlesRevokedPermissionGracefully() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort().apply {
            addFile("/restricted/subs.srt", 2048L)
            simulateRevokedPermission = true
        }
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val service = SubtitleImportService(operator, assetRepo, stagingPort, FakeFileInspector())
        val result = service.importSubtitleFile(
            entryId = "entry-revoked",
            sourcePath = "/restricted/subs.srt",
            mediaRevision = sampleRevision(),
        )

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertIs<AssetOperationException>(ex)
        assertIs<AssetOperationFailure.RevokedPermission>(ex.failure)
    }

    @Test
    fun failedImportNeverDestroysExistingVerifiedSubtitle() = runTest {
        val opRepo = MemoryAssetOperationRepository()
        val assetRepo = MemoryOfflineAssetRepository()
        val stagingPort = TestAssetStagingPort()
        val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, FakeFileInspector())

        val existingSub = OfflineAsset(
            id = "sub-existing",
            entryId = "entry-preserve-sub",
            kind = AssetKind.SUBTITLE,
            language = "en",
            relativeLocator = "entry-preserve-sub/sub-existing.srt",
            byteCount = 1024L,
            sha256 = "sub-sha",
            state = AssetState.VERIFIED,
            mediaRevision = sampleRevision(),
        )
        assetRepo.insertAsset(existingSub)

        val service = SubtitleImportService(operator, assetRepo, stagingPort, FakeFileInspector())
        val result = service.importSubtitleContent(
            entryId = "entry-preserve-sub",
            bytes = ByteArray(0), // Empty bytes will fail
            filenameHint = "empty.srt",
            mediaRevision = sampleRevision(),
        )

        assertTrue(result.isFailure)

        // Existing verified subtitle remains verified and intact
        val assets = assetRepo.loadAssetsForEntry("entry-preserve-sub")
        assertEquals(1, assets.size)
        assertEquals("sub-existing", assets.single().id)
        assertEquals(AssetState.VERIFIED, assets.single().state)
    }
}
