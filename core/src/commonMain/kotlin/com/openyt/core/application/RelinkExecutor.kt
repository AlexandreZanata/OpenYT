package com.openyt.core.application

import com.openyt.core.domain.AppSettings
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaRevision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface RelinkTransferPort {
    suspend fun inspectLocalTarget(locator: String): MediaRevision?

    suspend fun copyScopedSourceToOwned(
        operationId: String,
        sourceLocator: String,
        destinationRoot: String,
        desiredFileName: String,
        expectedSizeBytes: Long,
        expectedSha256: String?,
        storageReserveBytes: Long,
    ): Result<PortableOwnedFileReceipt>

    suspend fun cleanupOperationStaging(operationId: String)
}

data class RelinkCommitBundle(
    val entryId: String,
    val previousPath: String,
    val newPath: String,
    val newSizeBytes: Long,
    val newRevision: MediaRevision,
    val verifiedAtEpochMillis: Long,
    val contentMode: RelinkContentMode,
    val priorSentinelRevision: MediaRevision,
    val succeededJournal: PortableOperationJournalRecord,
)

data class RelinkCommitStats(
    val staleAssetsFlagged: Int,
    val subtitleIndexesInvalidated: Boolean,
)

interface RelinkPublicationPort {
    suspend fun loadEntryById(entryId: String): LibraryEntry?
    suspend fun commitRelink(bundle: RelinkCommitBundle): RelinkCommitStats
}

data class RelinkReceipt(
    val operationId: String,
    val entryId: String,
    val finalPath: String,
    val contentMode: RelinkContentMode,
    val copiedFromSaf: Boolean,
    val staleAssetsFlagged: Int,
    val subtitleIndexesInvalidated: Boolean,
)

/**
 * Executes confirmed library relinking:
 * 1. Validates current DB state & confirmation token before touching files or DB.
 * 2. Stops active playback if the target entry/path is open so file handles release cleanly.
 * 3. Copies scoped SAF sources into an owned recovery directory with byte/SHA-256 verification.
 * 4. Publishes DB path/revision update, playback checkpoint migration, stale-anchor flagging,
 *    offline asset staleness, FTS invalidation, and SUCCEEDED journal in ONE SQLite transaction.
 */
