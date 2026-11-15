package com.openyt.data

import com.openyt.core.application.PortableOperationDirection
import com.openyt.core.application.PortableOperationJournalRecord
import com.openyt.core.application.PortableOperationRepository
import com.openyt.core.application.PortableOperationStatus
import com.openyt.data.db.OpenYtDatabase
import com.openyt.data.db.Portable_operation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlDelightPortableOperationRepository(
    private val database: OpenYtDatabase,
) : PortableOperationRepository {
    private val queries = database.downloadQueries

    override suspend fun getOperation(id: String): PortableOperationJournalRecord? =
        withContext(Dispatchers.Default) {
            queries.selectPortableOperationById(id).executeAsOneOrNull()?.toRecord()
        }

    override suspend fun listOperations(): List<PortableOperationJournalRecord> =
        withContext(Dispatchers.Default) {
            queries.selectAllPortableOperations().executeAsList().map { it.toRecord() }
        }

    override suspend fun listActiveOperations(): List<PortableOperationJournalRecord> =
        withContext(Dispatchers.Default) {
            queries.selectActivePortableOperations().executeAsList().map { it.toRecord() }
        }

    override suspend fun upsertOperation(record: PortableOperationJournalRecord) {
        withContext(Dispatchers.Default) {
            queries.insertPortableOperation(
                id = record.id,
                direction = record.direction.name,
                status = record.status.name,
                bundle_id = record.bundleId,
                archive_locator = record.archiveLocator,
                archive_sha256 = record.archiveSha256,
                destination_root = record.destinationRoot,
                staging_dir = record.stagingDir,
                total_bytes = record.totalBytes,
                completed_bytes = record.completedBytes,
                entries_total = record.entriesTotal.toLong(),
                entries_done = record.entriesDone.toLong(),
                plan_json = record.planJson,
                error = record.error,
                created_at = record.createdAtEpochMillis,
                updated_at = record.updatedAtEpochMillis,
            )
        }
    }

    override suspend fun markInterruptedOnStartup(nowEpochMillis: Long) {
        withContext(Dispatchers.Default) {
            queries.markRunningPortableOperationsInterrupted(nowEpochMillis)
        }
    }

    override suspend fun deleteOperation(id: String) {
        withContext(Dispatchers.Default) {
            queries.deletePortableOperation(id)
        }
    }

    private fun Portable_operation.toRecord(): PortableOperationJournalRecord =
        PortableOperationJournalRecord(
            id = id,
            direction = runCatching { PortableOperationDirection.valueOf(direction) }.getOrDefault(PortableOperationDirection.EXPORT),
            status = runCatching { PortableOperationStatus.valueOf(status) }.getOrDefault(PortableOperationStatus.FAILED),
            bundleId = bundle_id,
            archiveLocator = archive_locator,
            archiveSha256 = archive_sha256,
            destinationRoot = destination_root,
            stagingDir = staging_dir,
            totalBytes = total_bytes,
            completedBytes = completed_bytes,
            entriesTotal = entries_total.toInt(),
            entriesDone = entries_done.toInt(),
            planJson = plan_json,
            error = error,
            createdAtEpochMillis = created_at,
            updatedAtEpochMillis = updated_at,
        )
}
