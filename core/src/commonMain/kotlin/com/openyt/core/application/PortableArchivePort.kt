package com.openyt.core.application

data class PortableStreamedBlobInfo(
    val archivePath: String,
    val byteCount: Long,
    val sha256: String,
)

sealed interface PortableSourceMetadata {
    data object Missing : PortableSourceMetadata
    data class PermissionDenied(val reason: String) : PortableSourceMetadata
    data class Readable(
        val sizeBytes: Long,
        val lastModifiedEpochMillis: Long,
        val displayName: String,
    ) : PortableSourceMetadata
    data class Unreadable(val reason: String) : PortableSourceMetadata
}

data class PortableOwnedFileReceipt(
    val targetLocator: String,
    val relativeLocator: String,
    val byteCount: Long,
    val sha256: String,
    val lastModifiedEpochMillis: Long,
)

interface PortableArchiveOutputSession {
    val archiveLocator: String

    suspend fun writeFileEntry(
        archivePath: String,
        sourceLocator: String,
        onProgress: suspend (bytesWrittenDelta: Long) -> Unit = {},
    ): Result<PortableStreamedBlobInfo>

    suspend fun writeJsonEntry(
        archivePath: String,
        content: String,
    ): Result<PortableStreamedBlobInfo>

    /** Finishes ZIP headers, flushes/promotes output, and returns the archive SHA-256 hex digest. */
    suspend fun commit(): Result<String>

    suspend fun abortAndCleanup()
}

interface PortableArchiveWriterPort {
    suspend fun openOutputSession(
        archiveLocator: String,
        operationId: String,
    ): Result<PortableArchiveOutputSession>
}

enum class PortableReadEntryDisposition {
    SKIP_DIRECTORY,
    BUFFER_MANIFEST,
    STAGE_BLOB,
}

data class PortableStagedEntryResult(
    val archivePath: String,
    val byteCount: Long,
    val sha256: String,
    val stagedFileLocator: String?,
)

data class PortableArchiveReadSummary(
    val archiveSha256: String,
    val archiveByteCount: Long,
    val entryCount: Int,
    val totalExpandedBytes: Long,
    val manifestBytes: ByteArray?,
    val stagedBlobsByPath: Map<String, PortableStagedEntryResult>,
)

interface PortableArchiveReadPolicy {
    val maxManifestBytes: Long get() = PortableBackupFormat.MAX_MANIFEST_BYTES
    val maxEntryCount: Int get() = PortableBackupFormat.MAX_ARCHIVE_ENTRIES
    val maxTotalExpandedBytes: Long

    fun inspectEntry(
        entryIndex: Int,
        rawName: String,
        isDirectory: Boolean,
        declaredSizeBytes: Long,
    ): Result<PortableReadEntryDisposition>
}

interface PortableArchiveReaderPort {
    suspend fun readAndStageStream(
        archiveLocator: String,
        stagingDir: String,
        policy: PortableArchiveReadPolicy,
        onProgress: suspend (compressedBytesRead: Long, entriesProcessed: Int) -> Unit = { _, _ -> },
    ): Result<PortableArchiveReadSummary>
}

interface PortableStorageAccessPort {
    fun availableBytesForDestination(locator: String): Long
    fun availableBytesForStaging(): Long
    fun inspectSource(sourceLocator: String): PortableSourceMetadata
    suspend fun prepareStagingDirectory(operationId: String): String
    suspend fun cleanupStagingDirectory(operationId: String)
    suspend fun cleanupOrphanedStaging(activeOperationIds: Set<String>)
    suspend fun copyStagedFileToOwnedTarget(
        stagedPath: String,
        destinationRoot: String,
        relativeSubdir: String,
        desiredFileName: String,
        expectedSha256: String,
        expectedByteCount: Long,
    ): Result<PortableOwnedFileReceipt>
    suspend fun checkFileSha256(locator: String): String?
}
