package com.openyt.core.application

import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the in-memory view of the persistent download queue and library.
 *
 * Persistence is optional (useful in tests and previews). Writes are serialized
 * through a channel so a slow database cannot reorder state transitions. Progress
 * is persisted at 10% intervals; terminal states are always persisted.
 *
 * [enqueue] is expected to be called from a single UI thread.
 */
class DownloadCoordinator(
    private val engine: DownloadEngine,
    private val scope: CoroutineScope,
    private val repository: DownloadRepository? = null,
    initialMaxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT,
    private val completedHandler: CompletedDownloadHandler? = null,
) {
    init {
        require(initialMaxConcurrentDownloads > 0) { "initialMaxConcurrentDownloads must be positive" }
    }

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _library = MutableStateFlow<List<LibraryEntry>>(emptyList())
    val library: StateFlow<List<LibraryEntry>> = _library.asStateFlow()

    private val activeDownloads = MutableStateFlow(0)
    private val concurrencyLimit = MutableStateFlow(initialMaxConcurrentDownloads)
    val maxConcurrentDownloads: Int
        get() = concurrencyLimit.value

    private val persistence = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private var nextId = 0
    private var restored = false

    init {
        scope.launch {
            for (operation in persistence) {
                runCatching { operation() }
            }
        }
    }

    /**
     * Loads persisted tasks and library entries. Active tasks from a previous
     * process become [DownloadStatus.INTERRUPTED]; nothing is auto-resumed.
     */
    suspend fun restore() {
        if (restored) return
        restored = true
        val repo = repository ?: return

        runCatching {
            repo.markActiveInterrupted()
            val loadedTasks = repo.loadTasks()
            val loadedLibrary = repo.loadLibrary()
            _tasks.value = loadedTasks
            _library.value = loadedLibrary
            nextId = (loadedTasks.map { it.id } + loadedLibrary.map { it.id })
                .mapNotNull { it.removePrefix(TASK_ID_PREFIX).toIntOrNull() }
                .maxOrNull() ?: 0
        }
    }

    fun setMaxConcurrentDownloads(value: Int) {
        concurrencyLimit.value = value.coerceIn(1, MAX_CONCURRENT)
    }

    fun enqueue(media: MediaInfo, request: DownloadRequest): String {
        val duplicate = _tasks.value.firstOrNull { task ->
            task.status.isActive &&
                task.request.url == request.url &&
                task.request.kind == request.kind &&
                task.request.formatId == request.formatId
        }
        if (duplicate != null) return duplicate.id

        val task = DownloadTask(
            id = nextTaskId(),
            media = media,
            request = request,
        )
        _tasks.update { it + task }
        persist { repository?.insertTask(task) }
        launchDownload(task)
        return task.id
    }

    fun cancel(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!task.status.isActive) return
        if (task.status == DownloadStatus.QUEUED) {
            applyEvent(id, DownloadEvent.Canceled)
            return
        }
        scope.launch { engine.cancel(id) }
    }

    fun addLibraryEntries(entries: List<LibraryEntry>) {
        if (entries.isEmpty()) return
        val ids = entries.map { it.id }.toSet()
        _library.update { current ->
            entries + current.filterNot { it.id in ids }
        }
        entries.forEach { entry ->
            persist { repository?.insertLibraryEntry(entry) }
        }
    }

    fun retry(id: String) {
        val previous = _tasks.value.firstOrNull { it.id == id } ?: return
        if (previous.status.isActive) return

        val task = previous.copy(
            id = nextTaskId(),
            status = DownloadStatus.QUEUED,
            percent = 0.0,
            speedBytesPerSecond = null,
            etaSeconds = null,
            filePath = null,
            sizeBytes = null,
            failure = null,
        )
        _tasks.update { it + task }
        persist { repository?.insertTask(task) }
        launchDownload(task)
    }

    fun dismiss(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!task.status.isFinished) return
        _tasks.update { tasks -> tasks.filterNot { it.id == id } }
        persist { repository?.deleteTask(id) }
    }

    fun clearFinished() {
        val finished = _tasks.value.filter { it.status.isFinished }.map { it.id }
        _tasks.update { tasks -> tasks.filter { it.status.isActive } }
        finished.forEach { id -> persist { repository?.deleteTask(id) } }
    }

    /** Removes the library record only; the file remains on disk. */
    fun removeFromLibrary(id: String) {
        _library.update { entries -> entries.filterNot { it.id == id } }
        persist { repository?.deleteLibraryEntry(id) }
    }

    fun removeFromLibrary(ids: Collection<String>) {
        ids.toList().forEach(::removeFromLibrary)
    }

    fun dismissAll(ids: Collection<String>) {
        ids.toList().forEach(::dismiss)
    }

    private fun launchDownload(task: DownloadTask) {
        scope.launch {
            acquireSlot()
            try {
                val current = _tasks.value.firstOrNull { it.id == task.id }
                if (current?.status != DownloadStatus.QUEUED) return@launch
                engine.download(task.request, task.id).collect { event ->
                    applyEvent(task.id, event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                applyEvent(task.id, DownloadEvent.Failed(DownloadFailure.Unexpected(null)))
            } finally {
                activeDownloads.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    private suspend fun acquireSlot() {
        while (true) {
            val active = activeDownloads.value
            if (
                active < concurrencyLimit.value &&
                activeDownloads.compareAndSet(active, active + 1)
            ) {
                return
            }
            combine(activeDownloads, concurrencyLimit) { running, limit ->
                running < limit
            }.first { it }
        }
    }

    private fun applyEvent(id: String, event: DownloadEvent) {
        val previous = _tasks.value.firstOrNull { it.id == id } ?: return
        _tasks.update { tasks ->
            tasks.map { task -> if (task.id == id) task.apply(event) else task }
        }
        val updated = _tasks.value.firstOrNull { it.id == id } ?: return

        val percentageBucketChanged =
            (updated.percent.toInt() / PROGRESS_BUCKET) != (previous.percent.toInt() / PROGRESS_BUCKET)
        if (event !is DownloadEvent.Progress || percentageBucketChanged) {
            persist { repository?.updateTask(updated) }
        }

        if (event is DownloadEvent.Completed) {
            scope.launch {
                runCatching { completedHandler?.onCompleted(updated) }
            }
            val entry = LibraryEntry(
                id = updated.id,
                title = updated.media.title,
                kind = updated.request.kind,
                filePath = event.filePath,
                sizeBytes = event.sizeBytes,
                thumbnailUrl = updated.media.thumbnailUrl,
            )
            _library.update { entries ->
                listOf(entry) + entries.filterNot { it.id == entry.id }
            }
            persist { repository?.insertLibraryEntry(entry) }
        }
    }

    private fun nextTaskId(): String = "$TASK_ID_PREFIX${++nextId}"

    private fun persist(operation: suspend () -> Unit) {
        persistence.trySend(operation)
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

    private companion object {
        const val DEFAULT_MAX_CONCURRENT = 2
        const val MAX_CONCURRENT = 4
        const val PROGRESS_BUCKET = 10
        const val TASK_ID_PREFIX = "download-"
    }
}