class RelinkExecutor(
    private val transferPort: RelinkTransferPort,
    private val publicationPort: RelinkPublicationPort,
    private val journalRepository: PortableOperationRepository,
    private val playbackSessionControl: PlaybackSessionControl? = null,
    private val storageReserveBytes: Long = AppSettings.DEFAULT_STORAGE_RESERVE_BYTES,
    private val nowProvider: () -> Long = {
        kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds + 1_700_000_000_000L
    },
    private val faultInjectionBeforeCommit: (() -> Unit)? = null,
) {
    private val mutex = Mutex()

    suspend fun reconcileAfterRestart() {
        journalRepository.markInterruptedOnStartup(nowProvider())
    }

    suspend fun executeRelink(
        plan: RelinkPlan,
        confirmationToken: String,
        confirmedContentChange: Boolean,
        ownedRecoveryRoot: String,
    ): Result<RelinkReceipt> = mutex.withLock {
        val startedAt = nowProvider()
        val opId = plan.planId
        var journal = PortableOperationJournalRecord(
            id = opId,
            direction = PortableOperationDirection.RECOVERY_RELINK,
            status = PortableOperationStatus.RUNNING,
            archiveLocator = plan.targetLocator,
            destinationRoot = ownedRecoveryRoot,
            totalBytes = plan.targetRevision.sizeBytes,
            entriesTotal = 1,
            createdAtEpochMillis = startedAt,
            updatedAtEpochMillis = startedAt,
        )
        journalRepository.upsertOperation(journal)

        try {
            val currentEntry = publicationPort.loadEntryById(plan.entryId)
            val observedTarget = if (plan.transferStrategy == RelinkTransferStrategy.REFERENCE_LOCAL_PATH) {
                transferPort.inspectLocalTarget(plan.targetLocator)
                    ?: throw RelinkFailure.SourceMissing("Selected target file is missing or unreadable").toException()
            } else {
                null
            }

            RelinkPlanner.validatePlanCurrent(
                plan = plan,
                currentEntry = currentEntry,
                confirmationToken = confirmationToken,
                confirmedContentChange = confirmedContentChange,
                observedTargetRevision = observedTarget,
            ).getOrThrow()

            // Release any active playback session bound to this entry or old path before copying/relinking
            val loaded = playbackSessionControl?.loadedMedia
            if (loaded != null && (loaded.entryId == plan.entryId || loaded.path == plan.previousPath)) {
                playbackSessionControl.stop()
            }

            val finalPath: String
            val finalRevision: MediaRevision
            val copiedFromSaf: Boolean
            if (plan.transferStrategy == RelinkTransferStrategy.COPY_SAF_TO_OWNED) {
                val receipt = transferPort.copyScopedSourceToOwned(
                    operationId = opId,
                    sourceLocator = plan.targetLocator,
                    destinationRoot = ownedRecoveryRoot,
                    desiredFileName = plan.targetDisplayName,
                    expectedSizeBytes = plan.targetRevision.sizeBytes,
                    expectedSha256 = plan.targetRevision.contentSha256,
                    storageReserveBytes = storageReserveBytes,
                ).getOrThrow()
                finalPath = receipt.targetLocator
                finalRevision = MediaRevision(
                    sizeBytes = receipt.byteCount,
                    lastModifiedEpochMillis = receipt.lastModifiedEpochMillis,
                    contentSha256 = receipt.sha256,
                )
                copiedFromSaf = true
            } else {
                finalPath = plan.targetLocator
                finalRevision = observedTarget ?: plan.targetRevision
                copiedFromSaf = false
            }

            journal = journal.copy(
                status = PortableOperationStatus.STAGING_VERIFIED,
                completedBytes = finalRevision.sizeBytes,
                updatedAtEpochMillis = nowProvider(),
            )
            journalRepository.upsertOperation(journal)

            faultInjectionBeforeCommit?.invoke()

            val committingAt = nowProvider()
            val succeededJournal = journal.copy(
                status = PortableOperationStatus.SUCCEEDED,
                completedBytes = finalRevision.sizeBytes,
                entriesDone = 1,
                updatedAtEpochMillis = committingAt,
            )
            val priorSentinel = plan.previousRevision ?: MediaRevision(
                sizeBytes = currentEntry?.sizeBytes ?: 0L,
                lastModifiedEpochMillis = 0L,
                contentSha256 = "replaced-prior-revision-${plan.entryId}",
            )

            val stats = publicationPort.commitRelink(
                RelinkCommitBundle(
                    entryId = plan.entryId,
                    previousPath = plan.previousPath,
                    newPath = finalPath,
                    newSizeBytes = finalRevision.sizeBytes,
                    newRevision = finalRevision,
                    verifiedAtEpochMillis = committingAt,
                    contentMode = plan.contentMode,
                    priorSentinelRevision = priorSentinel,
                    succeededJournal = succeededJournal,
                ),
            )

            transferPort.cleanupOperationStaging(opId)

            Result.success(
                RelinkReceipt(
                    operationId = opId,
                    entryId = plan.entryId,
                    finalPath = finalPath,
                    contentMode = plan.contentMode,
                    copiedFromSaf = copiedFromSaf,
                    staleAssetsFlagged = stats.staleAssetsFlagged,
                    subtitleIndexesInvalidated = stats.subtitleIndexesInvalidated,
                ),
            )
        } catch (cancel: CancellationException) {
            transferPort.cleanupOperationStaging(opId)
            journalRepository.upsertOperation(
                journal.copy(
                    status = PortableOperationStatus.CANCELED,
                    error = "CANCELED",
                    updatedAtEpochMillis = nowProvider(),
                ),
            )
            throw cancel
        } catch (error: Throwable) {
            transferPort.cleanupOperationStaging(opId)
            val errCode = when (error) {
                is RelinkException -> "${error.failure.userSafeCode}: ${error.failure.message}"
                is PortableBackupException -> "${error.failure.userSafeCode}: ${error.failure.message}"
                else -> error.message ?: "RELINK_FAILED"
            }.take(240)
            journalRepository.upsertOperation(
                journal.copy(
                    status = PortableOperationStatus.FAILED,
                    error = errCode,
                    updatedAtEpochMillis = nowProvider(),
                ),
            )
            Result.failure(error)
        }
    }
}
