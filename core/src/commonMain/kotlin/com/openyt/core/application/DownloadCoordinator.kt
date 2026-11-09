package com.openyt.core.application

import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.MediaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates in-session downloads and exposes immutable task snapshots.
 *
 * Persistence arrives in Phase 3; this coordinator is the single owner of active
 * jobs. At most one download per [enqueue] call; [enqueue] is expected to be called
 * from a single UI thread.
 */
class DownloadCoordinator(
    private val engine: DownloadEngine,
    private val scope: CoroutineScope,
) {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private var nextId = 0

    fun enqueue(media: MediaInfo, request: DownloadRequest): String {
        val id = "download-${++nextId}"
        _tasks.update { it + DownloadTask(id = id, media = media, request = request) }

        scope.launch {
            try {
                engine.download(request, id).collect { event -> applyEvent(id, event) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                applyEvent(id, DownloadEvent.Failed(DownloadFailure.Unexpected(null)))
            }
        }
        return id
    }

    fun cancel(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!task.status.isActive) return
        scope.launch { engine.cancel(id) }
    }

    fun dismiss(id: String) {
        _tasks.update { tasks ->
            tasks.filterNot { it.id == id && it.status.isFinished }
        }
    }

    fun clearFinished() {
        _tasks.update { tasks -> tasks.filter { it.status.isActive } }
    }

    private fun applyEvent(id: String, event: DownloadEvent) {
        _tasks.update { tasks ->
            tasks.map { task ->
                if (task.id == id) task.apply(event) else task
            }
        }
    }

    private fun DownloadTask.apply(event: DownloadEvent): DownloadTask = when (event) {
        DownloadEvent.Starting -> copy(status = DownloadStatus.DOWNLOADING)

        is DownloadEvent.Progress -> copy(
            status = DownloadStatus.DOWNLOADING,
            percent = event.percent,
            speedBytesPerSecond = event.speedBytesPerSecond,
            etaSeconds = event.etaSeconds,
        )

        DownloadEvent.Processing -> copy(status = DownloadStatus.PROCESSING, percent = 100.0)

        is DownloadEvent.Completed -> copy(
            status = DownloadStatus.COMPLETED,
            percent = 100.0,
            filePath = event.filePath,
            sizeBytes = event.sizeBytes,
        )

        is DownloadEvent.Failed -> copy(
            status = DownloadStatus.FAILED,
            failure = event.failure,
        )

        DownloadEvent.Canceled -> copy(status = DownloadStatus.CANCELED)
    }
}
