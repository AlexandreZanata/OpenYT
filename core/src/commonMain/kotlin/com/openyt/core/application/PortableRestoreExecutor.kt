package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.EntryStudyState
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaRevision
import com.openyt.core.domain.OfflineAsset
import com.openyt.core.domain.SavedStudyRange
import com.openyt.core.domain.SmartCollection
import com.openyt.core.domain.StudyBookmark
import com.openyt.core.domain.StudyNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PortableRestoreCommitBundle(
    val entries: List<LibraryEntry>,
    val playbackCheckpoints: List<Pair<String, PlaybackCheckpoint>>,
    val notes: List<StudyNote>,
    val bookmarks: List<StudyBookmark>,
    val savedRanges: List<SavedStudyRange>,
    val studyStates: List<EntryStudyState>,
    val offlineAssets: List<OfflineAsset>,
    val collections: List<Collection>,
    val collectionMembership: List<Pair<String, String>>,
    val smartCollections: List<SmartCollection>,
    val customProfiles: List<DownloadProfile>,
    val succeededJournal: PortableOperationJournalRecord,
)

interface PortableRestorePublicationPort {
    suspend fun commitBundle(bundle: PortableRestoreCommitBundle)
}

data class PortableRestoreReceipt(
    val operationId: String,
    val bundleId: String,
    val archiveSha256: String,
    val entriesRestored: Int,
    val entriesSkipped: Int,
    val mediaFilesCopied: Int,
    val offlineAssetsRestored: Int,
    val collectionsRestored: Int,
    val smartCollectionsRestored: Int,
    val customProfilesRestored: Int,
    val notesRestored: Int,
    val bookmarksRestored: Int,
    val savedRangesRestored: Int,
    val subtitlesReindexed: Int,
)

/**
 * Executes a confirmed [PortableRestorePlan]:
 * 1. Binds execution strictly to the validated `archiveSha256` and active journal state.
 * 2. Copies verified staged files into unique non-overwriting targets under owned destinations.
 * 3. Publishes all remapped SQLite entities and updates journal `SUCCEEDED` in ONE atomic transaction.
 * 4. Cleans staging and triggers clean local FTS subtitle re-indexing post-commit.
 */
