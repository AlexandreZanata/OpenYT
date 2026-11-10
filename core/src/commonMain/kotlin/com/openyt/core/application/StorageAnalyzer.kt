package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry

data class StorageGroup(
    val entries: List<LibraryEntry>,
    val totalBytes: Long,
)

data class StorageReport(
    val totalEntries: Int,
    val totalBytes: Long,
    val largest: List<LibraryEntry>,
    val duplicateGroups: List<StorageGroup>,
    val missing: List<LibraryEntry>,
    val orphanFiles: List<ScannedMedia>,
)

/**
 * Read-only storage summary. It never deletes or moves files; orphan detection
 * only reports files present on disk that are absent from the library.
 */
object StorageAnalyzer {
    fun analyze(
        entries: List<LibraryEntry>,
        missingIds: Set<String>,
        knownFiles: List<ScannedMedia>,
        largestLimit: Int = 10,
    ): StorageReport {
        val totalBytes = entries.sumOf { it.sizeBytes ?: 0L }
        val largest = entries
            .filter { it.sizeBytes != null }
            .sortedByDescending { it.sizeBytes ?: 0L }
            .take(largestLimit)

        val groups = mutableListOf<StorageGroup>()
        val seen = mutableSetOf<Set<String>>()
        fun addGroup(candidates: List<LibraryEntry>) {
            if (candidates.size < 2) return
            val ids = candidates.map { it.id }.toSet()
            if (ids.size < 2 || !seen.add(ids)) return
            groups += StorageGroup(
                entries = candidates,
                totalBytes = candidates.sumOf { it.sizeBytes ?: 0L },
            )
        }
        entries.filter { it.filePath.isNotBlank() }
            .groupBy { it.filePath }
            .values
            .forEach(::addGroup)
        entries.filter { it.sizeBytes != null }
            .groupBy { it.title.trim().lowercase() to it.sizeBytes }
            .values
            .forEach(::addGroup)

        val libraryPaths = entries.map { it.filePath }.toSet()
        return StorageReport(
            totalEntries = entries.size,
            totalBytes = totalBytes,
            largest = largest,
            duplicateGroups = groups.sortedByDescending { it.totalBytes },
            missing = entries.filter { it.id in missingIds },
            orphanFiles = knownFiles.filter { it.path !in libraryPaths },
        )
    }
}
