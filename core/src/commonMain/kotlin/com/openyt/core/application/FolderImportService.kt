package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind

data class ScannedMedia(
    val path: String,
    val title: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long = 0,
)

fun interface FolderScanner {
    suspend fun scan(directory: String): List<ScannedMedia>
}

data class FolderImportResult(
    val added: Int = 0,
    val skipped: Int = 0,
)

/**
 * Imports existing media in place. Files are never copied, moved or deleted;
 * entries already present by path are skipped, so repeated scans are idempotent.
 */
class FolderImportService(
    private val scanner: FolderScanner,
    private val repository: DownloadRepository?,
) {
    suspend fun import(directory: String): FolderImportResult {
        val repo = repository ?: return FolderImportResult()
        val existingPaths = repo.loadLibrary().map { it.filePath }.toSet()
        val scanned = scanner.scan(directory)
        var added = 0
        var skipped = 0
        scanned.forEach { media ->
            if (media.path in existingPaths) {
                skipped++
                return@forEach
            }
            val entry = LibraryEntry(
                id = "folder-${media.path.hashCode().toUInt().toString(16)}",
                title = media.title,
                kind = media.kind,
                filePath = media.path,
                sizeBytes = media.sizeBytes,
                createdAtEpochMillis = media.modifiedAtEpochMillis,
                source = "import",
            )
            repo.insertLibraryEntry(entry)
            added++
        }
        return FolderImportResult(added = added, skipped = skipped)
    }
}
