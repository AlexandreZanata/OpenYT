package com.openyt.core.application

import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadPriority
import com.openyt.core.domain.DownloadWindow
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.ResolvedDownloadPlan
import com.openyt.core.domain.toDownloadRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the in-memory view of the persistent download queue and library.
 *
 * Persistence is optional (useful in tests and previews). Writes are serialized
 * through a channel so a slow database cannot reorder state transitions. Progress
 * is persisted at 10% intervals; terminal states are always persisted.
 *
 * [enqueue] accepts concurrent UI and local scheduler requests atomically.
 */
class DownloadCoordinator(
    private val engine: DownloadEngine,
    private val scope: CoroutineScope,
    private val repository: DownloadRepository? = null,
    initialMaxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT,
    private val completedHandler: CompletedDownloadHandler? = null,
    private val maxAutomaticRetries: Int = DEFAULT_MAX_AUTOMATIC_RETRIES,
    private val retryDelayMillis: (Int) -> Long = ::defaultRetryDelayMillis,
    private val timeOfDayMinutes: () -> Int = { 0 },
    private val cookieRepository: CookieRepository? = null,
    private val assetStore: OfflineAssetStore? = null,
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
    private val scheduleSignal = Channel<Unit>(Channel.CONFLATED)
    private val backoffTasks = MutableStateFlow<Set<String>>(emptySet())
    private val pausingTasks = MutableStateFlow<Set<String>>(emptySet())
    private val speedLimitBytesPerSecond = MutableStateFlow<Long?>(null)
    private val downloadWindow = MutableStateFlow<DownloadWindow?>(null)
    private var windowWakeupJob: Job? = null

    private val nextId = MutableStateFlow(0)
    private val persistenceFailed = MutableStateFlow(false)
    private val pendingInsertions = MutableStateFlow<Set<String>>(emptySet())
    private var restored = false
    private val _ready = MutableStateFlow(repository == null)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        scope.launch {
            for (ignored in scheduleSignal) {
                dispatch()
            }
        }
        scope.launch {
            for (operation in persistence) {
                runCatching { operation() }.onFailure { persistenceFailed.value = true }
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
            val largestId = (loadedTasks.map { it.id } + loadedLibrary.map { it.id })
                .mapNotNull { it.removePrefix(TASK_ID_PREFIX).toIntOrNull() }
                .maxOrNull() ?: 0
            nextId.update { maxOf(it, largestId) }
        }
        _ready.value = true
    }

    suspend fun reloadLibrary() {
        val repo = repository ?: return
        runCatching { _library.value = repo.loadLibrary() }
    }

    fun setMaxConcurrentDownloads(value: Int) {
        concurrencyLimit.value = value.coerceIn(1, MAX_CONCURRENT)
        requestSchedule()
    }

    fun setSpeedLimit(bytesPerSecond: Long?) {
        speedLimitBytesPerSecond.value = bytesPerSecond?.takeIf { it > 0 }
    }

    fun setDownloadWindow(window: DownloadWindow?) {
        downloadWindow.value = window
        windowWakeupJob?.cancel()
        windowWakeupJob = null
        requestSchedule()
    }

    fun enqueue(
        media: MediaInfo,
        request: DownloadRequest,
        priority: DownloadPriority = DownloadPriority.NORMAL,
    ): String {
        while (true) {
            val current = _tasks.value
            val duplicate = current.firstOrNull { task ->
                if (request.options.originKey != null) {
                    task.request.options.originKey == request.options.originKey
                } else {
                    task.status.isActive && task.request.url == request.url &&
                        task.request.kind == request.kind && task.request.formatId == request.formatId &&
                        task.request.playlistItemIndex == request.playlistItemIndex && task.request.options == request.options
                }
            }
            if (duplicate != null) return duplicate.id
            val task = DownloadTask(id = nextTaskId(), media = media, request = request, priority = priority)
            pendingInsertions.update { it + task.id }
            if (_tasks.compareAndSet(current, current + task)) {
                persist { repository?.insertTask(task) }
                pendingInsertions.update { it - task.id }
                requestSchedule()
                return task.id
            }
            pendingInsertions.update { it - task.id }
        }
    }

    fun enqueuePlan(
        media: MediaInfo,
        plan: ResolvedDownloadPlan,
        destinationDirectory: String,
        fileNameTemplate: String? = null,
        exportTargetUri: String? = null,
        priority: DownloadPriority = DownloadPriority.NORMAL,
    ): String {
        val request = plan.toDownloadRequest(
            url = media.webpageUrl,
            destinationDirectory = destinationDirectory,
            fileNameTemplate = fileNameTemplate ?: "%(title)s.%(ext)s",
            exportTargetUri = exportTargetUri,
        )
        return enqueue(media = media, request = request, priority = priority)
    }

    suspend fun awaitPersistence(): Boolean {
        val barrier = CompletableDeferred<Unit>()
        persistence.send { barrier.complete(Unit) }
        barrier.await()
        return !persistenceFailed.value
    }

    fun cancel(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status == DownloadStatus.PAUSED) {
            applyEvent(id, DownloadEvent.Canceled)
            return
        }
        if (!task.status.isActive) return
        if (task.status == DownloadStatus.QUEUED) {
            applyEvent(id, DownloadEvent.Canceled)
            return
        }
        scope.launch { engine.cancel(id) }
    }

    fun pause(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        when (task.status) {
            DownloadStatus.QUEUED -> {
                val paused = task.copy(status = DownloadStatus.PAUSED)
                _tasks.update { tasks -> tasks.map { if (it.id == id) paused else it } }
                persist { repository?.updateTask(paused) }
                requestSchedule()
            }

            DownloadStatus.DOWNLOADING,
            DownloadStatus.PROCESSING,
            -> {
                pausingTasks.update { it + id }
                scope.launch { engine.pause(id) }
            }

            else -> Unit
        }
    }

    fun resume(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status != DownloadStatus.PAUSED) return
        val resumed = task.copy(
            status = DownloadStatus.QUEUED,
            percent = 0.0,
            speedBytesPerSecond = null,
            etaSeconds = null,
            failure = null,
        )
        _tasks.update { tasks -> tasks.map { if (it.id == id) resumed else it } }
        persist { repository?.updateTask(resumed) }
        requestSchedule()
    }

    fun setPriority(id: String, priority: DownloadPriority) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status != DownloadStatus.QUEUED && task.status != DownloadStatus.DOWNLOADING) {
            return
        }
        val updated = task.copy(priority = priority)
        _tasks.update { tasks -> tasks.map { if (it.id == id) updated else it } }
        persist { repository?.updateTask(updated) }
        requestSchedule()
    }

    fun resumeInterrupted() {
        _tasks.value
            .filter { it.status == DownloadStatus.INTERRUPTED }
            .forEach { task ->
                val resumed = task.copy(
                    status = DownloadStatus.QUEUED,
                    percent = 0.0,
                    speedBytesPerSecond = null,
                    etaSeconds = null,
                    failure = null,
                    attempt = 0,
                )
                _tasks.update { tasks -> tasks.map { if (it.id == task.id) resumed else it } }
                persist { repository?.updateTask(resumed) }
            }
        requestSchedule()
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
            attempt = 0,
        )
        _tasks.update { it + task }
        persist { repository?.insertTask(task) }
        requestSchedule()
    }

    fun dismiss(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!task.status.isFinished) return
        _tasks.update { tasks -> tasks.filterNot { it.id == id } }
        persist { repository?.deleteTask(id) }
    }

    fun clearFinished() {
        val finished = _tasks.value.filter { it.status.isFinished }.map { it.id }.toSet()
        _tasks.update { tasks -> tasks.filterNot { it.id in finished } }
        finished.forEach { id -> persist { repository?.deleteTask(id) } }
    }

    fun updateLibraryMetadata(id: String, title: String, author: String?) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val entry = _library.value.firstOrNull { it.id == id } ?: return
        val updated = entry.copy(
            title = trimmed,
            author = author?.trim()?.takeIf { it.isNotBlank() },
        )
        _library.update { entries -> entries.map { if (it.id == id) updated else it } }
        persist { repository?.updateLibraryMetadata(id, updated.title, updated.author) }
    }

    fun addLibraryTag(id: String, tag: String) {
        val normalized = normalizeTag(tag) ?: return
        val entry = _library.value.firstOrNull { it.id == id } ?: return
        if (entry.tags.any { it.equals(normalized, ignoreCase = true) }) return
        val updated = entry.copy(tags = entry.tags + normalized)
        _library.update { entries -> entries.map { if (it.id == id) updated else it } }
        persist { repository?.addLibraryTag(id, normalized) }
    }

    fun removeLibraryTag(id: String, tag: String) {
        val entry = _library.value.firstOrNull { it.id == id } ?: return
        val updated = entry.copy(
            tags = entry.tags.filterNot { it.equals(tag, ignoreCase = true) },
        )
        _library.update { entries -> entries.map { if (it.id == id) updated else it } }
        persist { repository?.removeLibraryTag(id, tag) }
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

    private fun requestSchedule() {
        scheduleSignal.trySend(Unit)
    }

    private fun dispatch() {
        while (activeDownloads.value < concurrencyLimit.value) {
            val next = nextEligibleQueuedTask() ?: return
            val window = downloadWindow.value
            if (window != null && !window.allows(timeOfDayMinutes())) {
                scheduleWindowWakeup(window)
                return
            }
            val started = next.copy(status = DownloadStatus.DOWNLOADING)
            _tasks.update { tasks -> tasks.map { if (it.id == started.id) started else it } }
            persist { repository?.updateTask(started) }
            activeDownloads.update { it + 1 }
            startTask(started)
        }
    }

    private fun scheduleWindowWakeup(window: DownloadWindow) {
        if (windowWakeupJob?.isActive == true) return
        val now = timeOfDayMinutes()
        if (window.allows(now)) return
        val minutesUntilStart =
            ((window.startMinutes - now) % DownloadWindow.MINUTES_PER_DAY +
                DownloadWindow.MINUTES_PER_DAY) % DownloadWindow.MINUTES_PER_DAY
        val delayMinutes = minutesUntilStart.takeIf { it > 0 } ?: DownloadWindow.MINUTES_PER_DAY
        windowWakeupJob = scope.launch {
            delay(delayMinutes * 60_000L)
            requestSchedule()
        }
    }

    private fun nextEligibleQueuedTask(): DownloadTask? =
        _tasks.value.withIndex()
            .filter { it.value.status == DownloadStatus.QUEUED && it.value.id !in backoffTasks.value && it.value.id !in pendingInsertions.value }
            .minWithOrNull(
                compareByDescending<IndexedValue<DownloadTask>> { it.value.priority.weight }
                    .thenBy { it.index },
            )
            ?.value

    private fun startTask(task: DownloadTask) {
        scope.launch {
            var cookiesPath: String? = null
            try {
                if (task.request.cookiesFilePath == null) {
                    cookiesPath = runCatching { cookieRepository?.createTemporaryFile() }.getOrNull()
                }
                val request = task.request.copy(
                    limitBytesPerSecond = speedLimitBytesPerSecond.value,
                    cookiesFilePath = task.request.cookiesFilePath ?: cookiesPath,
                )
                engine.download(request, task.id).collect { event ->
                    applyEvent(task.id, event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                applyEvent(task.id, DownloadEvent.Failed(DownloadFailure.Unexpected(null)))
            } finally {
                cookiesPath?.let { path ->
                    runCatching { cookieRepository?.deleteTemporaryFile(path) }
                }
                activeDownloads.update { (it - 1).coerceAtLeast(0) }
                requestSchedule()
            }
        }
    }

    private fun applyEvent(id: String, event: DownloadEvent) {
        val previous = _tasks.value.firstOrNull { it.id == id } ?: return
        val pausing = event == DownloadEvent.Canceled && id in pausingTasks.value
        if (event == DownloadEvent.Paused || event == DownloadEvent.Canceled ||
            event is DownloadEvent.Completed || event is DownloadEvent.Failed
        ) {
            pausingTasks.update { it - id }
        }
        val applied = if (pausing) DownloadEvent.Paused else event
        val updated = previous.apply(applied)

        val percentageBucketChanged =
            (updated.percent.toInt() / PROGRESS_BUCKET) != (previous.percent.toInt() / PROGRESS_BUCKET)
        if (applied !is DownloadEvent.Progress || percentageBucketChanged) {
            persist { repository?.updateTask(updated) }
        }

        if (applied is DownloadEvent.Failed && shouldRetry(updated)) {
            val retry = updated.copy(
                status = DownloadStatus.QUEUED,
                percent = 0.0,
                speedBytesPerSecond = null,
                etaSeconds = null,
                failure = null,
                attempt = updated.attempt + 1,
            )
            backoffTasks.update { it + retry.id }
            persist { repository?.updateTask(retry) }
            _tasks.update { tasks -> tasks.map { if (it.id == retry.id) retry else it } }
            scope.launch {
                delay(retryDelayMillis(retry.attempt))
                backoffTasks.update { it - retry.id }
                requestSchedule()
            }
            return
        }

        if (applied is DownloadEvent.Completed) {
            val entry = LibraryEntry(
                id = updated.id,
                title = updated.media.title,
                author = updated.media.author,
                kind = updated.request.kind,
                filePath = applied.filePath,
                sizeBytes = applied.sizeBytes,
                thumbnailUrl = updated.media.thumbnailUrl,
                source = updated.media.source,
            )
            _library.update { entries ->
                listOf(entry) + entries.filterNot { it.id == entry.id }
            }
            persist { repository?.insertLibraryEntry(entry) }

            if (applied.artifacts.isNotEmpty() && assetStore != null) {
                val mediaRevision = com.openyt.core.domain.MediaRevision(
                    sizeBytes = applied.sizeBytes ?: 0L,
                    lastModifiedEpochMillis = 0L,
                )
                val allowedRoots = listOf(updated.request.destinationDirectory)
                for (artifact in applied.artifacts) {
                    scope.launch {
                        runCatching {
                            assetStore.storeConfirmedArtifact(
                                entryId = entry.id,
                                receipt = artifact,
                                mediaRevision = mediaRevision,
                                allowedRoots = allowedRoots,
                            )
                        }
                    }
                }
            }
        }

        // Observers may flush persistence and exit immediately on a terminal state.
        // Queue every associated write before making that state observable.
        _tasks.update { tasks -> tasks.map { if (it.id == id) updated else it } }
        if (applied is DownloadEvent.Completed) {
            scope.launch { runCatching { completedHandler?.onCompleted(updated) } }
        }
    }

    private fun shouldRetry(task: DownloadTask): Boolean =
        task.attempt < maxAutomaticRetries &&
            when (task.failure) {
                is DownloadFailure.Network,
                is DownloadFailure.Unavailable,
                is DownloadFailure.Unexpected,
                -> true

                else -> false
            }

    private fun normalizeTag(value: String): String? =
        value.trim()
            .filterNot { it.isISOControl() }
            .take(MAX_TAG_LENGTH)
            .takeIf { it.isNotBlank() }

    private fun nextTaskId(): String = "$TASK_ID_PREFIX${nextId.updateAndGet { it + 1 }}"

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

        DownloadEvent.Paused -> copy(status = DownloadStatus.PAUSED)
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT = 2
        const val MAX_CONCURRENT = 4
        const val PROGRESS_BUCKET = 10
        const val TASK_ID_PREFIX = "download-"
        const val DEFAULT_MAX_AUTOMATIC_RETRIES = 3
        const val MAX_TAG_LENGTH = 40

        fun defaultRetryDelayMillis(attempt: Int): Long =
            (2_000L shl (attempt - 1)).coerceAtMost(30_000L)
    }
}
