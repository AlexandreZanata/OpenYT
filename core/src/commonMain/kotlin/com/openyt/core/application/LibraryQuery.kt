package com.openyt.core.application

import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.SmartCollection

data class LibraryFilter(
    val query: String = "",
    val kind: MediaKind? = null,
    val missingOnly: Boolean = false,
    val duplicatesOnly: Boolean = false,
    val tag: String? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val createdWithinDays: Int? = null,
) {
    val isDefault: Boolean
        get() = query.isBlank() &&
            kind == null &&
            !missingOnly &&
            !duplicatesOnly &&
            tag.isNullOrBlank() &&
            minSizeBytes == null &&
            maxSizeBytes == null &&
            createdWithinDays == null
}

fun SmartCollection.toLibraryFilter(): LibraryFilter = LibraryFilter(
    query = query,
    kind = kind,
    tag = tag,
    minSizeBytes = minSizeBytes,
    maxSizeBytes = maxSizeBytes,
    createdWithinDays = createdWithinDays,
)

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
        nowEpochMillis: Long = 0L,
    ): List<LibraryItem> {
        val duplicates = duplicateIds(entries)
        val query = filter.query.trim().lowercase()

        val items = entries.asSequence()
            .filter { entry ->
                val fileName = entry.filePath.substringAfterLast('/').substringAfterLast('\\')
                val matchesQuery = query.isEmpty() ||
                    entry.title.lowercase().contains(query) ||
                    entry.author?.lowercase()?.contains(query) == true ||
                    fileName.lowercase().contains(query) ||
                    entry.source?.lowercase()?.contains(query) == true ||
                    entry.tags.any { it.lowercase().contains(query) }
                val matchesKind = filter.kind == null || entry.kind == filter.kind
                val matchesMissing = !filter.missingOnly || entry.id in missingIds
                val matchesDuplicates = !filter.duplicatesOnly || entry.id in duplicates
                val matchesTag = filter.tag.isNullOrBlank() ||
                    entry.tags.any { it.equals(filter.tag, ignoreCase = true) }
                val size = entry.sizeBytes
                val matchesSize =
                    (filter.minSizeBytes == null || (size != null && size >= filter.minSizeBytes)) &&
                        (filter.maxSizeBytes == null || (size != null && size <= filter.maxSizeBytes))
                val matchesCreated = filter.createdWithinDays?.let { days ->
                    val created = entry.createdAtEpochMillis
                    nowEpochMillis > 0 &&
                        created > 0 &&
                        nowEpochMillis - created <= days * MILLIS_PER_DAY
                } ?: true

                matchesQuery &&
                    matchesKind &&
                    matchesMissing &&
                    matchesDuplicates &&
                    matchesTag &&
                    matchesSize &&
                    matchesCreated
            }
            .map { entry ->
                LibraryItem(
                    entry = entry,
                    missing = entry.id in missingIds,
                    duplicate = entry.id in duplicates,
                )
            }
            .toList()

        if (query.isEmpty()) return items
        return items.sortedWith(
            compareByDescending<LibraryItem> { score(it.entry, query) }
                .thenByDescending { it.entry.createdAtEpochMillis },
        )
    }

    /**
     * Higher score means a more direct match. Title beats author, tag, source
     * and file name; the relative ordering is stable for equal scores.
     */
    private fun score(entry: LibraryEntry, query: String): Int {
        val title = entry.title.lowercase()
        val author = entry.author?.lowercase()
        val source = entry.source?.lowercase()
        val fileName = entry.filePath
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase()

        var score = 0
        when {
            title == query -> score += 100
            title.startsWith(query) -> score += 80
            title.contains(query) -> score += 60
        }
        when {
            author == query -> score += 70
            author?.contains(query) == true -> score += 50
        }
        when {
            entry.tags.any { it.equals(query, ignoreCase = true) } -> score += 55
            entry.tags.any { it.lowercase().contains(query) } -> score += 40
        }
        if (source?.contains(query) == true) score += 30
        if (fileName.contains(query)) score += 20
        return score
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

    private const val MILLIS_PER_DAY = 86_400_000L
}
