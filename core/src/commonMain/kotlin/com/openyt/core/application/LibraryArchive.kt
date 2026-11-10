package com.openyt.core.application

import com.openyt.core.domain.Collection
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.SmartCollection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class LibraryArchive(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val entries: List<LibraryEntryRecord> = emptyList(),
    val collections: List<CollectionRecord> = emptyList(),
    val membership: Map<String, List<String>> = emptyMap(),
    val smartCollections: List<SmartCollectionRecord> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

@Serializable
data class LibraryEntryRecord(
    val id: String,
    val title: String,
    val author: String? = null,
    val kind: String,
    val filePath: String,
    val sizeBytes: Long? = null,
    val createdAtEpochMillis: Long = 0,
    val thumbnailUrl: String? = null,
    val source: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class CollectionRecord(
    val id: String,
    val name: String,
)

@Serializable
data class SmartCollectionRecord(
    val id: String,
    val name: String,
    val query: String = "",
    val kind: String? = null,
    val tag: String? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val createdWithinDays: Int? = null,
)

data class LibraryImportResult(
    val entriesImported: Int = 0,
    val entriesUpdated: Int = 0,
    val entriesSkipped: Int = 0,
    val collectionsImported: Int = 0,
    val smartCollectionsImported: Int = 0,
)

enum class LibraryImportPolicy {
    MERGE,
    REPLACE,
}

object LibraryArchiveCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(archive: LibraryArchive): String = json.encodeToString(archive)

    fun decode(raw: String): Result<LibraryArchive> = runCatching {
        val archive = json.decodeFromString<LibraryArchive>(raw)
        require(archive.schemaVersion == LibraryArchive.CURRENT_SCHEMA) {
            "unsupported library archive schema: ${archive.schemaVersion}"
        }
        archive
    }
}

fun LibraryEntry.toRecord(includePaths: Boolean): LibraryEntryRecord = LibraryEntryRecord(
    id = id,
    title = title,
    author = author,
    kind = kind.name,
    filePath = if (includePaths) filePath else "",
    sizeBytes = sizeBytes,
    createdAtEpochMillis = createdAtEpochMillis,
    thumbnailUrl = thumbnailUrl,
    source = source,
    tags = tags,
)

fun LibraryEntryRecord.toEntry(): LibraryEntry = LibraryEntry(
    id = id,
    title = title,
    kind = runCatching { MediaKind.valueOf(kind) }.getOrDefault(MediaKind.VIDEO),
    filePath = filePath,
    sizeBytes = sizeBytes,
    createdAtEpochMillis = createdAtEpochMillis,
    thumbnailUrl = thumbnailUrl,
    source = source,
    tags = tags,
    author = author,
)

fun Collection.toRecord(): CollectionRecord = CollectionRecord(id, name)

fun CollectionRecord.toCollection(): Collection = Collection(id, name)

fun SmartCollection.toRecord(): SmartCollectionRecord = SmartCollectionRecord(
    id = id,
    name = name,
    query = query,
    kind = kind?.name,
    tag = tag,
    minSizeBytes = minSizeBytes,
    maxSizeBytes = maxSizeBytes,
    createdWithinDays = createdWithinDays,
)

fun SmartCollectionRecord.toSmartCollection(): SmartCollection = SmartCollection(
    id = id,
    name = name,
    query = query,
    kind = kind?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() },
    tag = tag,
    minSizeBytes = minSizeBytes,
    maxSizeBytes = maxSizeBytes,
    createdWithinDays = createdWithinDays,
)
