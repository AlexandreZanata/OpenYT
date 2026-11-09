package com.openyt.data

import app.cash.sqldelight.db.SqlDriver
import com.openyt.core.application.DownloadRepository
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry
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
            id = task.id,
        )
        Unit
    }

    override suspend fun deleteTask(id: String) = withContext(Dispatchers.Default) {
        queries.deleteTask(id)
        Unit
    }

    override suspend fun markActiveInterrupted() = withContext(Dispatchers.Default) {
        queries.markActiveInterrupted()
        Unit
    }

    override suspend fun loadLibrary(): List<LibraryEntry> = withContext(Dispatchers.Default) {
        queries.selectAllLibrary().executeAsList().map { row ->
            LibraryEntry(
                id = row.id,
                title = row.title,
                kind = parseKind(row.kind),
                filePath = row.file_path,
                sizeBytes = row.size_bytes,
            )
        }
    }

    override suspend fun insertLibraryEntry(entry: LibraryEntry) = withContext(Dispatchers.Default) {
        queries.insertLibraryEntry(
            id = entry.id,
            title = entry.title,
            kind = entry.kind.name,
            file_path = entry.filePath,
            size_bytes = entry.sizeBytes,
        )
        Unit
    }

    override suspend fun deleteLibraryEntry(id: String) = withContext(Dispatchers.Default) {
        queries.deleteLibraryEntry(id)
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
    )

    private fun parseKind(value: String): MediaKind =
        runCatching { MediaKind.valueOf(value) }.getOrDefault(MediaKind.VIDEO)

    private fun parseStatus(value: String): DownloadStatus =
        runCatching { DownloadStatus.valueOf(value) }.getOrDefault(DownloadStatus.FAILED)

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
