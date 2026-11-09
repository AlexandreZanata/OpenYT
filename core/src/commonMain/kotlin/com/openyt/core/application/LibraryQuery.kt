package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind

data class LibraryFilter(
    val query: String = "",
    val kind: MediaKind? = null,
    val missingOnly: Boolean = false,
    val duplicatesOnly: Boolean = false,
) {
    val isDefault: Boolean
        get() = query.isBlank() && kind == null && !missingOnly && !duplicatesOnly
}

data class LibraryItem(
    val entry: LibraryEntry,
    val missing: Boolean,
    val duplicate: Boolean,
)

/**
 * Pure, in-memory library filtering. Kept out of the UI so search, availability,
 * and duplicate rules are testable and usable with large datasets.
 */
object LibraryQuery {
    fun apply(
        entries: List<LibraryEntry>,
        missingIds: Set<String>,
        filter: LibraryFilter,
    ): List<LibraryItem> {
        val duplicates = duplicateIds(entries)
        val query = filter.query.trim().lowercase()

        return entries.asSequence()
            .filter { entry ->
                val fileName = entry.filePath.substringAfterLast('/').substringAfterLast('\\')
                val matchesQuery = query.isEmpty() ||
                    entry.title.lowercase().contains(query) ||
                    fileName.lowercase().contains(query)
                val matchesKind = filter.kind == null || entry.kind == filter.kind
                val matchesMissing = !filter.missingOnly || entry.id in missingIds
                val matchesDuplicates = !filter.duplicatesOnly || entry.id in duplicates

                matchesQuery && matchesKind && matchesMissing && matchesDuplicates
            }
            .map { entry ->
                LibraryItem(
                    entry = entry,
                    missing = entry.id in missingIds,
                    duplicate = entry.id in duplicates,
                )
            }
            .toList()
    }

    /**
     * Marks entries that share a file path or that look like the same media
     * (same normalized title and size). Detection never deletes anything.
     */
    fun duplicateIds(entries: List<LibraryEntry>): Set<String> {
        val byPath = entries
            .filter { it.filePath.isNotBlank() }
            .groupBy { it.filePath }
            .values
            .filter { it.size > 1 }
            .flatten()

        val byContent = entries
            .filter { it.sizeBytes != null }
            .groupBy { it.title.trim().lowercase() to it.sizeBytes }
            .values
            .filter { it.size > 1 }
            .flatten()

        return (byPath + byContent).map { it.id }.toSet()
    }

    /**
     * Ids that can be removed to leave one entry per duplicate group. The first
     * entry in each group (newest, since the repository orders by creation) is kept.
     * This only removes library records; files are never deleted.
     */
    fun duplicateRemovalIds(entries: List<LibraryEntry>): Set<String> {
        val ids = mutableSetOf<String>()

        fun mark(groups: Collection<List<LibraryEntry>>) {
            groups.filter { it.size > 1 }.forEach { group ->
                group.drop(1).forEach { ids += it.id }
            }
        }

        mark(
            entries.filter { it.filePath.isNotBlank() }
                .groupBy { it.filePath }
                .values,
        )
        mark(
            entries.filter { it.sizeBytes != null }
                .groupBy { it.title.trim().lowercase() to it.sizeBytes }
                .values,
        )
        return ids
    }
}
