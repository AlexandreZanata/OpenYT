package com.openyt.core.application

import com.openyt.core.domain.AppSettings
import com.openyt.core.domain.AssetState
import com.openyt.core.domain.OfflineAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PortableExportProgress(
    val entriesDone: Int,
    val entriesTotal: Int,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val currentLabel: String = "",
)

data class PortableExportReceipt(
    val operationId: String,
    val bundleId: String,
    val archiveLocator: String,
    val archiveSha256: String,
    val entryCount: Int,
    val mediaFileCount: Int,
    val offlineAssetCount: Int,
    val skippedItems: List<PortableSkippedItem>,
    val totalBytesWritten: Long,
)

/**
 * Orchestrates cancellable, journaled portable backup exports:
 * 1. Captures consistent metadata snapshot.
 * 2. Verifies source availability, permission, and destination free space + reserve.
 * 3. Streams files once into ZIP while computing SHA-256 and checking source-revision stability
 *    before and after each file stream so mid-copy file edits never slip in silently.
 * 4. Writes sanitized `manifest.json` cleanly and atomically promotes archive output.
 */
class PortableBackupExportService(
    private val snapshotSource: PortableBackupSnapshotSource,
    private val writerPort: PortableArchiveWriterPort,
    private val storageAccess: PortableStorageAccessPort,
    private val journalRepository: PortableOperationRepository,
    private val ownedAssetLocatorResolver: (OfflineAsset) -> String? = { null },
    private val storageReserveBytes: Long = AppSettings.DEFAULT_STORAGE_RESERVE_BYTES,
    private val nowProvider: () -> Long = {
        kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds + 1_700_000_000_000L
    },
) {
    private val mutex = Mutex()
    private var idSeq = 0L

    suspend fun reconcileInterruptedOperations() {
        val now = nowProvider()
        journalRepository.markInterruptedOnStartup(now)
        val preservedOps = journalRepository.listActiveOperations()
            .filter {
                it.status == PortableOperationStatus.PLANNED ||
                    it.status == PortableOperationStatus.STAGING_VERIFIED
            }
            .map { it.id }
            .toSet()
        storageAccess.cleanupOrphanedStaging(preservedOps)
    }

    suspend fun exportBackup(
        archiveLocator: String,
        options: PortableExportOptions = PortableExportOptions(),
        appVersion: String? = null,
        onProgress: suspend (PortableExportProgress) -> Unit = {},
    ): Result<PortableExportReceipt> = mutex.withLock {
        val startMillis = nowProvider()
        val seq = ++idSeq
        val opId = "bkp-exp-$startMillis-$seq"
        val bundleId = "bundle-$startMillis-$seq"

        val snapshot = try {
            snapshotSource.captureConsistentSnapshot()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            return@withLock Result.failure(PortableBackupFailure.IoError(error.message ?: "Snapshot failed").toException())
        }

        val selectedEntries = snapshot.entries
            .let { list ->
                val filter = options.selectedEntryIds
                if (filter == null) list else list.filter { it.id in filter }
            }
            .sortedWith(compareBy({ it.createdAtEpochMillis }, { it.id }))

        data class PlannedFileWrite(
            val entryId: String,
            val assetId: String?,
            val archivePath: String,
            val sourceLocator: String,
            val beforeSize: Long,
            val beforeMtime: Long,
            val displayName: String,
            val mediaKind: com.openyt.core.domain.MediaKind,
        )

        val plannedWrites = mutableListOf<PlannedFileWrite>()
        val skippedItems = mutableListOf<PortableSkippedItem>()
        val missingOrDeniedLabels = mutableListOf<String>()
        var permissionDeniedLabel: String? = null
        val usedArchivePaths = mutableSetOf<String>()

        fun allocateUniqueArchivePath(folder: String, slotId: String, desiredName: String): String {
            val cleanBase = PortableArchivePathRules.buildBlobArchivePath(folder, slotId, desiredName)
            if (usedArchivePaths.add(cleanBase.lowercase())) return cleanBase
            val dot = cleanBase.lastIndexOf('.')
            val prefix = if (dot > cleanBase.lastIndexOf('/')) cleanBase.substring(0, dot) else cleanBase
            val ext = if (dot > cleanBase.lastIndexOf('/')) cleanBase.substring(dot) else ""
            var n = 1
            while (true) {
                val candidate = "$prefix-$n$ext"
                if (usedArchivePaths.add(candidate.lowercase())) return candidate
                n++
            }
        }

        for (entry in selectedEntries) {
            if (options.includeMediaFiles) {
                when (val meta = storageAccess.inspectSource(entry.filePath)) {
                    is PortableSourceMetadata.Readable -> {
                        val safeName = PortableArchivePathRules.sanitizeFileName(meta.displayName, fallback = "${entry.id}.bin")
                        plannedWrites += PlannedFileWrite(
                            entryId = entry.id,
                            assetId = null,
                            archivePath = allocateUniqueArchivePath("media", entry.id, safeName),
                            sourceLocator = entry.filePath,
                            beforeSize = meta.sizeBytes,
                            beforeMtime = meta.lastModifiedEpochMillis,
                            displayName = safeName,
                            mediaKind = entry.kind,
                        )
                    }

                    is PortableSourceMetadata.PermissionDenied -> {
                        permissionDeniedLabel = permissionDeniedLabel ?: entry.title
                        missingOrDeniedLabels += entry.title
                        skippedItems += PortableSkippedItem(entry.id, "PRIMARY_MEDIA", entry.title, PortableSkipReason.PERMISSION_DENIED)
                    }

                    PortableSourceMetadata.Missing,
                    is PortableSourceMetadata.Unreadable -> {
                        missingOrDeniedLabels += entry.title
                        skippedItems += PortableSkippedItem(entry.id, "PRIMARY_MEDIA", entry.title, PortableSkipReason.MISSING_SOURCE)
                    }
                }
            } else {
                skippedItems += PortableSkippedItem(entry.id, "PRIMARY_MEDIA", entry.title, PortableSkipReason.METADATA_ONLY)
            }

            if (options.includeOfflineAssets) {
                val assets = snapshot.offlineAssetsByEntryId[entry.id].orEmpty()
                    .filter { it.state == AssetState.VERIFIED }
                for (asset in assets) {
                    val resolvedPath = ownedAssetLocatorResolver(asset)
                    if (resolvedPath == null) {
                        if (asset.isRequired) {
                            skippedItems += PortableSkippedItem(entry.id, asset.kind.name, asset.relativeLocator, PortableSkipReason.MISSING_SOURCE)
                        }
                        continue
                    }
                    when (val meta = storageAccess.inspectSource(resolvedPath)) {
                        is PortableSourceMetadata.Readable -> {
                            val rawName = asset.relativeLocator.substringAfterLast('/')
                            val safeName = PortableArchivePathRules.sanitizeFileName(rawName, fallback = "${asset.id}.dat")
                            plannedWrites += PlannedFileWrite(
                                entryId = entry.id,
                                assetId = asset.id,
                                archivePath = allocateUniqueArchivePath("assets", entry.id, safeName),
                                sourceLocator = resolvedPath,
                                beforeSize = meta.sizeBytes,
                                beforeMtime = meta.lastModifiedEpochMillis,
                                displayName = safeName,
                                mediaKind = entry.kind,
                            )
                        }

                        is PortableSourceMetadata.PermissionDenied -> {
                            if (asset.isRequired) {
                                permissionDeniedLabel = permissionDeniedLabel ?: safeLabel(asset)
                                missingOrDeniedLabels += safeLabel(asset)
                                skippedItems += PortableSkippedItem(entry.id, asset.kind.name, safeLabel(asset), PortableSkipReason.PERMISSION_DENIED)
                            }
                        }

                        PortableSourceMetadata.Missing,
                        is PortableSourceMetadata.Unreadable -> {
                            if (asset.isRequired) {
                                missingOrDeniedLabels += safeLabel(asset)
                                skippedItems += PortableSkippedItem(entry.id, asset.kind.name, safeLabel(asset), PortableSkipReason.MISSING_SOURCE)
                            }
                        }
                    }
                }
            }
        }

        if (!options.allowPartialMissing && missingOrDeniedLabels.isNotEmpty()) {
            val failure = if (permissionDeniedLabel != null && missingOrDeniedLabels.size == 1) {
                PortableBackupFailure.PermissionDenied(permissionDeniedLabel)
            } else {
                PortableBackupFailure.SourceMissing(missingOrDeniedLabels)
            }
            return@withLock Result.failure(failure.toException())
        }

        val estimatedArchiveBytes = plannedWrites.sumOf { it.beforeSize.coerceAtLeast(0L) } + 65_536L
        val availableDestBytes = storageAccess.availableBytesForDestination(archiveLocator)
        if (availableDestBytes < estimatedArchiveBytes + storageReserveBytes) {
            return@withLock Result.failure(
                PortableBackupFailure.InsufficientSpace(
                    requiredBytes = estimatedArchiveBytes,
                    availableBytes = availableDestBytes,
                    reserveBytes = storageReserveBytes,
                ).toException(),
            )
        }

        var journal = PortableOperationJournalRecord(
            id = opId,
            direction = PortableOperationDirection.EXPORT,
            status = PortableOperationStatus.RUNNING,
            bundleId = bundleId,
            archiveLocator = archiveLocator,
            totalBytes = estimatedArchiveBytes,
            completedBytes = 0L,
            entriesTotal = plannedWrites.size,
            entriesDone = 0,
            createdAtEpochMillis = startMillis,
            updatedAtEpochMillis = startMillis,
        )
        journalRepository.upsertOperation(journal)

        val sessionResult = writerPort.openOutputSession(archiveLocator, opId)
        if (sessionResult.isFailure) {
            val err = sessionResult.exceptionOrNull()
            journalRepository.upsertOperation(
                journal.copy(
                    status = PortableOperationStatus.FAILED,
                    error = sanitizeError(err),
                    updatedAtEpochMillis = nowProvider(),
                ),
            )
            return@withLock Result.failure(err ?: PortableBackupFailure.IoError("Failed to open archive output").toException())
        }

        val session = sessionResult.getOrThrow()
        try {
            val primaryBlobs = mutableMapOf<String, PortableBlobRef>()
            val assetBlobs = mutableMapOf<String, PortableBlobRef>()
            var bytesProcessed = 0L
            var filesDone = 0

            for (item in plannedWrites) {
                currentCoroutineContext().ensureActive()
                onProgress(
                    PortableExportProgress(
                        entriesDone = filesDone,
                        entriesTotal = plannedWrites.size,
                        bytesProcessed = bytesProcessed,
                        totalBytes = estimatedArchiveBytes,
                        currentLabel = item.displayName,
                    ),
                )

                val streamed = session.writeFileEntry(item.archivePath, item.sourceLocator) { delta ->
                    bytesProcessed += delta
                    onProgress(
                        PortableExportProgress(
                            entriesDone = filesDone,
                            entriesTotal = plannedWrites.size,
                            bytesProcessed = bytesProcessed,
                            totalBytes = estimatedArchiveBytes,
                            currentLabel = item.displayName,
                        ),
                    )
                }.getOrThrow()

                // Verify source file did not change mid-copy!
                val afterMeta = storageAccess.inspectSource(item.sourceLocator)
                if (afterMeta !is PortableSourceMetadata.Readable ||
                    (item.beforeSize >= 0L && (afterMeta.sizeBytes != item.beforeSize || streamed.byteCount != item.beforeSize)) ||
                    (item.beforeMtime > 0L && afterMeta.lastModifiedEpochMillis != item.beforeMtime)
                ) {
                    throw PortableBackupFailure.SourceChanged(item.displayName).toException()
                }

                val blobRef = PortableBlobRef(
                    archivePath = item.archivePath,
                    byteCount = streamed.byteCount,
                    sha256 = streamed.sha256,
                    originalFileName = item.displayName,
                    mediaKind = if (item.assetId == null) item.mediaKind else null,
                    lastModifiedEpochMillis = afterMeta.lastModifiedEpochMillis,
                )
                if (item.assetId == null) {
                    primaryBlobs[item.entryId] = blobRef
                } else {
                    assetBlobs[item.assetId] = blobRef
                }
                filesDone++
                journal = journal.copy(
                    entriesDone = filesDone,
                    completedBytes = bytesProcessed,
                    updatedAtEpochMillis = nowProvider(),
                )
                journalRepository.upsertOperation(journal)
            }

            currentCoroutineContext().ensureActive()
            val manifest = PortableSnapshotCollector.assembleManifest(
                snapshot = snapshot,
                bundleId = bundleId,
                createdAtEpochMillis = startMillis,
                appVersion = appVersion,
                selectedEntryIds = options.selectedEntryIds,
                primaryBlobsByEntryId = primaryBlobs,
                assetBlobsByAssetId = assetBlobs,
                skippedItems = skippedItems,
            )
            PortableBackupCodec.validateManifest(manifest).getOrThrow()
            val manifestJson = PortableBackupCodec.encode(manifest, pretty = false)
            session.writeJsonEntry(PortableBackupFormat.MANIFEST_ENTRY_NAME, manifestJson).getOrThrow()

            val archiveSha256 = session.commit().getOrThrow()
            journalRepository.upsertOperation(
                journal.copy(
                    status = PortableOperationStatus.SUCCEEDED,
                    archiveSha256 = archiveSha256,
                    entriesDone = filesDone,
                    completedBytes = bytesProcessed,
                    updatedAtEpochMillis = nowProvider(),
                ),
            )
            onProgress(
                PortableExportProgress(
                    entriesDone = filesDone,
                    entriesTotal = plannedWrites.size,
                    bytesProcessed = estimatedArchiveBytes,
                    totalBytes = estimatedArchiveBytes,
                    currentLabel = "",
                ),
            )

            Result.success(
                PortableExportReceipt(
                    operationId = opId,
                    bundleId = bundleId,
                    archiveLocator = archiveLocator,
                    archiveSha256 = archiveSha256,
                    entryCount = manifest.entries.size,
                    mediaFileCount = primaryBlobs.size,
                    offlineAssetCount = assetBlobs.size,
                    skippedItems = skippedItems,
                    totalBytesWritten = bytesProcessed + manifestJson.encodeToByteArray().size,
                ),
            )
        } catch (cancel: CancellationException) {
            runCatching { session.abortAndCleanup() }
            journalRepository.upsertOperation(
                journal.copy(
                    status = PortableOperationStatus.CANCELED,
                    error = "CANCELED",
                    updatedAtEpochMillis = nowProvider(),
                ),
            )
            throw cancel
        } catch (error: Throwable) {
            runCatching { session.abortAndCleanup() }
            journalRepository.upsertOperation(
                journal.copy(
                    status = PortableOperationStatus.FAILED,
                    error = sanitizeError(error),
                    updatedAtEpochMillis = nowProvider(),
                ),
            )
            Result.failure(error)
        }
    }

    private fun safeLabel(asset: OfflineAsset): String =
        "${asset.kind.name}:${asset.relativeLocator.substringAfterLast('/')}"

    private fun sanitizeError(error: Throwable?): String = when (error) {
        is PortableBackupException -> "${error.failure.userSafeCode}: ${error.failure.message}"
        else -> error?.message?.take(240) ?: "EXPORT_FAILED"
    }
}
