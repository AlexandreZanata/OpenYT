package com.openyt.data

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperationFailure
import com.openyt.core.domain.AssetOperationStatus
import com.openyt.core.domain.CompanionArtifactReceipt
import com.openyt.core.domain.MediaRevision
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmDurableAssetStorageTest {

    @Test
    fun realStagingHashingAndAtomicPromotion() {
        runBlocking {
            val rootDir = Files.createTempDirectory("openyt-durable-storage-").toFile()
            try {
                val dbFile = File(rootDir, "test.db")
                val database = createOpenYtDatabase(dbFile.absolutePath)
                val assetRepo = SqlDelightOfflineAssetRepository(database)
                val opRepo = SqlDelightAssetOperationRepository(database)
                val assetsDir = File(rootDir, "app_assets")
                val downloadsDir = File(rootDir, "downloads").apply { mkdirs() }

                val stagingPort = JvmAssetStagingPort(assetsDirectory = assetsDir)
                val store = JvmOfflineAssetStore(
                    assetsDirectory = assetsDir,
                    repository = assetRepo,
                    operationRepository = opRepo,
                    fileInspector = JvmMediaFileInspector(),
                    stagingPort = stagingPort,
                )

                val sourceSubtitle = File(downloadsDir, "video.pt.vtt").apply {
                    writeText("WEBVTT\n\n00:00.000 --> 00:05.000\nLegenda Confirmada")
                }

                val receipt = CompanionArtifactReceipt(
                    kind = AssetKind.SUBTITLE,
                    filePath = sourceSubtitle.absolutePath,
                    language = "pt",
                    sourceType = "SUBTITLE_VTT",
                    timelineBasis = "SOURCE",
                    isRequired = true,
                    byteCount = sourceSubtitle.length(),
                )

                val revision = MediaRevision(sizeBytes = 10000L, lastModifiedEpochMillis = 1000L)

                val result = store.storeConfirmedArtifact(
                    entryId = "entry-durable-1",
                    receipt = receipt,
                    mediaRevision = revision,
                    allowedRoots = listOf(downloadsDir.absolutePath),
                )

                assertTrue(result.isSuccess, "Expected successful storage result")
                val asset = result.getOrThrow()

                // Verify target promoted file exists
                val targetFile = File(assetsDir, asset.relativeLocator)
                assertTrue(targetFile.exists())
                assertEquals(sourceSubtitle.readText(), targetFile.readText())

                // Verify staging directory is cleaned up
                val stagingDir = File(assetsDir, ".staging")
                val stagingSubdirs = stagingDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                assertTrue(stagingSubdirs.isEmpty(), "Staging subdirectories must be deleted upon success")

                // Verify operation marked SUCCEEDED in SQLite
                val operations = opRepo.loadOperationsForEntry("entry-durable-1")
                assertEquals(1, operations.size)
                val op = operations.single()
                assertEquals(AssetOperationStatus.SUCCEEDED, op.status)
                assertEquals(sourceSubtitle.length(), op.byteCount)
                assertEquals(asset.sha256, op.sha256)

                // Verify source media untouched
                assertTrue(sourceSubtitle.exists())
            } finally {
                rootDir.deleteRecursively()
            }
        }
    }

    @Test
    fun diskFullInjectionPreservesSourceAndCleansStaging() {
        runBlocking {
            val rootDir = Files.createTempDirectory("openyt-disk-full-").toFile()
            try {
                val dbFile = File(rootDir, "test.db")
                val database = createOpenYtDatabase(dbFile.absolutePath)
                val assetRepo = SqlDelightOfflineAssetRepository(database)
                val opRepo = SqlDelightAssetOperationRepository(database)
                val assetsDir = File(rootDir, "app_assets")
                val downloadsDir = File(rootDir, "downloads").apply { mkdirs() }

                // Inject simulated available disk space smaller than required
                val stagingPort = JvmAssetStagingPort(assetsDirectory = assetsDir, simulatedDiskSpace = 50L)
                val store = JvmOfflineAssetStore(
                    assetsDirectory = assetsDir,
                    repository = assetRepo,
                    operationRepository = opRepo,
                    fileInspector = JvmMediaFileInspector(),
                    stagingPort = stagingPort,
                )

                val sourceArtwork = File(downloadsDir, "artwork.jpg").apply {
                    writeBytes(ByteArray(1024) { 1 })
                }

                val receipt = CompanionArtifactReceipt(
                    kind = AssetKind.ARTWORK,
                    filePath = sourceArtwork.absolutePath,
                    byteCount = 1024L,
                )

                val result = store.storeConfirmedArtifact(
                    entryId = "entry-df",
                    receipt = receipt,
                    mediaRevision = MediaRevision(100L, 100L),
                    allowedRoots = listOf(downloadsDir.absolutePath),
                )

                assertTrue(result.isFailure)

                // Staging cleaned up, source preserved, no published asset
                assertTrue(sourceArtwork.exists())
                assertTrue(assetRepo.loadAssetsForEntry("entry-df").isEmpty())
                val op = opRepo.loadOperationsForEntry("entry-df").single()
                assertEquals(AssetOperationStatus.FAILED, op.status)
                assertIs<AssetOperationFailure.DiskFull>(op.failure)
            } finally {
                rootDir.deleteRecursively()
            }
        }
    }

    @Test
    fun truncatedCopyInjectionFailsAndRejectsPublication() {
        runBlocking {
            val rootDir = Files.createTempDirectory("openyt-trunc-copy-").toFile()
            try {
                val dbFile = File(rootDir, "test.db")
                val database = createOpenYtDatabase(dbFile.absolutePath)
                val assetRepo = SqlDelightOfflineAssetRepository(database)
                val opRepo = SqlDelightAssetOperationRepository(database)
                val assetsDir = File(rootDir, "app_assets")
                val downloadsDir = File(rootDir, "downloads").apply { mkdirs() }

                // Inject simulated truncation at 10 bytes for a 500 byte file
                val stagingPort = JvmAssetStagingPort(assetsDirectory = assetsDir, simulatedTruncationAtBytes = 10L)
                val store = JvmOfflineAssetStore(
                    assetsDirectory = assetsDir,
                    repository = assetRepo,
                    operationRepository = opRepo,
                    fileInspector = JvmMediaFileInspector(),
                    stagingPort = stagingPort,
                )

                val sourceFile = File(downloadsDir, "large_sub.vtt").apply {
                    writeBytes(ByteArray(500) { 42 })
                }

                val receipt = CompanionArtifactReceipt(
                    kind = AssetKind.SUBTITLE,
                    filePath = sourceFile.absolutePath,
                    byteCount = 500L,
                )

                val result = store.storeConfirmedArtifact(
                    entryId = "entry-trunc",
                    receipt = receipt,
                    mediaRevision = MediaRevision(100L, 100L),
                    allowedRoots = listOf(downloadsDir.absolutePath),
                )

                assertTrue(result.isFailure)

                // Done criteria: no success with missing bytes
                assertTrue(assetRepo.loadAssetsForEntry("entry-trunc").isEmpty())
                val op = opRepo.loadOperationsForEntry("entry-trunc").single()
                assertEquals(AssetOperationStatus.FAILED, op.status)
                assertIs<AssetOperationFailure.TruncatedCopy>(op.failure)

                // Source preserved
                assertTrue(sourceFile.exists())
                assertEquals(500L, sourceFile.length())
            } finally {
                rootDir.deleteRecursively()
            }
        }
    }

    @Test
    fun revokedPermissionFailsGracefully() {
        runBlocking {
            val rootDir = Files.createTempDirectory("openyt-revoked-").toFile()
            try {
                val dbFile = File(rootDir, "test.db")
                val database = createOpenYtDatabase(dbFile.absolutePath)
                val assetRepo = SqlDelightOfflineAssetRepository(database)
                val opRepo = SqlDelightAssetOperationRepository(database)
                val assetsDir = File(rootDir, "app_assets")
                val downloadsDir = File(rootDir, "downloads").apply { mkdirs() }

                val stagingPort = JvmAssetStagingPort(assetsDirectory = assetsDir, simulatedRevokedPermission = true)
                val store = JvmOfflineAssetStore(
                    assetsDirectory = assetsDir,
                    repository = assetRepo,
                    operationRepository = opRepo,
                    fileInspector = JvmMediaFileInspector(),
                    stagingPort = stagingPort,
                )

                val sourceFile = File(downloadsDir, "sub.vtt").apply { writeText("sample") }

                val receipt = CompanionArtifactReceipt(
                    kind = AssetKind.SUBTITLE,
                    filePath = sourceFile.absolutePath,
                )

                val result = store.storeConfirmedArtifact(
                    entryId = "entry-perm",
                    receipt = receipt,
                    mediaRevision = MediaRevision(100L, 100L),
                    allowedRoots = listOf(downloadsDir.absolutePath),
                )

                assertTrue(result.isFailure)
                val op = opRepo.loadOperationsForEntry("entry-perm").single()
                assertEquals(AssetOperationStatus.FAILED, op.status)
                assertIs<AssetOperationFailure.RevokedPermission>(op.failure)
            } finally {
                rootDir.deleteRecursively()
            }
        }
    }

    @Test
    fun crashReconciliationRecoversInterruptedOperationsAndCleansOrphanedStaging() {
        runBlocking {
            val rootDir = Files.createTempDirectory("openyt-crash-reconcile-").toFile()
            try {
                val dbFile = File(rootDir, "test.db")
                val database = createOpenYtDatabase(dbFile.absolutePath)
                val assetRepo = SqlDelightOfflineAssetRepository(database)
                val opRepo = SqlDelightAssetOperationRepository(database)
                val assetsDir = File(rootDir, "app_assets")

                val stagingPort = JvmAssetStagingPort(assetsDirectory = assetsDir)
                val store = JvmOfflineAssetStore(
                    assetsDirectory = assetsDir,
                    repository = assetRepo,
                    operationRepository = opRepo,
                    fileInspector = JvmMediaFileInspector(),
                    stagingPort = stagingPort,
                )

                // Simulate crash: an operation was marked RUNNING in DB and left staging directory
                val op = com.openyt.core.domain.AssetOperation(
                    id = "op-crash-1",
                    entryId = "entry-crashed",
                    kind = AssetKind.SUBTITLE,
                    sourcePath = "/tmp/fake.vtt",
                    status = AssetOperationStatus.RUNNING,
                    createdAtEpochMillis = 1000L,
                    updatedAtEpochMillis = 1000L,
                )
                opRepo.insertOperation(op)

                val orphanedStaging = File(assetsDir, ".staging/op-crash-1").apply { mkdirs() }
                File(orphanedStaging, "staged.tmp").writeText("abandoned partial bytes")

                // Reconcile journal on startup
                store.operator.reconcileJournal()

                // Operation transitioned to INTERRUPTED
                val reconciled = opRepo.getOperation("op-crash-1")
                assertNotNull(reconciled)
                assertEquals(AssetOperationStatus.INTERRUPTED, reconciled.status)

                // Orphaned staging directory cleaned up
                assertFalse(orphanedStaging.exists())
            } finally {
                rootDir.deleteRecursively()
            }
        }
    }
}
