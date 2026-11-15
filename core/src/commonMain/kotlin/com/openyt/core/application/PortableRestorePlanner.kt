package com.openyt.core.application

import com.openyt.core.domain.AppSettings
import kotlinx.coroutines.CancellationException

data class PortableRestoreProgress(
    val phase: String,
    val itemsDone: Int,
    val itemsTotal: Int,
    val bytesProcessed: Long,
    val totalBytes: Long,
)

data class PortableRestorePlan(
    val operationId: String,
    val bundleId: String,
    val archiveLocator: String,
    val archiveSha256: String,
    val stagingDir: String,
    val destinationRoot: String,
    val options: PortableRestoreOptions,
    val manifest: PortableBackupManifest,
    val importPlan: PortableRemappedImportPlan,
    val stagedBlobsByPath: Map<String, PortableStagedEntryResult>,
    val requiredDestinationBytes: Long,
    val availableDestinationBytes: Long,
    val storageReserveBytes: Long,
    val hasPlaintextSensitiveData: Boolean,
    val createdAtEpochMillis: Long,
)

/**
 * Validates untrusted portable backup bundles using strict streaming size/path/hash
 * verification into private staging and builds a user-confirmable [PortableRestorePlan]
 * bound strictly to the archive SHA-256 digest before touching library data or user directories.
 */
