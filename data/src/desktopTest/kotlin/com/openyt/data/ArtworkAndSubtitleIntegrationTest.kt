package com.openyt.data

import com.openyt.core.application.ArtworkFetchService
import com.openyt.core.application.DurableAssetOperator
import com.openyt.core.application.SubtitleImportService
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperationException
import com.openyt.core.domain.AssetOperationFailure
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.findVerifiedArtwork
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArtworkAndSubtitleIntegrationTest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0

    private val validJpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
        0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x48,
    )

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port

        server.createContext("/art.jpg") { exchange ->
            exchange.responseHeaders.set("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, validJpeg.size.toLong())
            exchange.responseBody.use { it.write(validJpeg) }
        }

        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun sampleRevision() = MediaRevision(sizeBytes = 1048576L, lastModifiedEpochMillis = 1000L)

    @Test
    fun downloadedArtworkSurvivesRestartAndResolvesLocally(): Unit = runBlocking {
        val rootDir = Files.createTempDirectory("openyt-artwork-integration-").toFile()
        val dbFile = File(rootDir, "openyt.db")
        val assetsDir = File(rootDir, "assets")

        try {
            val database = createOpenYtDatabase(dbFile.absolutePath)
            val downloadRepo = SqlDelightDownloadRepository(database)
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val opRepo = SqlDelightAssetOperationRepository(database)
            val stagingPort = JvmAssetStagingPort(assetsDir)
            val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, JvmMediaFileInspector())
            val fetchPort = JvmArtworkFetchPort()
            val service = ArtworkFetchService(operator, fetchPort, assetRepo, stagingPort)

            val entry = LibraryEntry(
                id = "entry-art-persist",
                title = "Persisted Video",
                kind = MediaKind.VIDEO,
                filePath = "/media/video.mp4",
                thumbnailUrl = "http://127.0.0.1:$serverPort/art.jpg",
            )
            downloadRepo.insertLibraryEntry(entry)

            val fetchResult = service.fetchArtwork(
                entryId = "entry-art-persist",
                url = "http://127.0.0.1:$serverPort/art.jpg",
                mediaRevision = sampleRevision(),
            )
            assertTrue(fetchResult.isSuccess)
            val asset = fetchResult.getOrThrow()
            assertEquals(AssetKind.ARTWORK, asset.kind)
            assertEquals(AssetState.VERIFIED, asset.state)

            // Reopen database and verify offline asset survives restart
            val databaseReopened = createOpenYtDatabase(dbFile.absolutePath)
            val assetRepoReopened = SqlDelightOfflineAssetRepository(databaseReopened)
            val assets = assetRepoReopened.loadAssetsForEntry("entry-art-persist")
            assertEquals(1, assets.size)
            val reopenedAsset = assets.single()
            assertEquals(asset.id, reopenedAsset.id)
            assertEquals(AssetState.VERIFIED, reopenedAsset.state)

            // Verify local asset path resolves to physical file in owned storage
            val localFile = File(assetsDir, reopenedAsset.relativeLocator)
            assertTrue(localFile.exists(), "Artwork file must physically exist in owned storage")
            assertEquals(validJpeg.size.toLong(), localFile.length())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun failedArtworkFetchDoesNotCorruptExistingVerifiedAssets(): Unit = runBlocking {
        val rootDir = Files.createTempDirectory("openyt-artwork-preserve-").toFile()
        val dbFile = File(rootDir, "openyt.db")
        val assetsDir = File(rootDir, "assets")

        try {
            val database = createOpenYtDatabase(dbFile.absolutePath)
            val downloadRepo = SqlDelightDownloadRepository(database)
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val opRepo = SqlDelightAssetOperationRepository(database)
            val stagingPort = JvmAssetStagingPort(assetsDir)
            val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, JvmMediaFileInspector())
            val fetchPort = JvmArtworkFetchPort()
            val service = ArtworkFetchService(operator, fetchPort, assetRepo, stagingPort)

            val entry = LibraryEntry(
                id = "entry-preserve",
                title = "Media with Existing Art",
                kind = MediaKind.VIDEO,
                filePath = "/media/v.mp4",
            )
            downloadRepo.insertLibraryEntry(entry)

            // Fetch initial valid artwork
            val firstResult = service.fetchArtwork("entry-preserve", "http://127.0.0.1:$serverPort/art.jpg", sampleRevision())
            assertTrue(firstResult.isSuccess)
            val initialAsset = firstResult.getOrThrow()

            // Simulate network offline on second fetch attempt
            val offlinePort = JvmArtworkFetchPort(simulatedNetworkOffline = true)
            val offlineService = ArtworkFetchService(operator, offlinePort, assetRepo, stagingPort)
            val failedResult = offlineService.fetchArtwork("entry-preserve", "http://127.0.0.1:$serverPort/different.jpg", sampleRevision())
            assertTrue(failedResult.isFailure)

            // Initial verified asset remains completely intact in database and filesystem
            val assets = assetRepo.loadAssetsForEntry("entry-preserve")
            assertEquals(1, assets.size)
            assertEquals(initialAsset.id, assets.single().id)
            assertEquals(AssetState.VERIFIED, assets.single().state)

            val physicalFile = File(assetsDir, initialAsset.relativeLocator)
            assertTrue(physicalFile.exists())
            assertEquals(validJpeg.size.toLong(), physicalFile.length())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun subtitleImportFromHostFileStagedAndPromoted(): Unit = runBlocking {
        val rootDir = Files.createTempDirectory("openyt-sub-import-").toFile()
        val dbFile = File(rootDir, "openyt.db")
        val assetsDir = File(rootDir, "assets")
        val tempSrt = File.createTempFile("openyt-sample-", ".srt")

        val srtContent = """
            1
            00:00:01,000 --> 00:00:05,000
            Welcome to offline OpenYT!
        """.trimIndent()
        tempSrt.writeText(srtContent)

        try {
            val database = createOpenYtDatabase(dbFile.absolutePath)
            val downloadRepo = SqlDelightDownloadRepository(database)
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val opRepo = SqlDelightAssetOperationRepository(database)
            val stagingPort = JvmAssetStagingPort(assetsDir)
            val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, JvmMediaFileInspector())
            val service = SubtitleImportService(operator, assetRepo, stagingPort, JvmMediaFileInspector())

            val entry = LibraryEntry(
                id = "entry-sub",
                title = "Video with Subtitle",
                kind = MediaKind.VIDEO,
                filePath = "/media/video.mp4",
            )
            downloadRepo.insertLibraryEntry(entry)

            val result = service.importSubtitleFile(
                entryId = "entry-sub",
                sourcePath = tempSrt.absolutePath,
                language = "en",
                mediaRevision = sampleRevision(),
            )

            assertTrue(result.isSuccess)
            val asset = result.getOrThrow()
            assertEquals(AssetKind.SUBTITLE, asset.kind)
            assertEquals("en", asset.language)
            assertEquals(AssetState.VERIFIED, asset.state)

            // Verify physical file in owned storage
            val promotedFile = File(assetsDir, asset.relativeLocator)
            assertTrue(promotedFile.exists())
            assertEquals(srtContent, promotedFile.readText())

            // Verify original file was preserved
            assertTrue(tempSrt.exists(), "Original host file must never be deleted by import")
        } finally {
            tempSrt.delete()
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun subtitleImportFromBytesStagedAndPromoted(): Unit = runBlocking {
        val rootDir = Files.createTempDirectory("openyt-vtt-import-").toFile()
        val dbFile = File(rootDir, "openyt.db")
        val assetsDir = File(rootDir, "assets")

        val vttContent = """
            WEBVTT

            00:01.000 --> 00:04.000
            Hello WebVTT!
        """.trimIndent()

        try {
            val database = createOpenYtDatabase(dbFile.absolutePath)
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val opRepo = SqlDelightAssetOperationRepository(database)
            val stagingPort = JvmAssetStagingPort(assetsDir)
            val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, JvmMediaFileInspector())
            val service = SubtitleImportService(operator, assetRepo, stagingPort, JvmMediaFileInspector())

            val result = service.importSubtitleContent(
                entryId = "entry-vtt",
                bytes = vttContent.encodeToByteArray(),
                filenameHint = "track.vtt",
                language = "pt",
                mediaRevision = sampleRevision(),
            )

            assertTrue(result.isSuccess)
            val asset = result.getOrThrow()
            assertEquals(AssetKind.SUBTITLE, asset.kind)
            assertEquals("pt", asset.language)
            assertEquals(AssetState.VERIFIED, asset.state)

            val promotedFile = File(assetsDir, asset.relativeLocator)
            assertTrue(promotedFile.exists())
            assertEquals(vttContent, promotedFile.readText())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun revokedPermissionOnHostSubtitleFailsGracefully(): Unit = runBlocking {
        val rootDir = Files.createTempDirectory("openyt-revoked-sub-").toFile()
        val dbFile = File(rootDir, "openyt.db")
        val assetsDir = File(rootDir, "assets")

        try {
            val database = createOpenYtDatabase(dbFile.absolutePath)
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val opRepo = SqlDelightAssetOperationRepository(database)
            val stagingPort = JvmAssetStagingPort(assetsDir, simulatedRevokedPermission = true)
            val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, JvmMediaFileInspector())
            val service = SubtitleImportService(operator, assetRepo, stagingPort, JvmMediaFileInspector())

            val vttBytes = "WEBVTT\n\n00:01.000 --> 00:03.000\nSub".encodeToByteArray()
            val result = service.importSubtitleContent(
                entryId = "entry-revoked",
                bytes = vttBytes,
                filenameHint = "test.vtt",
                mediaRevision = sampleRevision(),
            )

            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            assertIs<AssetOperationException>(ex)
            assertIs<AssetOperationFailure.RevokedPermission>(ex.failure)
            assertTrue(assetRepo.loadAssetsForEntry("entry-revoked").isEmpty())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun localArtworkPreferenceResolvedOverRemoteUrl(): Unit = runBlocking {
        val rootDir = Files.createTempDirectory("openyt-local-pref-").toFile()
        val dbFile = File(rootDir, "openyt.db")
        val assetsDir = File(rootDir, "assets")

        try {
            val database = createOpenYtDatabase(dbFile.absolutePath)
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val opRepo = SqlDelightAssetOperationRepository(database)
            val stagingPort = JvmAssetStagingPort(assetsDir)
            val operator = DurableAssetOperator(opRepo, assetRepo, stagingPort, JvmMediaFileInspector())
            val fetchPort = JvmArtworkFetchPort()
            val service = ArtworkFetchService(operator, fetchPort, assetRepo, stagingPort)
            val store = JvmOfflineAssetStore(assetsDir, assetRepo, opRepo)

            val entry = LibraryEntry(
                id = "entry-pref",
                title = "Preference Test",
                kind = MediaKind.VIDEO,
                filePath = "/media/v.mp4",
                thumbnailUrl = "https://remote.cdn/thumb.jpg",
            )

            // Before fetch: no local verified artwork
            val initialAssets = store.getAssetsForEntry("entry-pref")
            val initialVerified = initialAssets.findVerifiedArtwork()
            val initialEffective = initialVerified?.let { store.resolveAssetPath(it.relativeLocator) } ?: entry.thumbnailUrl
            assertEquals("https://remote.cdn/thumb.jpg", initialEffective, "Must fall back to remote URL when no verified local artwork exists")

            // Fetch artwork into owned storage
            val fetchResult = service.fetchArtwork("entry-pref", "http://127.0.0.1:$serverPort/art.jpg", sampleRevision())
            assertTrue(fetchResult.isSuccess)

            // After fetch: local verified artwork exists and is preferred!
            val updatedAssets = store.getAssetsForEntry("entry-pref")
            val verifiedArt = updatedAssets.findVerifiedArtwork()
            assertNotNull(verifiedArt)
            val resolvedLocalPath = store.resolveAssetPath(verifiedArt.relativeLocator)
            assertTrue(resolvedLocalPath.endsWith(".jpg"))
            assertTrue(File(resolvedLocalPath).exists())

            val effectiveModel = verifiedArt.let { store.resolveAssetPath(it.relativeLocator) } ?: entry.thumbnailUrl
            assertEquals(resolvedLocalPath, effectiveModel, "Must prefer verified local artwork over remote URL")
        } finally {
            rootDir.deleteRecursively()
        }
    }
}
