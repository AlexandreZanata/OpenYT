package com.openyt.core.application

data class StagedArtifact(
    val stagingPath: String,
    val byteCount: Long,
    val sha256: String,
)

interface AssetStagingPort {
    fun getAvailableSpace(): Long
    suspend fun prepareStagingDirectory(operationId: String): String
    suspend fun stageAndHash(
        operationId: String,
        sourcePath: String,
        stagingPath: String,
        byteLimit: Long? = null,
        onBytesWritten: ((Long) -> Unit)? = null,
    ): Result<StagedArtifact>
    suspend fun stageBytesAndHash(
        operationId: String,
        bytes: ByteArray,
        stagingPath: String,
    ): Result<StagedArtifact>
    suspend fun promote(stagingPath: String, targetLocator: String): Result<String>
    suspend fun cleanupStaging(operationId: String)
    suspend fun cleanupOrphanedStaging(activeOperationIds: Set<String>)
    fun fileExists(path: String): Boolean
    fun fileSize(path: String): Long
    suspend fun computeSha256(path: String): String?
    fun resolveTargetLocator(targetLocator: String): String
    suspend fun deleteTarget(targetLocator: String): Boolean
}