class PortableRestoreExecutor(
    private val storageAccess: PortableStorageAccessPort,
    private val journalRepository: PortableOperationRepository,
    private val publicationPort: PortableRestorePublicationPort,
    private val subtitleRebuilder: SubtitleIndexRebuilder? = null,
    private val ownedAssetLocatorResolver: (OfflineAsset) -> String? = { null },
    private val nowProvider: () -> Long = {
        kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds + 1_700_000_000_000L
    },
    private val faultInjectionBeforeCommit: (() -> Unit)? = null,
) {
    private val mutex = Mutex()

    suspend fun reconcileAfterRestart() {
        journalRepository.markInterruptedOnStartup(nowProvider())
        val activeIds = journalRepository.listActiveOperations()
            .filter { it.status == PortableOperationStatus.STAGING_VERIFIED || it.status == PortableOperationStatus.PLANNED }
            .map { it.id }
            .toSet()
        storageAccess.cleanupOrphanedStaging(activeIds)
    }

    suspend fun executeRestore(
        plan: PortableRestorePlan,
        confirmedArchiveSha256: String,
        ownedAssetsRoot: String,
        onProgress: suspend (PortableRestoreProgress) -> Unit = {},
    ): Result<PortableRestoreReceipt> = mutex.withLock {
        if (!confirmedArchiveSha256.equals(plan.archiveSha256, ignoreCase = true)) {
            return@withLock Result.failure(
                PortableBackupFailure.PlanMismatch("Restore confirmation does not match verified archive digest").toException(),
            )
        }

        val currentJournal = journalRepository.getOperation(plan.operationId)
        if (currentJournal == null ||
            currentJournal.archiveSha256 != plan.archiveSha256 ||
            currentJournal.status == PortableOperationStatus.SUCCEEDED
        ) {
            return@withLock Result.failure(
                PortableBackupFailure.PlanMismatch("Restore plan is stale or already applied").toException(),
            )
        }
        if (currentJournal.status != PortableOperationStatus.STAGING_VERIFIED &&
            currentJournal.status != PortableOperationStatus.INTERRUPTED
        ) {
            return@withLock Result.failure(
                PortableBackupFailure.PlanMismatch("Restore plan is in invalid state: ${currentJournal.status}").toException(),
            )
        }

        val availDest = storageAccess.availableBytesForDestination(plan.destinationRoot)
        if (availDest < plan.requiredDestinationBytes + plan.storageReserveBytes) {
            return@withLock Result.failure(
                PortableBackupFailure.InsufficientSpace(
                    requiredBytes = plan.requiredDestinationBytes,
                    availableBytes = availDest,
                    reserveBytes = plan.storageReserveBytes,
                ).toException(),
            )
        }

        val committingJournal = currentJournal.copy(
            status = PortableOperationStatus.COMMITTING,
            updatedAtEpochMillis = nowProvider(),
        )
        journalRepository.upsertOperation(committingJournal)

        val entriesToCommit = mutableListOf<LibraryEntry>()
        val checkpointsToCommit = mutableListOf<Pair<String, PlaybackCheckpoint>>()
        val notesToCommit = mutableListOf<StudyNote>()
        val bookmarksToCommit = mutableListOf<StudyBookmark>()
        val rangesToCommit = mutableListOf<SavedStudyRange>()
        val statesToCommit = mutableListOf<EntryStudyState>()
        val assetsToCommit = mutableListOf<OfflineAsset>()
        var copiedMediaCount = 0
        var copiedAssetCount = 0
        var bytesDone = 0L
        var entriesDone = 0
        val totalItems = plan.importPlan.entriesToImport.size

        try {
            for (plannedEntry in plan.importPlan.entriesToImport) {
                currentCoroutineContext().ensureActive()
                onProgress(
                    PortableRestoreProgress(
                        phase = "COPYING",
                        itemsDone = entriesDone,
                        itemsTotal = totalItems,
                        bytesProcessed = bytesDone,
                        totalBytes = plan.requiredDestinationBytes,
                    ),
                )

                var finalEntry = plannedEntry.entryTemplate
                val blob = plannedEntry.primaryBlob
                if (blob != null) {
                    val staged = plan.stagedBlobsByPath[blob.archivePath]
                        ?: throw PortableBackupFailure.InvalidManifest("Missing staged blob: ${blob.archivePath}").toException()
                    val stagedLocator = staged.stagedFileLocator
                        ?: throw PortableBackupFailure.InvalidManifest("Missing staged path for ${blob.archivePath}").toException()
                    val safeName = PortableArchivePathRules.sanitizeFileName(blob.originalFileName, fallback = "${plannedEntry.targetEntryId}.bin")
                    val receipt = storageAccess.copyStagedFileToOwnedTarget(
                        stagedPath = stagedLocator,
                        destinationRoot = plan.destinationRoot,
                        relativeSubdir = "Restored",
                        desiredFileName = safeName,
                        expectedSha256 = blob.sha256,
                        expectedByteCount = blob.byteCount,
                    ).getOrThrow()

                    val finalRevision = MediaRevision(
                        sizeBytes = receipt.byteCount,
                        lastModifiedEpochMillis = receipt.lastModifiedEpochMillis,
                        contentSha256 = receipt.sha256,
                    )
                    finalEntry = finalEntry.copy(
                        filePath = receipt.targetLocator,
                        sizeBytes = receipt.byteCount,
                        mediaRevision = finalRevision,
                        lastVerifiedEpochMillis = nowProvider(),
                    )
                    copiedMediaCount++
                    bytesDone += receipt.byteCount
                }

                val effectiveRev = finalEntry.mediaRevision
                plannedEntry.playbackCheckpoint?.let { cp ->
                    checkpointsToCommit += (finalEntry.filePath.ifBlank { "entry:${finalEntry.id}" } to cp.copy(entryId = finalEntry.id))
                }
                notesToCommit += plannedEntry.notes.map { it.copy(mediaRevision = effectiveRev ?: it.mediaRevision) }
                bookmarksToCommit += plannedEntry.bookmarks.map { it.copy(mediaRevision = effectiveRev ?: it.mediaRevision) }
                rangesToCommit += plannedEntry.savedRanges.map { it.copy(mediaRevision = effectiveRev ?: it.mediaRevision) }
                plannedEntry.studyState?.let { statesToCommit += it }

                for (plannedAsset in plannedEntry.assets) {
                    currentCoroutineContext().ensureActive()
                    val staged = plan.stagedBlobsByPath[plannedAsset.sourceArchivePath]
                        ?: throw PortableBackupFailure.InvalidManifest("Missing staged asset: ${plannedAsset.sourceArchivePath}").toException()
                    val stagedLocator = staged.stagedFileLocator
                        ?: throw PortableBackupFailure.InvalidManifest("Missing staged path for ${plannedAsset.sourceArchivePath}").toException()
                    val safeAssetName = PortableArchivePathRules.sanitizeFileName(plannedAsset.originalFileName, fallback = "${plannedAsset.asset.id}.dat")
                    val assetReceipt = storageAccess.copyStagedFileToOwnedTarget(
                        stagedPath = stagedLocator,
                        destinationRoot = ownedAssetsRoot,
                        relativeSubdir = plannedEntry.targetEntryId,
                        desiredFileName = safeAssetName,
                        expectedSha256 = plannedAsset.expectedSha256,
                        expectedByteCount = plannedAsset.expectedByteCount,
                    ).getOrThrow()
                    assetsToCommit += plannedAsset.asset.copy(
                        relativeLocator = assetReceipt.relativeLocator,
                        byteCount = assetReceipt.byteCount,
                        sha256 = assetReceipt.sha256,
                        mediaRevision = effectiveRev ?: plannedAsset.asset.mediaRevision,
                        lastVerifiedEpochMillis = nowProvider(),
                    )
                    copiedAssetCount++
                    bytesDone += assetReceipt.byteCount
                }

                entriesToCommit += finalEntry
                entriesDone++
            }

            currentCoroutineContext().ensureActive()
            faultInjectionBeforeCommit?.invoke()

            val succeededRecord = committingJournal.copy(
                status = PortableOperationStatus.SUCCEEDED,
                completedBytes = bytesDone,
                entriesDone = entriesDone,
                updatedAtEpochMillis = nowProvider(),
            )
            publicationPort.commitBundle(
                PortableRestoreCommitBundle(
                    entries = entriesToCommit,
                    playbackCheckpoints = checkpointsToCommit,
                    notes = notesToCommit,
                    bookmarks = bookmarksToCommit,
                    savedRanges = rangesToCommit,
                    studyStates = statesToCommit,
                    offlineAssets = assetsToCommit,
                    collections = plan.importPlan.collections,
                    collectionMembership = plan.importPlan.collectionMembership,
                    smartCollections = plan.importPlan.smartCollections,
                    customProfiles = plan.importPlan.customProfiles,
                    succeededJournal = succeededRecord,
                ),
            )

            // Best-effort cleanup of staging ONLY after durable DB commit
            storageAccess.cleanupStagingDirectory(plan.operationId)

            var reindexedSubtitles = 0
            val rebuilder = subtitleRebuilder
            if (rebuilder != null) {
                val entryById = entriesToCommit.associateBy { it.id }
                for (asset in assetsToCommit.filter { it.kind == AssetKind.SUBTITLE }) {
                    val path = ownedAssetLocatorResolver(asset) ?: continue
                    val entryRev = entryById[asset.entryId]?.mediaRevision?.revisionToken ?: asset.mediaRevision.revisionToken
                    val rebuildResult = runCatching {
                        rebuilder.reindexTrack(
                            trackId = asset.id,
                            entryId = asset.entryId,
                            subtitlePath = path,
                            language = asset.language,
                            mediaRevisionToken = entryRev,
                        )
                    }.getOrNull()
                    if (rebuildResult?.isSuccess == true) {
                        reindexedSubtitles++
                    }
                }
            }

            onProgress(
                PortableRestoreProgress(
                    phase = "SUCCEEDED",
                    itemsDone = totalItems,
                    itemsTotal = totalItems,
                    bytesProcessed = plan.requiredDestinationBytes,
                    totalBytes = plan.requiredDestinationBytes,
                ),
            )

            Result.success(
                PortableRestoreReceipt(
                    operationId = plan.operationId,
                    bundleId = plan.bundleId,
                    archiveSha256 = plan.archiveSha256,
                    entriesRestored = entriesToCommit.size,
                    entriesSkipped = plan.importPlan.skippedDuplicateEntryIds.size,
                    mediaFilesCopied = copiedMediaCount,
                    offlineAssetsRestored = copiedAssetCount,
                    collectionsRestored = plan.importPlan.collections.size,
                    smartCollectionsRestored = plan.importPlan.smartCollections.size,
                    customProfilesRestored = plan.importPlan.customProfiles.size,
                    notesRestored = notesToCommit.size,
                    bookmarksRestored = bookmarksToCommit.size,
                    savedRangesRestored = rangesToCommit.size,
                    subtitlesReindexed = reindexedSubtitles,
                ),
            )
        } catch (cancel: CancellationException) {
            journalRepository.upsertOperation(
                committingJournal.copy(
                    status = PortableOperationStatus.CANCELED,
                    error = "CANCELED",
                    updatedAtEpochMillis = nowProvider(),
                ),
            )
            storageAccess.cleanupStagingDirectory(plan.operationId)
            throw cancel
        } catch (error: Throwable) {
            journalRepository.upsertOperation(
                committingJournal.copy(
                    status = PortableOperationStatus.FAILED,
                    error = (error.message ?: "RESTORE_FAILED").take(240),
                    updatedAtEpochMillis = nowProvider(),
                ),
            )
            storageAccess.cleanupStagingDirectory(plan.operationId)
            Result.failure(error)
        }
    }
}
