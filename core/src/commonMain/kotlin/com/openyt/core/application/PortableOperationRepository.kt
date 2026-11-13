package com.openyt.core.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

interface PortableOperationRepository {
    suspend fun getOperation(id: String): PortableOperationJournalRecord?
    suspend fun listOperations(): List<PortableOperationJournalRecord>
    suspend fun listActiveOperations(): List<PortableOperationJournalRecord>
    suspend fun upsertOperation(record: PortableOperationJournalRecord)
    suspend fun markInterruptedOnStartup(nowEpochMillis: Long)
    suspend fun deleteOperation(id: String)
}

class MemoryPortableOperationRepository : PortableOperationRepository {
    private val records = MutableStateFlow<Map<String, PortableOperationJournalRecord>>(emptyMap())

    override suspend fun getOperation(id: String): PortableOperationJournalRecord? = records.value[id]

    override suspend fun listOperations(): List<PortableOperationJournalRecord> =
        records.value.values.sortedWith(compareByDescending<PortableOperationJournalRecord> { it.createdAtEpochMillis }.thenByDescending { it.id })

    override suspend fun listActiveOperations(): List<PortableOperationJournalRecord> =
        records.value.values.filter {
            it.status == PortableOperationStatus.PLANNED ||
                it.status == PortableOperationStatus.RUNNING ||
                it.status == PortableOperationStatus.STAGING_VERIFIED ||
                it.status == PortableOperationStatus.COMMITTING
        }.sortedBy { it.createdAtEpochMillis }

    override suspend fun upsertOperation(record: PortableOperationJournalRecord) {
        records.update { it + (record.id to record) }
    }

    override suspend fun markInterruptedOnStartup(nowEpochMillis: Long) {
        records.update { current ->
            current.mapValues { (_, rec) ->
                if (rec.status == PortableOperationStatus.RUNNING || rec.status == PortableOperationStatus.COMMITTING) {
                    rec.copy(status = PortableOperationStatus.INTERRUPTED, updatedAtEpochMillis = nowEpochMillis)
                } else {
                    rec
                }
            }
        }
    }

    override suspend fun deleteOperation(id: String) {
        records.update { it - id }
    }
}