class PortableRestorePlanner(
    private val snapshotSource: PortableBackupSnapshotSource,
    private val readerPort: PortableArchiveReaderPort,
    private val storageAccess: PortableStorageAccessPort,
    private val journalRepository: PortableOperationRepository,
    private val storageReserveBytes: Long = AppSettings.DEFAULT_STORAGE_RESERVE_BYTES,
    private val maxExpandedArchiveBytes: Long = DEFAULT_MAX_EXPANDED_ARCHIVE_BYTES,
    private val nowProvider: () -> Long = {
        kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds + 1_700_000_000_000L
    },
) {
    private var seq = 0L

    suspend fun prepareRestorePlan(
        archiveLocator: String,
        destinationRoot: String,
        options: PortableRestoreOptions = PortableRestoreOptions(),
        onProgress: suspend (PortableRestoreProgress) -> Unit = {},
    ): Result<PortableRestorePlan> {
        val now = nowProvider()
        val opId = "bkp-rst-$now-${++seq}"
        val stagingDir = try {
            storageAccess.prepareStagingDirectory(opId)
        } catch (error: Throwable) {
            return Result.failure(PortableBackupFailure.IoError(error.message ?: "Staging unavailable").toException())
        }

        val availStaging = storageAccess.availableBytesForStaging()
        val stagingBudget = minOf(maxExpandedArchiveBytes, (availStaging - storageReserveBytes).coerceAtLeast(0L))

        var journal = PortableOperationJournalRecord(
            id = opId,
            direction = PortableOperationDirection.RESTORE,
            status = PortableOperationStatus.RUNNING,
            archiveLocator = archiveLocator,
            destinationRoot = destinationRoot,
            stagingDir = stagingDir,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        journalRepository.upsertOperation(journal)

        val policy = object : PortableArchiveReadPolicy {
            override val maxManifestBytes: Long = PortableBackupFormat.MAX_MANIFEST_BYTES
            override val maxEntryCount: Int = PortableBackupFormat.MAX_ARCHIVE_ENTRIES
            override val maxTotalExpandedBytes: Long = stagingBudget

            override fun inspectEntry(
                entryIndex: Int,
                rawName: String,
                isDirectory: Boolean,
                declaredSizeBytes: Long,
                isSymlink: Boolean,
            ): Result<PortableReadEntryDisposition> {
                if (isSymlink) {
                    return Result.failure(
                        PortableBackupFailure.SecurityViolation("Symlink entry disallowed in backup archive").toException(),
                    )
                }
                if (isDirectory) {
                    return Result.success(PortableReadEntryDisposition.SKIP_DIRECTORY)
                }
                val validPath = PortableArchivePathRules.validateEntryPath(rawName)
                return validPath.map { cleanPath ->
                    if (cleanPath == PortableBackupFormat.MANIFEST_ENTRY_NAME) {
                        PortableReadEntryDisposition.BUFFER_MANIFEST
                    } else {
                        PortableReadEntryDisposition.STAGE_BLOB
                    }
                }
            }
        }

        try {
            val summary = readerPort.readAndStageStream(
                archiveLocator = archiveLocator,
                stagingDir = stagingDir,
                policy = policy,
            ) { bytesRead, entryCount ->
                onProgress(PortableRestoreProgress("INSPECTING", entryCount, entryCount, bytesRead, bytesRead))
            }.getOrThrow()

            val manifestBytes = summary.manifestBytes
                ?: throw PortableBackupFailure.InvalidManifest("Missing manifest.json inside archive").toException()

            val manifest = PortableBackupCodec.decode(manifestBytes).getOrThrow()

            val declaredBlobs = manifest.declaredBlobs()
            val declaredPaths = declaredBlobs.map { it.archivePath }.toSet()
            val unexpectedPaths = summary.stagedBlobsByPath.keys - declaredPaths
            if (unexpectedPaths.isNotEmpty()) {
                throw PortableBackupFailure.SecurityViolation(
                    "Archive contains undeclared files: ${unexpectedPaths.take(3).joinToString()}",
                ).toException()
            }

            for (blob in declaredBlobs) {
                val staged = summary.stagedBlobsByPath[blob.archivePath]
                    ?: throw PortableBackupFailure.InvalidManifest("Missing declared blob: ${blob.archivePath}").toException()
                if (staged.byteCount != blob.byteCount || !staged.sha256.equals(blob.sha256, ignoreCase = true)) {
                    throw PortableBackupFailure.ChecksumMismatch(blob.archivePath).toException()
                }
            }

            val existing = snapshotSource.captureConsistentSnapshot()
            val importPlan = PortableIdRemapper.buildImportPlan(
                manifest = manifest,
                existing = existing,
                conflictPolicy = options.conflictPolicy,
                nowEpochMillis = nowProvider(),
            )

            val requiredBytes = importPlan.totalBlobBytesToCopy
            val availableDestBytes = storageAccess.availableBytesForDestination(destinationRoot)
            if (availableDestBytes < requiredBytes + storageReserveBytes) {
                throw PortableBackupFailure.InsufficientSpace(
                    requiredBytes = requiredBytes,
                    availableBytes = availableDestBytes,
                    reserveBytes = storageReserveBytes,
                ).toException()
            }

            val hasSensitive = manifest.entries.any {
                it.study?.isEmpty == false || it.playbackCheckpoint != null || it.tags.isNotEmpty()
            }

            val plan = PortableRestorePlan(
                operationId = opId,
                bundleId = manifest.bundleId,
                archiveLocator = archiveLocator,
                archiveSha256 = summary.archiveSha256,
                stagingDir = stagingDir,
                destinationRoot = destinationRoot,
                options = options,
                manifest = manifest,
                importPlan = importPlan,
                stagedBlobsByPath = summary.stagedBlobsByPath,
                requiredDestinationBytes = requiredBytes,
                availableDestinationBytes = availableDestBytes,
                storageReserveBytes = storageReserveBytes,
                hasPlaintextSensitiveData = hasSensitive,
                createdAtEpochMillis = nowProvider(),
            )

            journal = journal.copy(
                status = PortableOperationStatus.STAGING_VERIFIED,
                bundleId = manifest.bundleId,
                archiveSha256 = summary.archiveSha256,
                totalBytes = requiredBytes,
                entriesTotal = importPlan.entriesToImport.size,
                updatedAtEpochMillis = nowProvider(),
            )
            journalRepository.upsertOperation(journal)

            return Result.success(plan)
        } catch (cancel: CancellationException) {
            storageAccess.cleanupStagingDirectory(opId)
            journalRepository.upsertOperation(
                journal.copy(
                    status = PortableOperationStatus.CANCELED,
                    error = "CANCELED",
                    updatedAtEpochMillis = nowProvider(),
                ),
            )
            throw cancel
        } catch (error: Throwable) {
            storageAccess.cleanupStagingDirectory(opId)
            val errStr = (error as? PortableBackupException)?.let { "${it.failure.userSafeCode}: ${it.failure.message}" }
                ?: (error.message ?: "INVALID_ARCHIVE")
            journalRepository.upsertOperation(
                journal.copy(
                    status = PortableOperationStatus.FAILED,
                    error = errStr.take(240),
                    updatedAtEpochMillis = nowProvider(),
                ),
            )
            return Result.failure(error)
        }
    }

    suspend fun discardPlan(plan: PortableRestorePlan) {
        storageAccess.cleanupStagingDirectory(plan.operationId)
        journalRepository.getOperation(plan.operationId)?.let { rec ->
            if (rec.status != PortableOperationStatus.SUCCEEDED) {
                journalRepository.upsertOperation(
                    rec.copy(
                        status = PortableOperationStatus.CANCELED,
                        updatedAtEpochMillis = nowProvider(),
                    ),
                )
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_EXPANDED_ARCHIVE_BYTES: Long = 64L * 1024L * 1024L * 1024L
    }
}
