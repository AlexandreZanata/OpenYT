package com.openyt.data

import com.openyt.core.application.DurableAssetOperator
import com.openyt.core.application.MemoryAssetOperationRepository
import com.openyt.core.application.OfflineAssetMaintenance
import com.openyt.core.application.OfflineLibraryPresenter
import com.openyt.core.application.OfflineReadinessCalculator
import com.openyt.core.application.SubtitleImportService
import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperationException
import com.openyt.core.domain.AssetOperationFailure
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.ReadinessStatus
import com.openyt.core.domain.RecoveryAction
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OfflineMilestoneProofTest {

    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
        0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x48,
    )

    private val srt = "1\n00:00:01,000 --> 00:00:03,000\nOffline proof\n"

    @Test
    fun offlineAssetsSurviveRestartCacheClearAndQuotaPressure(): Unit = runBlocking {
        val root = Files.createTempDirectory("openyt-nx-o06-").toFile()
        val dbFile = File(root, "openyt.db")
        val assetsDir = File(root, "assets")
        val cacheDir = File(root, "rendering-cache")
        val media = File(root, "media.mp4").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val thumb = File(root, "thumb.jpg").apply { writeBytes(jpeg) }
        val subtitleFile = File(root, "captions.srt").apply { writeText(srt) }

        try {
            val inspector = JvmMediaFileInspector()
            val revision = MediaRevision(
                sizeBytes = media.length(),
                lastModifiedEpochMillis = media.lastModified(),
            )
            val entry = LibraryEntry(
                id = "entry-offline",
                title = "Offline proof",
                kind = MediaKind.VIDEO,
                filePath = media.absolutePath,
                sizeBytes = media.length(),
                thumbnailUrl = "https://remote.example/thumb.jpg",
                mediaRevision = revision,
            )

            var database = createOpenYtDatabase(dbFile.absolutePath)
            var downloadRepository = SqlDelightDownloadRepository(database)
            var assetRepository = SqlDelightOfflineAssetRepository(database)
            var stagingPort = JvmAssetStagingPort(assetsDir)
            var operator = DurableAssetOperator(
                MemoryAssetOperationRepository(),
                assetRepository,
                stagingPort,
                inspector,
            )
            var store = JvmOfflineAssetStore(assetsDir, assetRepository)
            downloadRepository.insertLibraryEntry(entry)

            val artwork = store.storeConfirmedArtifact(
                entryId = entry.id,
                receipt = CompanionArtifactReceipt(
                    kind = AssetKind.ARTWORK,
                    filePath = thumb.absolutePath,
                    sourceType = "ENGINE",
                    isRequired = true,
                ),
                mediaRevision = revision,
                allowedRoots = listOf(root.absolutePath),
            ).getOrThrow()

            val subtitleService = SubtitleImportService(
                operator,
                assetRepository,
                stagingPort,
                inspector,
            )
            val subtitle = subtitleService.importSubtitleFile(
                entryId = entry.id,
                sourcePath = subtitleFile.absolutePath,
                language = "en",
                mediaRevision = revision,
            ).getOrThrow()

            val artworkFile = File(assetsDir, artwork.relativeLocator)
            val subtitleFileOwned = File(assetsDir, subtitle.relativeLocator)
            assertTrue(artworkFile.exists())
            assertTrue(subtitleFileOwned.exists())
            assertTrue(artworkFile.readBytes().contentEquals(jpeg))
            assertEquals(srt, subtitleFileOwned.readText())

            // Simulate disposable rendering cache and then clear it.
            cacheDir.mkdirs()
            File(cacheDir, "coil-entry").writeBytes(ByteArray(256) { 1 })
            assertTrue(cacheDir.deleteRecursively())
            assertTrue(artworkFile.exists(), "Clearing the rendering cache must not remove owned artwork")
            assertTrue(subtitleFileOwned.exists(), "Clearing the rendering cache must not remove owned subtitles")

            // Restart: reopen the database and rebuild the adapters.
            database = createOpenYtDatabase(dbFile.absolutePath)
            downloadRepository = SqlDelightDownloadRepository(database)
            assetRepository = SqlDelightOfflineAssetRepository(database)
            stagingPort = JvmAssetStagingPort(assetsDir)
            operator = DurableAssetOperator(
                MemoryAssetOperationRepository(),
                assetRepository,
                stagingPort,
                inspector,
            )
            store = JvmOfflineAssetStore(assetsDir, assetRepository)

            val restoredAssets = assetRepository.loadAssetsForEntry(entry.id)
            assertEquals(2, restoredAssets.size)
            assertTrue(restoredAssets.all { it.state == AssetState.VERIFIED })
            assertTrue(restoredAssets.all { File(assetsDir, it.relativeLocator).exists() })

            val restoredState = OfflineLibraryPresenter.present(
                entry = entry,
                assets = restoredAssets,
                remoteArtworkUrl = entry.thumbnailUrl,
                renderingCacheBytes = 0L,
                fileInspector = inspector,
                resolveAssetPath = { store.resolveAssetPath(it) },
            )
            assertEquals(ReadinessStatus.READY, restoredState.readiness.status)
            assertTrue(restoredState.hasVerifiedLocalArtwork)
            assertEquals(store.resolveAssetPath(artwork.relativeLocator), restoredState.artworkModel)
            assertEquals(media.length(), restoredState.storage.originalMediaBytes)

            // Storage totals reconcile against the bytes actually owned on disk.
            val ownedBytes = assetsDir.walkTopDown()
                .filter { it.isFile && !it.path.contains("${File.separator}.staging${File.separator}") }
                .sumOf { it.length() }
            assertEquals(ownedBytes, restoredState.storage.durableAssetBytes)

            // Changed external media is detected and stays actionable.
            media.appendBytes(ByteArray(16) { 9 })
            val changed = OfflineReadinessCalculator.computeReadiness(entry, restoredAssets, inspector)
            assertEquals(ReadinessStatus.STALE, changed.status)
            assertEquals(RecoveryAction.REVERIFY, changed.missingRequirements.single().suggestedAction)

            // Pinned owned artwork survives a quota breach that blocks new assets.
            val maintenance = OfflineAssetMaintenance(assetRepository, store)
            assertTrue(maintenance.setPinned(entry.id, artwork.id, true).isSuccess)
            val usedBeforeQuota = assetRepository.loadAllAssets().sumOf { it.byteCount }
            val quotaOperator = DurableAssetOperator(
                MemoryAssetOperationRepository(),
                assetRepository,
                stagingPort,
                inspector,
                assetBudgetBytes = usedBeforeQuota,
            )
            val quotaFailure = subtitleServiceFor(quotaOperator, assetRepository, stagingPort)
                .importSubtitleFile(
                    entryId = entry.id,
                    sourcePath = subtitleFile.absolutePath,
                    mediaRevision = revision,
                )
            assertTrue(quotaFailure.isFailure)
            val quotaError = quotaFailure.exceptionOrNull()
            assertIs<AssetOperationException>(quotaError)
            assertIs<AssetOperationFailure.StorageBudgetExceeded>(quotaError.failure)
            assertTrue(artworkFile.exists(), "Pinned owned artwork must survive quota pressure")
            assertNotNull(assetRepository.loadAssetById(artwork.id))
            assertEquals(usedBeforeQuota, assetRepository.loadAllAssets().sumOf { it.byteCount })

            // Missing external media is detected with a relink action.
            val missingMedia = File(root, "missing.mp4")
            val missingEntry = entry.copy(filePath = missingMedia.absolutePath)
            val missing = OfflineReadinessCalculator.computeReadiness(missingEntry, restoredAssets, inspector)
            assertEquals(ReadinessStatus.UNAVAILABLE, missing.status)
            assertEquals(RecoveryAction.RELINK_MEDIA, missing.missingRequirements.single().suggestedAction)

            // Local artwork and subtitles remain readable without networking.
            assertTrue(artworkFile.exists())
            assertTrue(subtitleFileOwned.exists())
            assertFalse(restoredState.artworkModel.isNullOrBlank())
            assertEquals(2, restoredState.assets.size)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun subtitleServiceFor(
        operator: DurableAssetOperator,
        assetRepository: SqlDelightOfflineAssetRepository,
        stagingPort: JvmAssetStagingPort,
    ) = SubtitleImportService(operator, assetRepository, stagingPort, JvmMediaFileInspector())
}
