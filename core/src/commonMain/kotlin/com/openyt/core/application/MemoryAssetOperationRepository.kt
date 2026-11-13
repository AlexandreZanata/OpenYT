package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperation
import com.openyt.core.domain.AssetOperationStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemoryAssetOperationRepository : AssetOperationRepository {
    private val mutex = Mutex()
    private val operations = mutableMapOf<String, AssetOperation>()

    override suspend fun insertOperation(operation: AssetOperation) {
        mutex.withLock {
            operations[operation.id] = operation
        }
    }

    override suspend fun updateOperation(operation: AssetOperation) {
        mutex.withLock {
            operations[operation.id] = operation
        }
    }

    override suspend fun getOperation(id: String): AssetOperation? =
        mutex.withLock { operations[id] }

    override suspend fun loadOperationsForEntry(entryId: String): List<AssetOperation> =
        mutex.withLock {
            operations.values.filter { it.entryId == entryId }.sortedByDescending { it.createdAtEpochMillis }
        }

    override suspend fun loadActiveOperations(): List<AssetOperation> =
        mutex.withLock {
            operations.values.filter { it.status.isActive }.sortedBy { it.createdAtEpochMillis }
        }

    override suspend fun findPendingOperation(
        entryId: String,
        kind: AssetKind,
        language: String?,
        sourcePath: String,
    ): AssetOperation? = mutex.withLock {
        operations.values.firstOrNull {
            it.entryId == entryId &&
                it.kind == kind &&
                it.language == language &&
                it.sourcePath == sourcePath &&
                it.status.isActive
        }
    }

    override suspend fun markRunningAsInterrupted() {
        mutex.withLock {
            operations.replaceAll { _, op ->
                if (op.status == AssetOperationStatus.RUNNING) {
                    op.copy(status = AssetOperationStatus.INTERRUPTED)
                } else op
            }
        }
    }

    override suspend fun deleteOperation(id: String) {
        mutex.withLock {
            operations.remove(id)
        }
    }

    override suspend fun deleteOperationsForEntry(entryId: String) {
        mutex.withLock {
            operations.entries.removeAll { it.value.entryId == entryId }
        }
    }
}
