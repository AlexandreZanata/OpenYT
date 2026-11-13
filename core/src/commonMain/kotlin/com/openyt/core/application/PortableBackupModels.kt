package com.openyt.core.application

import com.openyt.core.domain.AssetKind
import com.openyt.core.domain.DownloadProfile
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.MediaRevision
import kotlinx.serialization.Serializable

object PortableBackupFormat {
    const val FORMAT_VERSION: Int = 1
    const val MIN_SUPPORTED_READER_VERSION: Int = 1
    const val MANIFEST_ENTRY_NAME: String = "manifest.json"
    const val MAX_MANIFEST_BYTES: Long = 16L * 1024L * 1024L
    const val MAX_ARCHIVE_ENTRIES: Int = 100_000
    const val PLAINTEXT_PRIVACY_MARKER: String = "UNENCRYPTED_PORTABLE_BACKUP"
}

@Serializable
data class PortableBlobRef(
    val archivePath: String,
    val byteCount: Long,
    val sha256: String,
    val originalFileName: String,
    val mediaKind: MediaKind? = null,
    val lastModifiedEpochMillis: Long? = null,
)

@Serializable
data class PortablePlaybackCheckpoint(
    val positionMillis: Long,
    val durationMillis: Long = 0L,
)

@Serializable
data class PortableStudyNote(
    val exportId: String,
    val timestampMillis: Long,
    val endMillis: Long? = null,
    val body: String,
    val mediaRevision: MediaRevision? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class PortableStudyBookmark(
    val exportId: String,
    val timestampMillis: Long,
    val label: String,
    val mediaRevision: MediaRevision? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class PortableStudyRange(
    val exportId: String,
    val startMillis: Long,
    val endMillis: Long,
    val label: String? = null,
    val mediaRevision: MediaRevision? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class PortableEntryStudySnapshot(
    val notes: List<PortableStudyNote> = emptyList(),
    val bookmarks: List<PortableStudyBookmark> = emptyList(),
    val ranges: List<PortableStudyRange> = emptyList(),
    val isStudied: Boolean = false,
    val studiedAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long = 0L,
) {
    val isEmpty: Boolean
        get() = notes.isEmpty() && bookmarks.isEmpty() && ranges.isEmpty() && !isStudied && studiedAtEpochMillis == null
}

@Serializable
data class PortableOfflineAssetRecord(
    val exportId: String,
    val kind: AssetKind,
    val language: String? = null,
    val sourceType: String? = null,
    val isRequired: Boolean = false,
    val isPinned: Boolean = false,
    val mediaRevision: MediaRevision,
    val createdAtEpochMillis: Long = 0L,
    val blob: PortableBlobRef? = null,
)

@Serializable
data class PortableEntryRecord(
    val exportId: String,
    val title: String,
    val kind: MediaKind,
    val author: String? = null,
    val source: String = "local",
    val thumbnailUrl: String? = null,
    val createdAtEpochMillis: Long = 0L,
    val sizeBytes: Long? = null,
    val tags: List<String> = emptyList(),
    val primaryMedia: PortableBlobRef? = null,
    val playbackCheckpoint: PortablePlaybackCheckpoint? = null,
    val study: PortableEntryStudySnapshot? = null,
    val offlineAssets: List<PortableOfflineAssetRecord> = emptyList(),
)

@Serializable
data class PortableCollectionRecord(
    val exportId: String,
    val name: String,
    val entryExportIds: List<String> = emptyList(),
)

@Serializable
data class PortableSmartCollectionRecord(
    val exportId: String,
    val name: String,
    val query: String = "",
    val kind: MediaKind? = null,
    val tag: String? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val createdWithinDays: Int? = null,
)

@Serializable
data class PortableDownloadProfileRecord(
    val exportId: String,
    val profile: DownloadProfile,
)

@Serializable
enum class PortableSkipReason {
    METADATA_ONLY,
    MISSING_SOURCE,
    PERMISSION_DENIED,
    NON_PORTABLE_ASSET,
}

@Serializable
data class PortableSkippedItem(
    val entryExportId: String,
    val itemKind: String,
    val label: String,
    val reason: PortableSkipReason,
)

@Serializable
data class PortableBackupManifest(
    val formatVersion: Int = PortableBackupFormat.FORMAT_VERSION,
    val minReaderVersion: Int = PortableBackupFormat.MIN_SUPPORTED_READER_VERSION,
    val bundleId: String,
    val createdAtEpochMillis: Long,
    val appVersion: String? = null,
    val privacyNotice: String = PortableBackupFormat.PLAINTEXT_PRIVACY_MARKER,
    val entries: List<PortableEntryRecord> = emptyList(),
    val collections: List<PortableCollectionRecord> = emptyList(),
    val smartCollections: List<PortableSmartCollectionRecord> = emptyList(),
    val downloadProfiles: List<PortableDownloadProfileRecord> = emptyList(),
    val skippedItems: List<PortableSkippedItem> = emptyList(),
) {
    fun declaredBlobs(): List<PortableBlobRef> = buildList {
        entries.forEach { entry ->
            entry.primaryMedia?.let(::add)
            entry.offlineAssets.forEach { asset -> asset.blob?.let(::add) }
        }
    }
}

@Serializable
data class PortableExportOptions(
    val includeMediaFiles: Boolean = true,
    val includeOfflineAssets: Boolean = true,
    val allowPartialMissing: Boolean = false,
    val selectedEntryIds: Set<String>? = null,
)

@Serializable
enum class PortableRestoreConflictPolicy {
    IMPORT_AS_NEW,
    SKIP_IF_PRESENT,
}

@Serializable
data class PortableRestoreOptions(
    val conflictPolicy: PortableRestoreConflictPolicy = PortableRestoreConflictPolicy.IMPORT_AS_NEW,
)

@Serializable
enum class PortableOperationDirection {
    EXPORT,
    RESTORE,
    RECOVERY_RELINK,
}

@Serializable
enum class PortableOperationStatus {
    PLANNED,
    RUNNING,
    STAGING_VERIFIED,
    COMMITTING,
    SUCCEEDED,
    CANCELED,
    FAILED,
    INTERRUPTED,
}

@Serializable
data class PortableOperationJournalRecord(
    val id: String,
    val direction: PortableOperationDirection,
    val status: PortableOperationStatus,
    val bundleId: String? = null,
    val archiveLocator: String,
    val archiveSha256: String? = null,
    val destinationRoot: String? = null,
    val stagingDir: String? = null,
    val totalBytes: Long = 0L,
    val completedBytes: Long = 0L,
    val entriesTotal: Int = 0,
    val entriesDone: Int = 0,
    val planJson: String? = null,
    val error: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

sealed class PortableBackupFailure(val userSafeCode: String, open val message: String) {
    data class InvalidManifest(override val message: String) : PortableBackupFailure("INVALID_MANIFEST", message)
    data class UnsupportedVersion(val foundVersion: Int, val minReaderVersion: Int) :
        PortableBackupFailure("UNSUPPORTED_VERSION", "Unsupported backup format (formatVersion=$foundVersion, minReader=$minReaderVersion)")
    data class SecurityViolation(override val message: String) : PortableBackupFailure("SECURITY_VIOLATION", message)
    data class ChecksumMismatch(val archivePath: String) : PortableBackupFailure("CHECKSUM_MISMATCH", "Checksum mismatch for $archivePath")
    data class SizeLimitExceeded(val limitName: String, val actual: Long, val limit: Long) :
        PortableBackupFailure("SIZE_LIMIT_EXCEEDED", "$limitName exceeded ($actual > $limit)")
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long, val reserveBytes: Long) :
        PortableBackupFailure("INSUFFICIENT_SPACE", "Insufficient space: need $requiredBytes plus reserve $reserveBytes, available $availableBytes")
    data class SourceMissing(val itemLabels: List<String>) :
        PortableBackupFailure("SOURCE_MISSING", "Missing source files: ${itemLabels.joinToString()}")
    data class SourceChanged(val itemLabel: String) :
        PortableBackupFailure("SOURCE_CHANGED", "Source changed while streaming: $itemLabel")
    data class PermissionDenied(val itemLabel: String) :
        PortableBackupFailure("PERMISSION_DENIED", "Permission denied: $itemLabel")
    data class PlanMismatch(override val message: String) : PortableBackupFailure("PLAN_MISMATCH", message)
    data class Canceled(override val message: String = "Operation canceled") : PortableBackupFailure("CANCELED", message)
    data class IoError(override val message: String) : PortableBackupFailure("IO_ERROR", message)

    fun toException(): PortableBackupException = PortableBackupException(this)
}

class PortableBackupException(
    val failure: PortableBackupFailure,
    cause: Throwable? = null,
) : Exception(failure.message, cause)
