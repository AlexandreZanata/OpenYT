package com.openyt.data

import com.openyt.core.application.DurableAssetOperator
import com.openyt.core.application.MemoryAssetOperationRepository
import com.openyt.core.application.MemoryOfflineAssetRepository
import com.openyt.core.application.OfflineAssetMaintenance
import com.openyt.core.application.SubtitleImportService
import com.openyt.core.domain.MediaRevision
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineAssetCleanupTest {

    private fun revision() = MediaRevision(sizeBytes = 1024L, lastModifiedEpochMillis = 1000L)

    @Test
    fun cleanupRemovesOwnedAssetButNeverSourceMedia(): Unit = runBlocking {
        val root = Files.createTempDirectory("openyt-cleanup-").toFile()
        val assetsDir = File(root, "assets")
        val source = File(root, "source.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:02,000\nHello\n")
        }
        try {
            val assetRepository = MemoryOfflineAssetRepository()
            val stagingPort = JvmAssetStagingPort(assetsDir)
            val operator = DurableAssetOperator(
                MemoryAssetOperationRepository(),
                assetRepository,
                stagingPort,
                JvmMediaFileInspector(),
            )
            val importer = SubtitleImportService(
                operator,
                assetRepository,
                stagingPort,
                JvmMediaFileInspector(),
            )
            val asset = importer.importSubtitleFile(
                entryId = "entry-cleanup",
                sourcePath = source.absolutePath,
                language = "en",
                mediaRevision = revision(),
            ).getOrThrow()
            val ownedFile = File(assetsDir, asset.relativeLocator)
            assertTrue(ownedFile.exists())

            val maintenance = OfflineAssetMaintenance(assetRepository, JvmOfflineAssetStore(assetsDir, assetRepository))
            val removed = maintenance.cleanup("entry-cleanup", setOf(asset.id)).getOrThrow()

            assertEquals(1, removed)
            assertFalse(ownedFile.exists())
            assertTrue(assetRepository.loadAssetsForEntry("entry-cleanup").isEmpty())
            assertTrue(source.exists(), "Cleanup must never delete original user media")
            assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHello\n", source.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pinnedAssetSurvivesCleanup(): Unit = runBlocking {
        val root = Files.createTempDirectory("openyt-cleanup-pin-").toFile()
        val assetsDir = File(root, "assets")
        val source = File(root, "source.srt").apply {
            writeText("1\n00:00:01,000 --> 00:00:02,000\nHello\n")
        }
        try {
            val assetRepository = MemoryOfflineAssetRepository()
            val stagingPort = JvmAssetStagingPort(assetsDir)
            val operator = DurableAssetOperator(
                MemoryAssetOperationRepository(),
                assetRepository,
                stagingPort,
                JvmMediaFileInspector(),
            )
            val importer = SubtitleImportService(
                operator,
                assetRepository,
                stagingPort,
                JvmMediaFileInspector(),
            )
            val asset = importer.importSubtitleFile(
                entryId = "entry-pin",
                sourcePath = source.absolutePath,
                mediaRevision = revision(),
            ).getOrThrow()

            val store = JvmOfflineAssetStore(assetsDir, assetRepository)
            val maintenance = OfflineAssetMaintenance(assetRepository, store)
            assertTrue(maintenance.setPinned("entry-pin", asset.id, true).isSuccess)

            val removed = maintenance.cleanup("entry-pin", setOf(asset.id)).getOrThrow()

            assertEquals(0, removed)
            assertTrue(File(assetsDir, asset.relativeLocator).exists())
            assertEquals(1, assetRepository.loadAssetsForEntry("entry-pin").size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteTargetRejectsPathsOutsideOwnedStorage(): Unit = runBlocking {
        val root = Files.createTempDirectory("openyt-delete-escape-").toFile()
        val assetsDir = File(root, "assets")
        val outside = File(root, "outside.txt").apply { writeText("keep") }
        try {
            val port = JvmAssetStagingPort(assetsDir)
            assertFalse(port.deleteTarget("../outside.txt"))
            assertTrue(outside.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
