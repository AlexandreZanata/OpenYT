package com.openyt.core.application

/**
 * Exports and imports library metadata. Files are never touched; paths may be
 * omitted for privacy, and missing files are imported so the UI can flag them.
 */
class LibraryArchiveService(
    private val repository: DownloadRepository?,
) {
    suspend fun export(includePaths: Boolean = true): LibraryArchive {
        val repo = repository ?: return LibraryArchive()
        val entries = repo.loadLibrary()
        val collections = repo.loadCollections()
        val membership = repo.loadCollectionMembership()
        val smartCollections = repo.loadSmartCollections()
        return LibraryArchive(
            entries = entries.map { it.toRecord(includePaths) },
            collections = collections.map { it.toRecord() },
            membership = membership.mapValues { (_, ids) -> ids.toList() },
            smartCollections = smartCollections.map { it.toRecord() },
        )
    }

    suspend fun import(
        archive: LibraryArchive,
        policy: LibraryImportPolicy = LibraryImportPolicy.MERGE,
    ): LibraryImportResult {
        val repo = repository ?: return LibraryImportResult()
        val existingEntries = repo.loadLibrary().associateBy { it.id }
        var imported = 0
        var updated = 0
        var skipped = 0

        archive.entries.forEach { record ->
            val entry = record.toEntry()
            val current = existingEntries[entry.id]
            when {
                current == null -> {
                    repo.insertLibraryEntry(entry)
                    imported++
                }

                policy == LibraryImportPolicy.REPLACE -> {
                    repo.insertLibraryEntry(entry)
                    updated++
                }

                else -> {
                    val merged = current.copy(
                        title = entry.title,
                        author = entry.author ?: current.author,
                        kind = entry.kind,
                        filePath = current.filePath.ifBlank { entry.filePath },
                        sizeBytes = entry.sizeBytes ?: current.sizeBytes,
                        thumbnailUrl = entry.thumbnailUrl ?: current.thumbnailUrl,
                        source = entry.source ?: current.source,
                        tags = (current.tags + entry.tags).distinctBy { it.lowercase() },
                    )
                    if (merged == current) {
                        skipped++
                    } else {
                        repo.insertLibraryEntry(merged)
                        updated++
                    }
                }
            }
        }

        val existingCollections = repo.loadCollections()
        val byName = existingCollections.associateBy { it.name.lowercase() }
        val collectionIds = mutableMapOf<String, String>()
        var collectionsImported = 0
        archive.collections.forEach { record ->
            val existing = byName[record.name.lowercase()]
            if (existing != null) {
                collectionIds[record.id] = existing.id
            } else {
                repo.insertCollection(record.toCollection())
                collectionIds[record.id] = record.id
                collectionsImported++
            }
        }
        archive.membership.forEach { (archiveCollectionId, entryIds) ->
            val target = collectionIds[archiveCollectionId] ?: return@forEach
            entryIds.forEach { entryId -> repo.addEntryToCollection(target, entryId) }
        }

        val existingSmart = repo.loadSmartCollections().map { it.id }.toSet()
        var smartImported = 0
        archive.smartCollections.forEach { record ->
            if (record.id !in existingSmart) {
                repo.insertSmartCollection(record.toSmartCollection())
                smartImported++
            }
        }

        return LibraryImportResult(
            entriesImported = imported,
            entriesUpdated = updated,
            entriesSkipped = skipped,
            collectionsImported = collectionsImported,
            smartCollectionsImported = smartImported,
        )
    }
}
