package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.AssetOperation

interface AssetOperationRepository {
    suspend fun insertOperation(operation: AssetOperation)
    suspend fun updateOperation(operation: AssetOperation)
    suspend fun getOperation(id: String): AssetOperation?
    suspend fun loadOperationsForEntry(entryId: String): List<AssetOperation>
    suspend fun loadActiveOperations(): List<AssetOperation>
    suspend fun findPendingOperation(
        entryId: String,
        kind: AssetKind,
        language: String?,
        sourcePath: String,
    ): AssetOperation?
    suspend fun markRunningAsInterrupted()
    suspend fun deleteOperation(id: String)
    suspend fun deleteOperationsForEntry(entryId: String)
}
