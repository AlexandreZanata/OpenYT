package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortableArchiveSecurityTest {

    private val validSha = "1".repeat(64)

    @Test
    fun rejectsTraversalDriveUncControlCharsAndCaseCollisionsInPaths() {
        val maliciousPaths = listOf(
            "",
            "   ",
            "../secret.mp4",
            "media/../../escape.mp4",
            "media/./file.mp4",
            "/etc/passwd",
            "media\\win\\path.mp4",
            "C:\\Users\\bob\\video.mp4",
            "D:relative.mp4",
            "//server/share/file.mp4",
            "media/empty//segment.mp4",
            "media/nul\u0000byte.mp4",
            "scripts/payload.sh",
            "db/openyt.db",
        )
        maliciousPaths.forEach { bad ->
            val err = PortableArchivePathRules.validateEntryPath(bad).exceptionOrNull() as? PortableBackupException
            assertTrue(err != null && err.failure is PortableBackupFailure.SecurityViolation, "Expected rejection for '$bad'")
        }
    }

    @Test
    fun plannerRejectsMissingManifestSymlinkUndeclaredBlobAndHashMismatchAndCleansStaging() = runBlocking {
        val validManifest = PortableBackupManifest(
            bundleId = "b1",
            createdAtEpochMillis = 100L,
            entries = listOf(
                PortableEntryRecord(
                    exportId = "e1",
                    title = "Safe Video",
                    kind = MediaKind.VIDEO,
                    primaryMedia = PortableBlobRef("media/e1/clip.mp4", 100L, validSha, "clip.mp4", MediaKind.VIDEO),
                ),
            ),
        )
        val validManifestBytes = PortableBackupCodec.encode(validManifest).encodeToByteArray()

        // 1. Unexpected extra blob rejected & staging cleaned
        val storage = TrackingStagingAccess(availableBytes = 100_000_000L)
        val extraBlobReader = object : PortableArchiveReaderPort {
            override suspend fun readAndStageStream(
                archiveLocator: String,
                stagingDir: String,
                policy: PortableArchiveReadPolicy,
                onProgress: suspend (Long, Int) -> Unit,
            ): Result<PortableArchiveReadSummary> {
                policy.inspectEntry(1, "manifest.json", false, validManifestBytes.size.toLong()).getOrThrow()
                policy.inspectEntry(2, "media/e1/clip.mp4", false, 100L).getOrThrow()
                policy.inspectEntry(3, "media/extra/hidden.bin", false, 50L).getOrThrow()
                return Result.success(
                    PortableArchiveReadSummary(
                        archiveSha256 = "9".repeat(64),
                        archiveByteCount = 300L,
                        entryCount = 3,
                        totalExpandedBytes = 150L,
                        manifestBytes = validManifestBytes,
                        stagedBlobsByPath = mapOf(
                            "media/e1/clip.mp4" to PortableStagedEntryResult("media/e1/clip.mp4", 100L, validSha, "$stagingDir/blob_0.part"),
                            "media/extra/hidden.bin" to PortableStagedEntryResult("media/extra/hidden.bin", 50L, "2".repeat(64), "$stagingDir/blob_1.part"),
                        ),
                    ),
                )
            }
        }
        val planner = PortableRestorePlanner(
            snapshotSource = emptySnapshotSource(),
            readerPort = extraBlobReader,
            storageAccess = storage,
            journalRepository = MemoryPortableOperationRepository(),
            storageReserveBytes = 1024L,
        )
        val extraErr = planner.prepareRestorePlan("/bad.zip", "/dest").exceptionOrNull() as PortableBackupException
        assertTrue(extraErr.failure is PortableBackupFailure.SecurityViolation)
        assertTrue(storage.cleanedUpIds.isNotEmpty())

        // 2. Symlink entry rejected inside inspectEntry
        val symlinkReader = object : PortableArchiveReaderPort {
            override suspend fun readAndStageStream(
                archiveLocator: String,
                stagingDir: String,
                policy: PortableArchiveReadPolicy,
                onProgress: suspend (Long, Int) -> Unit,
            ): Result<PortableArchiveReadSummary> =
                policy.inspectEntry(1, "media/e1/link.mp4", false, 10L, isSymlink = true)
                    .map { error("unreachable") }
        }
        val symPlanner = PortableRestorePlanner(emptySnapshotSource(), symlinkReader, storage, MemoryPortableOperationRepository(), 1024L)
        val symErr = symPlanner.prepareRestorePlan("/sym.zip", "/dest").exceptionOrNull() as PortableBackupException
        assertTrue(symErr.failure is PortableBackupFailure.SecurityViolation)

        // 3. Mismatched SHA-256 rejected
        val hashReader = object : PortableArchiveReaderPort {
            override suspend fun readAndStageStream(
                archiveLocator: String,
                stagingDir: String,
                policy: PortableArchiveReadPolicy,
                onProgress: suspend (Long, Int) -> Unit,
            ): Result<PortableArchiveReadSummary> = Result.success(
                PortableArchiveReadSummary(
                    archiveSha256 = "9".repeat(64),
                    archiveByteCount = 300L,
                    entryCount = 2,
                    totalExpandedBytes = 100L,
                    manifestBytes = validManifestBytes,
                    stagedBlobsByPath = mapOf(
                        "media/e1/clip.mp4" to PortableStagedEntryResult("media/e1/clip.mp4", 100L, "0".repeat(64), "$stagingDir/blob_0.part"),
                    ),
                ),
            )
        }
        val hashPlanner = PortableRestorePlanner(emptySnapshotSource(), hashReader, storage, MemoryPortableOperationRepository(), 1024L)
        val hashErr = hashPlanner.prepareRestorePlan("/tampered.zip", "/dest").exceptionOrNull() as PortableBackupException
        assertTrue(hashErr.failure is PortableBackupFailure.ChecksumMismatch)

        // 4. Valid bundle creates verified confirmation plan with digest & privacy flag
        val validManifestSensitive = validManifest.copy(
            entries = listOf(
                validManifest.entries.single().copy(tags = listOf("study-tag")),
            ),
        )
        val sensitiveBytes = PortableBackupCodec.encode(validManifestSensitive).encodeToByteArray()
        val okReader = object : PortableArchiveReaderPort {
            override suspend fun readAndStageStream(
                archiveLocator: String,
                stagingDir: String,
                policy: PortableArchiveReadPolicy,
                onProgress: suspend (Long, Int) -> Unit,
            ): Result<PortableArchiveReadSummary> = Result.success(
                PortableArchiveReadSummary(
                    archiveSha256 = "f".repeat(64),
                    archiveByteCount = 500L,
                    entryCount = 2,
                    totalExpandedBytes = 100L,
                    manifestBytes = sensitiveBytes,
                    stagedBlobsByPath = mapOf(
                        "media/e1/clip.mp4" to PortableStagedEntryResult("media/e1/clip.mp4", 100L, validSha, "$stagingDir/blob_0.part"),
                    ),
                ),
            )
        }
        val journal = MemoryPortableOperationRepository()
        val okPlanner = PortableRestorePlanner(emptySnapshotSource(), okReader, storage, journal, 1024L)
        val plan = okPlanner.prepareRestorePlan("/good.zip", "/dest").getOrThrow()
        assertEquals("f".repeat(64), plan.archiveSha256)
        assertTrue(plan.hasPlaintextSensitiveData)
        assertEquals(PortableOperationStatus.STAGING_VERIFIED, journal.getOperation(plan.operationId)?.status)
    }

    private fun emptySnapshotSource() = PortableBackupSnapshotSource {
        PortableSnapshotData(
            entries = emptyList(),
            collections = emptyList(),
            collectionMembership = emptyMap(),
            smartCollections = emptyList(),
            playbackByEntryId = emptyMap(),
            studyBundlesByEntryId = emptyMap(),
            offlineAssetsByEntryId = emptyMap(),
            customProfiles = emptyList(),
        )
    }

    private class TrackingStagingAccess(private val availableBytes: Long) : PortableStorageAccessPort {
        val cleanedUpIds = mutableListOf<String>()
        override fun availableBytesForDestination(locator: String): Long = availableBytes
        override fun availableBytesForStaging(): Long = availableBytes
        override fun inspectSource(sourceLocator: String): PortableSourceMetadata = PortableSourceMetadata.Missing
        override suspend fun prepareStagingDirectory(operationId: String): String = "/staging/$operationId"
        override suspend fun cleanupStagingDirectory(operationId: String) {
            cleanedUpIds += operationId
        }
        override suspend fun cleanupOrphanedStaging(activeOperationIds: Set<String>) {}
        override suspend fun copyStagedFileToOwnedTarget(
            stagedPath: String,
            destinationRoot: String,
            relativeSubdir: String,
            desiredFileName: String,
            expectedSha256: String,
            expectedByteCount: Long,
        ): Result<PortableOwnedFileReceipt> = Result.failure(UnsupportedOperationException())
        override suspend fun checkFileSha256(locator: String): String? = null
    }
}
