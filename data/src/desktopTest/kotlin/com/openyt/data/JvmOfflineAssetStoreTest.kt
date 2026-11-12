package com.openyt.data

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.MediaRevision
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmOfflineAssetStoreTest {

    @Test
    fun storeConfirmedArtifact_copiesFileComputesSha256AndInsertsAsset() = runBlocking {
        val rootDir = Files.createTempDirectory("openyt-asset-store-test").toFile()
        try {
            val dbFile = File(rootDir, "test.db")
            val database = createOpenYtDatabase(dbFile.absolutePath)
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val assetsDir = File(rootDir, "app_assets")
            val taskDir = File(rootDir, "downloads").apply { mkdirs() }

            val store = JvmOfflineAssetStore(
                assetsDirectory = assetsDir,
                repository = assetRepo,
                fileInspector = JvmMediaFileInspector(),
            )

            val sourceSubtitle = File(taskDir, "video.pt.vtt").apply {
                writeText("WEBVTT\n\n00:00.000 --> 00:05.000\nOlá Mundo!")
            }

            val receipt = CompanionArtifactReceipt(
                kind = AssetKind.SUBTITLE,
                filePath = sourceSubtitle.absolutePath,
                language = "pt",
                sourceType = "SUBTITLE_VTT",
                timelineBasis = "SOURCE",
                isRequired = true,
            )

            val mediaRevision = MediaRevision(
                sizeBytes = 1048576L,
                lastModifiedEpochMillis = 1700000000000L,
                contentSha256 = "dummy-media-sha",
            )

            val result = store.storeConfirmedArtifact(
                entryId = "entry-101",
                receipt = receipt,
                mediaRevision = mediaRevision,
                allowedRoots = listOf(taskDir.absolutePath),
            )

            assertTrue(result.isSuccess, "Expected successful asset store result")
            val asset = result.getOrThrow()

            assertEquals("entry-101", asset.entryId)
            assertEquals(AssetKind.SUBTITLE, asset.kind)
            assertEquals("pt", asset.language)
            assertEquals("SUBTITLE_VTT", asset.sourceType)
            assertEquals(AssetState.VERIFIED, asset.state)
            assertTrue(asset.isRequired)
            assertEquals(sourceSubtitle.length(), asset.byteCount)

            // Verify file was copied into assets directory
            val expectedStoredFile = File(assetsDir, asset.relativeLocator)
            assertTrue(expectedStoredFile.exists())
            assertEquals(sourceSubtitle.readText(), expectedStoredFile.readText())

            // Verify persisted in SQLite repository
            val storedList = assetRepo.loadAssetsForEntry("entry-101")
            assertEquals(1, storedList.size)
            assertEquals(asset.id, storedList.first().id)
            assertEquals(asset.sha256, storedList.first().sha256)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun storeConfirmedArtifact_rejectsOutOfRootReceiptAndDoesNotWriteFiles() = runBlocking {
        val rootDir = Files.createTempDirectory("openyt-asset-forgery-test").toFile()
        try {
            val dbFile = File(rootDir, "test.db")
            val database = createOpenYtDatabase(dbFile.absolutePath)
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val assetsDir = File(rootDir, "app_assets")
            val taskDir = File(rootDir, "downloads").apply { mkdirs() }
            val otherDir = File(rootDir, "other").apply { mkdirs() }

            val store = JvmOfflineAssetStore(
                assetsDirectory = assetsDir,
                repository = assetRepo,
                fileInspector = JvmMediaFileInspector(),
            )

            val forgedFile = File(otherDir, "sensitive.txt").apply { writeText("secret") }

            val receipt = CompanionArtifactReceipt(
                kind = AssetKind.SUBTITLE,
                filePath = forgedFile.absolutePath,
            )

            val result = store.storeConfirmedArtifact(
                entryId = "entry-forged",
                receipt = receipt,
                mediaRevision = MediaRevision(100L, 100L),
                allowedRoots = listOf(taskDir.absolutePath),
            )

            assertTrue(result.isFailure, "Must reject out-of-root artifact receipt")
            // Verify no entry folder or files written
            val entryDir = File(assetsDir, "entry-forged")
            assertFalse(entryDir.exists())
            assertTrue(assetRepo.loadAssetsForEntry("entry-forged").isEmpty())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun storeConfirmedArtifact_handlesUnicodeAndQuotesInSourcePath() = runBlocking {
        val rootDir = Files.createTempDirectory("openyt-asset-unicode-test").toFile()
        try {
            val dbFile = File(rootDir, "test.db")
            val database = createOpenYtDatabase(dbFile.absolutePath)
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val assetsDir = File(rootDir, "app_assets")
            val taskDir = File(rootDir, "downloads").apply { mkdirs() }

            val store = JvmOfflineAssetStore(
                assetsDirectory = assetsDir,
                repository = assetRepo,
                fileInspector = JvmMediaFileInspector(),
            )

            val isWindows = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)
            val fileName = if (isWindows) {
                "Curso 'Kotlin' [Ação] #1.pt.vtt"
            } else {
                "Curso \"Kotlin\" [Ação] #1.pt.vtt"
            }
            val unicodeFile = File(taskDir, fileName).apply {
                writeText("WEBVTT\n\n00:00.000 --> 00:02.000\nTeste Unicode e Aspas")
            }

            val receipt = CompanionArtifactReceipt(
                kind = AssetKind.SUBTITLE,
                filePath = unicodeFile.absolutePath,
                language = "pt",
            )

            val result = store.storeConfirmedArtifact(
                entryId = "entry-unicode",
                receipt = receipt,
                mediaRevision = MediaRevision(500L, 500L),
                allowedRoots = listOf(taskDir.absolutePath),
            )

            assertTrue(result.isSuccess)
            val asset = result.getOrThrow()
            val copiedFile = File(assetsDir, asset.relativeLocator)
            assertTrue(copiedFile.exists())
            assertEquals(unicodeFile.readText(), copiedFile.readText())
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun storeConfirmedArtifact_failsWhenSourceIsMissing() = runBlocking {
        val rootDir = Files.createTempDirectory("openyt-asset-missing-test").toFile()
        try {
            val dbFile = File(rootDir, "test.db")
            val database = createOpenYtDatabase(dbFile.absolutePath)
            val assetRepo = SqlDelightOfflineAssetRepository(database)
            val assetsDir = File(rootDir, "app_assets")
            val taskDir = File(rootDir, "downloads").apply { mkdirs() }

            val store = JvmOfflineAssetStore(
                assetsDirectory = assetsDir,
                repository = assetRepo,
                fileInspector = JvmMediaFileInspector(),
            )

            val receipt = CompanionArtifactReceipt(
                kind = AssetKind.SUBTITLE,
                filePath = File(taskDir, "non_existent.vtt").absolutePath,
            )

            val result = store.storeConfirmedArtifact(
                entryId = "entry-missing",
                receipt = receipt,
                mediaRevision = MediaRevision(500L, 500L),
                allowedRoots = listOf(taskDir.absolutePath),
            )

            assertTrue(result.isFailure)
            assertTrue(assetRepo.loadAssetsForEntry("entry-missing").isEmpty())
        } finally {
            rootDir.deleteRecursively()
        }
    }
}
