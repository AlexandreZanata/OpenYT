package com.openyt.data

import app.cash.sqldelight.db.SqlDriver
import com.openyt.core.application.DownloadRepository
import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadPriority
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.SmartCollection
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.data.db.Download_task
import com.openyt.data.db.OpenYtDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqlDelightDownloadRepository(
    private val database: OpenYtDatabase,
) : DownloadRepository {
    private val queries = database.downloadQueries

    override suspend fun loadTasks(): List<DownloadTask> = withContext(Dispatchers.Default) {
        queries.selectAllTasks().executeAsList().map(::toTask)
    }

    override suspend fun insertTask(task: DownloadTask) = withContext(Dispatchers.Default) {
        val (failureKind, failureDetail) = task.failure.stored()
        queries.insertTask(
            id = task.id,
            source_id = task.media.sourceId,
            title = task.media.title,
            author = task.media.author,
            source = task.media.source,
            url = task.request.url,
            webpage_url = task.media.webpageUrl,
            duration_seconds = task.media.durationSeconds,
            thumbnail_url = task.media.thumbnailUrl,
            kind = task.request.kind.name,
            format_id = task.request.formatId,
            destination_directory = task.request.destinationDirectory,
            status = task.status.name,
            percent = task.percent,
            speed_bytes_per_second = task.speedBytesPerSecond,
            eta_seconds = task.etaSeconds,
            file_path = task.filePath,
            size_bytes = task.sizeBytes,
            failure_kind = failureKind,
            failure_detail = failureDetail,
            priority = task.priority.name,
            attempt = task.attempt.toLong(),
        )
        Unit
    }

    override suspend fun updateTask(task: DownloadTask) = withContext(Dispatchers.Default) {
        val (failureKind, failureDetail) = task.failure.stored()
        queries.updateTask(
            status = task.status.name,
            percent = task.percent,
            speed_bytes_per_second = task.speedBytesPerSecond,
            eta_seconds = task.etaSeconds,
            file_path = task.filePath,
            size_bytes = task.sizeBytes,
            failure_kind = failureKind,
            failure_detail = failureDetail,
            priority = task.priority.name,
            attempt = task.attempt.toLong(),
            id = task.id,
        )
        Unit
    }

    override suspend fun deleteTask(id: String) = withContext(Dispatchers.Default) {
        queries.deleteTask(id)
        Unit
    }

    override suspend fun loadCollections(): List<Collection> = withContext(Dispatchers.Default) {
        queries.selectAllCollections { id, name, _ ->
            Collection(id = id, name = name)
        }.executeAsList()
    }

    override suspend fun insertCollection(collection: Collection) = withContext(Dispatchers.Default) {
        queries.insertCollection(id = collection.id, name = collection.name)
        Unit
    }

    override suspend fun deleteCollection(id: String) = withContext(Dispatchers.Default) {
        database.transaction {
            queries.deleteCollectionEntries(collection_id = id)
            queries.deleteCollection(id = id)
        }
        Unit
    }

    override suspend fun loadCollectionMembership(): Map<String, Set<String>> =
        withContext(Dispatchers.Default) {
            queries.selectCollectionMembership { collectionId, entryId ->
                collectionId to entryId
            }.executeAsList()
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, entries) -> entries.toSet() }
        }

    override suspend fun addEntryToCollection(collectionId: String, entryId: String) =
        withContext(Dispatchers.Default) {
            queries.insertCollectionEntry(collection_id = collectionId, entry_id = entryId)
            Unit
        }

    override suspend fun removeEntryFromCollection(collectionId: String, entryId: String) =
        withContext(Dispatchers.Default) {
            queries.deleteCollectionEntry(collection_id = collectionId, entry_id = entryId)
            Unit
        }

    override suspend fun loadSmartCollections(): List<SmartCollection> =
        withContext(Dispatchers.Default) {
            queries.selectAllSmartCollections().executeAsList().map { row ->
                SmartCollection(
                    id = row.id,
                    name = row.name,
                    query = row.query,
                    kind = row.kind?.let(::parseKind),
                    tag = row.tag,
                    minSizeBytes = row.min_size_bytes,
                    maxSizeBytes = row.max_size_bytes,
                    createdWithinDays = row.created_within_days?.toInt(),
                )
            }
        }

    override suspend fun insertSmartCollection(collection: SmartCollection) =
        withContext(Dispatchers.Default) {
            queries.insertSmartCollection(
                collection.id,
                collection.name,
                collection.query,
                collection.kind?.name,
                collection.tag,
                collection.minSizeBytes,
                collection.maxSizeBytes,
                collection.createdWithinDays?.toLong(),
            )
            Unit
        }

    override suspend fun deleteSmartCollection(id: String) =
        withContext(Dispatchers.Default) {
            queries.deleteSmartCollection(id)
            Unit
        }

    override suspend fun markActiveInterrupted() = withContext(Dispatchers.Default) {
        queries.markActiveInterrupted()
        Unit
    }

    override suspend fun loadLibrary(): List<LibraryEntry> = withContext(Dispatchers.Default) {
        val tags = queries.selectAllLibraryTags()
            .executeAsList()
            .groupBy({ it.entry_id }, { it.tag })
        queries.selectAllLibrary().executeAsList().map { row ->
            LibraryEntry(
                id = row.id,
                title = row.title,
                author = row.author,
                kind = parseKind(row.kind),
                filePath = row.file_path,
                sizeBytes = row.size_bytes,
                createdAtEpochMillis = row.created_at,
                thumbnailUrl = row.thumbnail_url,
                source = row.source,
                tags = tags[row.id].orEmpty(),
            )
        }
    }

    override suspend fun insertLibraryEntry(entry: LibraryEntry) = withContext(Dispatchers.Default) {
        database.transaction {
            queries.insertLibraryEntry(
                id = entry.id,
                title = entry.title,
                author = entry.author,
                kind = entry.kind.name,
                file_path = entry.filePath,
                size_bytes = entry.sizeBytes,
                thumbnail_url = entry.thumbnailUrl,
                source = entry.source,
            )
            queries.deleteLibraryTagsForEntry(entry.id)
            entry.tags.forEach { tag -> queries.insertLibraryTag(entry.id, tag) }
        }
        Unit
    }

    override suspend fun deleteLibraryEntry(id: String) = withContext(Dispatchers.Default) {
        database.transaction {
            queries.deleteLibraryTagsForEntry(id)
            queries.deleteCollectionEntriesForEntry(entry_id = id)
            queries.deleteLibraryEntry(id)
        }
        Unit
    }

    override suspend fun updateLibraryMetadata(id: String, title: String, author: String?) =
        withContext(Dispatchers.Default) {
            queries.updateLibraryMetadata(title, author, id)
            Unit
        }

    override suspend fun addLibraryTag(entryId: String, tag: String) =
        withContext(Dispatchers.Default) {
            queries.insertLibraryTag(entryId, tag)
            Unit
        }

    override suspend fun removeLibraryTag(entryId: String, tag: String) =
        withContext(Dispatchers.Default) {
            queries.deleteLibraryTag(entryId, tag)
            Unit
        }

    private fun toTask(row: Download_task): DownloadTask = DownloadTask(
        id = row.id,
        media = MediaInfo(
            sourceId = row.source_id,
            source = row.source,
            title = row.title,
            author = row.author,
            durationSeconds = row.duration_seconds,
            thumbnailUrl = row.thumbnail_url,
            webpageUrl = row.webpage_url,
            formats = emptyList(),
        ),
        request = DownloadRequest(
            url = row.url,
            kind = parseKind(row.kind),
            formatId = row.format_id,
            destinationDirectory = row.destination_directory,
        ),
        status = parseStatus(row.status),
        percent = row.percent,
        speedBytesPerSecond = row.speed_bytes_per_second,
        etaSeconds = row.eta_seconds,
        filePath = row.file_path,
        sizeBytes = row.size_bytes,
        failure = failureFrom(row.failure_kind, row.failure_detail),
        priority = parsePriority(row.priority),
        attempt = row.attempt.toInt(),
    )

    private fun parseKind(value: String): MediaKind =
        runCatching { MediaKind.valueOf(value) }.getOrDefault(MediaKind.VIDEO)

    private fun parseStatus(value: String): DownloadStatus =
        runCatching { DownloadStatus.valueOf(value) }.getOrDefault(DownloadStatus.FAILED)

    private fun parsePriority(value: String): DownloadPriority =
        runCatching { DownloadPriority.valueOf(value) }.getOrDefault(DownloadPriority.NORMAL)

    private fun DownloadFailure?.stored(): Pair<String?, String?> = when (this) {
        null -> null to null
        is DownloadFailure.InvalidUrl -> FAILURE_INVALID_URL to reason
        DownloadFailure.UnsupportedSource -> FAILURE_UNSUPPORTED_SOURCE to null
        is DownloadFailure.Unavailable -> FAILURE_UNAVAILABLE to reason
        DownloadFailure.Network -> FAILURE_NETWORK to null
        DownloadFailure.InsufficientSpace -> FAILURE_INSUFFICIENT_SPACE to null
        DownloadFailure.PermissionDenied -> FAILURE_PERMISSION_DENIED to null
        is DownloadFailure.DependencyMissing -> FAILURE_DEPENDENCY_MISSING to component
        is DownloadFailure.ProcessingFailed -> FAILURE_PROCESSING_FAILED to reason
        is DownloadFailure.Unexpected -> FAILURE_UNEXPECTED to reason
    }

    private fun failureFrom(kind: String?, detail: String?): DownloadFailure? = when (kind) {
        null -> null
        FAILURE_INVALID_URL -> DownloadFailure.InvalidUrl(detail.orEmpty())
        FAILURE_UNSUPPORTED_SOURCE -> DownloadFailure.UnsupportedSource
        FAILURE_UNAVAILABLE -> DownloadFailure.Unavailable(detail)
        FAILURE_NETWORK -> DownloadFailure.Network
        FAILURE_INSUFFICIENT_SPACE -> DownloadFailure.InsufficientSpace
        FAILURE_PERMISSION_DENIED -> DownloadFailure.PermissionDenied
        FAILURE_DEPENDENCY_MISSING -> DownloadFailure.DependencyMissing(detail.orEmpty())
        FAILURE_PROCESSING_FAILED -> DownloadFailure.ProcessingFailed(detail)
        else -> DownloadFailure.Unexpected(detail)
    }

    private companion object {
        const val FAILURE_INVALID_URL = "INVALID_URL"
        const val FAILURE_UNSUPPORTED_SOURCE = "UNSUPPORTED_SOURCE"
        const val FAILURE_UNAVAILABLE = "UNAVAILABLE"
        const val FAILURE_NETWORK = "NETWORK"
        const val FAILURE_INSUFFICIENT_SPACE = "INSUFFICIENT_SPACE"
        const val FAILURE_PERMISSION_DENIED = "PERMISSION_DENIED"
        const val FAILURE_DEPENDENCY_MISSING = "DEPENDENCY_MISSING"
        const val FAILURE_PROCESSING_FAILED = "PROCESSING_FAILED"
        const val FAILURE_UNEXPECTED = "UNEXPECTED"
    }
}

fun openYtDatabase(driver: SqlDriver): OpenYtDatabase = OpenYtDatabase(driver)
