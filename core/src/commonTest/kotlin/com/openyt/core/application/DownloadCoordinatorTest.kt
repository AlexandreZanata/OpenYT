package com.openyt.core.application

import com.openyt.core.domain.Collection
import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadPriority
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadWindow
import com.openyt.core.application.CookieRepository
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.DownloadTask
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.LibraryEntry
import com.openyt.core.domain.MediaInfo
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.SmartCollection
import com.openyt.core.testing.FakeDownloadEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadCoordinatorTest {
    @Test
    fun marksTaskCompletedAfterEngineFinishes() = runTest {
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope())

        val id = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        advanceUntilIdle()

        val task = coordinator.tasks.value.single()
        assertEquals(id, task.id)
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(100.0, task.percent)
        assertEquals(1024, task.sizeBytes)
        assertTrue(assertNotNull(task.filePath).endsWith(".bin"))
    }

    @Test
    fun exposesProgressWhileDownloading() = runTest {
        val engine = SuspendingEngine()
        val coordinator = DownloadCoordinator(engine, coordinatorScope())

        coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        advanceUntilIdle()

        val active = coordinator.tasks.value.single()
        assertEquals(DownloadStatus.DOWNLOADING, active.status)
        assertEquals(12.5, active.percent)
        assertFalse(active.status.isFinished)
    }

    @Test
    fun cancelRequestsEngineCancellationAndFinishesTask() = runTest {
        val engine = SuspendingEngine()
        val coordinator = DownloadCoordinator(engine, coordinatorScope())

        val id = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        advanceUntilIdle()

        coordinator.cancel(id)
        advanceUntilIdle()

        assertEquals(DownloadStatus.CANCELED, coordinator.tasks.value.single().status)
        assertTrue(engine.cancelCalls > 0)
    }

    @Test
    fun propagatesStructuredFailure() = runTest {
        val engine = ImmediateEngine(
            listOf(DownloadEvent.Starting, DownloadEvent.Failed(DownloadFailure.Network)),
        )
        val coordinator = DownloadCoordinator(engine, coordinatorScope())

        coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        advanceUntilIdle()

        val task = coordinator.tasks.value.single()
        assertEquals(DownloadStatus.FAILED, task.status)
        assertEquals(DownloadFailure.Network, task.failure)
    }

    @Test
    fun dismissOnlyRemovesFinishedTasks() = runTest {
        val engine = SuspendingEngine()
        val coordinator = DownloadCoordinator(engine, coordinatorScope())

        val id = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        advanceUntilIdle()

        coordinator.dismiss(id)
        assertEquals(1, coordinator.tasks.value.size)

        coordinator.cancel(id)
        advanceUntilIdle()
        coordinator.dismiss(id)
        assertTrue(coordinator.tasks.value.isEmpty())
    }

    @Test
    fun enqueueAssignsUniqueIds() = runTest {
        val coordinator = DownloadCoordinator(ImmediateEngine(listOf(DownloadEvent.Starting)), coordinatorScope())

        val first = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        val second = coordinator.enqueue(
            FakeDownloadEngine.SAMPLE_MEDIA,
            request().copy(url = "https://example.com/watch?v=2"),
        )
        advanceUntilIdle()

        assertTrue(first != second)
        assertEquals(2, coordinator.tasks.value.size)
    }

    @Test
    fun updatesLibraryMetadataWithoutRenamingTheFile() = runTest {
        val repository = FakeRepository()
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repository)
        coordinator.addLibraryEntries(
            listOf(
                LibraryEntry(
                    id = "id-1",
                    title = "Old title",
                    kind = MediaKind.AUDIO,
                    filePath = "/tmp/keep-name.m4a",
                    sizeBytes = 1,
                ),
            ),
        )
        advanceUntilIdle()

        coordinator.updateLibraryMetadata("id-1", "New title", "New author")
        advanceUntilIdle()

        val entry = coordinator.library.value.single()
        assertEquals("New title", entry.title)
        assertEquals("New author", entry.author)
        assertEquals("/tmp/keep-name.m4a", entry.filePath)
    }

    @Test
    fun persistsCompletionAndAddsLibraryEntry() = runTest {
        val repository = FakeRepository()
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repository)

        coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        advanceUntilIdle()

        assertEquals(DownloadStatus.COMPLETED, repository.tasks.single().status)
        assertEquals(1, repository.library.size)
        assertEquals(FakeDownloadEngine.SAMPLE_MEDIA.title, repository.library.single().title)
    }

    @Test
    fun restoresPersistedTasksAndMarksActiveAsInterrupted() = runTest {
        val repository = FakeRepository(
            tasks = mutableListOf(
                persistedTask("download-1", DownloadStatus.DOWNLOADING),
                persistedTask("download-2", DownloadStatus.COMPLETED),
            ),
        )
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repository)

        coordinator.restore()

        val byId = coordinator.tasks.value.associateBy { it.id }
        assertEquals(DownloadStatus.INTERRUPTED, byId.getValue("download-1").status)
        assertEquals(DownloadStatus.COMPLETED, byId.getValue("download-2").status)
    }

    @Test
    fun invokesCompletedHandlerWithFinalPath() = runTest {
        var completedPath: String? = null
        val coordinator = DownloadCoordinator(
            engine = FakeDownloadEngine(),
            scope = coordinatorScope(),
            completedHandler = CompletedDownloadHandler { task -> completedPath = task.filePath },
        )

        coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        advanceUntilIdle()

        assertEquals("/tmp/openyt/download-1.bin", completedPath)
    }

    @Test
    fun addsImportedEntriesToLibraryAndPersists() = runTest {
        val repository = FakeRepository()
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repository)
        val imported = LibraryEntry(
            id = "import-1",
            title = "Imported",
            kind = com.openyt.core.domain.MediaKind.VIDEO,
            filePath = "/tmp/imported.mp4",
            sizeBytes = 10,
        )

        coordinator.addLibraryEntries(listOf(imported))
        advanceUntilIdle()

        assertEquals(1, coordinator.library.value.size)
        assertEquals("Imported", coordinator.library.value.single().title)
        assertEquals(1, repository.library.size)
    }

    @Test
    fun doesNotReuseIdsReferencedByLibraryEntries() = runTest {
        val repository = FakeRepository(
            library = mutableListOf(
                LibraryEntry("download-5", "Old media", com.openyt.core.domain.MediaKind.VIDEO, "/tmp/old", 1),
            ),
        )
        val coordinator = DownloadCoordinator(
            ImmediateEngine(listOf(DownloadEvent.Starting)),
            coordinatorScope(),
            repository,
        )
        coordinator.restore()

        val id = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())

        assertEquals("download-6", id)
    }

    @Test
    fun deduplicatesActiveRequestsWithSameTarget() = runTest {
        val coordinator = DownloadCoordinator(SuspendingEngine(), coordinatorScope())

        val first = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        val second = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        advanceUntilIdle()

        assertEquals(first, second)
        assertEquals(1, coordinator.tasks.value.size)
    }

    @Test
    fun retryCreatesNewTaskForFinishedTask() = runTest {
        val repository = FakeRepository(
            tasks = mutableListOf(persistedTask("download-1", DownloadStatus.FAILED)),
        )
        val coordinator = DownloadCoordinator(SuspendingEngine(), coordinatorScope(), repository)
        coordinator.restore()

        coordinator.retry("download-1")
        advanceUntilIdle()

        assertEquals(2, coordinator.tasks.value.size)
        val retried = coordinator.tasks.value.last()
        assertEquals(DownloadStatus.DOWNLOADING, retried.status)
        assertEquals(2, repository.tasks.size)
    }

    @Test
    fun removingFromLibraryKeepsTaskRecord() = runTest {
        val repository = FakeRepository(
            tasks = mutableListOf(persistedTask("download-1", DownloadStatus.COMPLETED)),
            library = mutableListOf(
                LibraryEntry("download-1", "Sample media", com.openyt.core.domain.MediaKind.VIDEO, "/tmp/a", 1),
            ),
        )
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repository)
        coordinator.restore()

        coordinator.removeFromLibrary("download-1")
        advanceUntilIdle()

        assertTrue(coordinator.library.value.isEmpty())
        assertTrue(repository.library.isEmpty())
        assertEquals(1, repository.tasks.size)
    }

    @Test
    fun dismissRemovesTaskButKeepsLibraryEntry() = runTest {
        val repository = FakeRepository()
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repository)
        val id = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        advanceUntilIdle()

        coordinator.dismiss(id)
        advanceUntilIdle()

        assertTrue(coordinator.tasks.value.isEmpty())
        assertTrue(repository.tasks.isEmpty())
        assertEquals(1, repository.library.size)
    }

    @Test
    fun respectsMaxConcurrentDownloads() = runTest {
        val engine = GatedEngine()
        val coordinator = DownloadCoordinator(
            engine = engine,
            scope = coordinatorScope(),
            initialMaxConcurrentDownloads = 1,
        )

        val first = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        val second = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request().copy(url = "https://example.com/other"))
        advanceUntilIdle()

        val byId = coordinator.tasks.value.associateBy { it.id }
        assertEquals(DownloadStatus.DOWNLOADING, byId.getValue(first).status)
        assertEquals(DownloadStatus.QUEUED, byId.getValue(second).status)

        engine.release(first)
        advanceUntilIdle()

        val after = coordinator.tasks.value.associateBy { it.id }
        assertEquals(DownloadStatus.COMPLETED, after.getValue(first).status)
        assertEquals(DownloadStatus.DOWNLOADING, after.getValue(second).status)
    }

    @Test
    fun cancellingQueuedDownloadDoesNotStartIt() = runTest {
        val engine = GatedEngine()
        val coordinator = DownloadCoordinator(
            engine = engine,
            scope = coordinatorScope(),
            initialMaxConcurrentDownloads = 1,
        )

        val first = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        val second = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request().copy(url = "https://example.com/other"))
        advanceUntilIdle()
        assertEquals(
            DownloadStatus.QUEUED,
            coordinator.tasks.value.associateBy { it.id }.getValue(second).status,
        )

        coordinator.cancel(second)
        engine.release(first)
        advanceUntilIdle()

        val after = coordinator.tasks.value.associateBy { it.id }
        assertEquals(DownloadStatus.COMPLETED, after.getValue(first).status)
        assertEquals(DownloadStatus.CANCELED, after.getValue(second).status)
    }

    @Test
    fun increasingConcurrencyStartsQueuedDownloads() = runTest {
        val engine = GatedEngine()
        val coordinator = DownloadCoordinator(
            engine = engine,
            scope = coordinatorScope(),
            initialMaxConcurrentDownloads = 1,
        )

        val first = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        val second = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request().copy(url = "https://example.com/other"))
        advanceUntilIdle()
        assertEquals(
            DownloadStatus.QUEUED,
            coordinator.tasks.value.associateBy { it.id }.getValue(second).status,
        )

        coordinator.setMaxConcurrentDownloads(2)
        advanceUntilIdle()

        val after = coordinator.tasks.value.associateBy { it.id }
        assertEquals(DownloadStatus.DOWNLOADING, after.getValue(first).status)
        assertEquals(DownloadStatus.DOWNLOADING, after.getValue(second).status)
    }


    @Test
    fun startsHigherPriorityQueuedTaskFirst() = runTest {
        val engine = OrderingEngine()
        val coordinator = DownloadCoordinator(
            engine,
            coordinatorScope(),
            initialMaxConcurrentDownloads = 1,
        )

        val low = coordinator.enqueue(media("low"), request("low"), DownloadPriority.LOW)
        advanceUntilIdle()
        val normal = coordinator.enqueue(media("normal"), request("normal"), DownloadPriority.NORMAL)
        val high = coordinator.enqueue(media("high"), request("high"), DownloadPriority.HIGH)
        advanceUntilIdle()

        assertEquals(listOf(low), engine.started)
        engine.release(low)
        advanceUntilIdle()
        assertEquals(listOf(low, high), engine.started)

        engine.release(high)
        advanceUntilIdle()
        assertEquals(listOf(low, high, normal), engine.started)
    }

    @Test
    fun setPriorityReordersQueuedTask() = runTest {
        val engine = OrderingEngine()
        val coordinator = DownloadCoordinator(
            engine,
            coordinatorScope(),
            initialMaxConcurrentDownloads = 1,
        )

        val first = coordinator.enqueue(media("first"), request("first"))
        advanceUntilIdle()
        val second = coordinator.enqueue(media("second"), request("second"))
        val third = coordinator.enqueue(media("third"), request("third"))
        coordinator.setPriority(third, DownloadPriority.HIGH)
        advanceUntilIdle()

        engine.release(first)
        advanceUntilIdle()
        assertEquals(listOf(first, third), engine.started)

        engine.release(third)
        advanceUntilIdle()
        assertEquals(listOf(first, third, second), engine.started)
    }

    @Test
    fun retriesTransientFailureWithBackoff() = runTest {
        val engine = FlakyEngine(failures = 1)
        val coordinator = DownloadCoordinator(
            engine,
            coordinatorScope(),
            maxAutomaticRetries = 2,
            retryDelayMillis = { 100L },
        )

        coordinator.enqueue(media("retry"), request("retry"))
        advanceUntilIdle()

        val task = coordinator.tasks.value.single()
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertEquals(1, task.attempt)
        assertEquals(2, engine.calls)
    }

    @Test
    fun resumesInterruptedTasks() = runTest {
        val repository = FakeRepository(
            tasks = mutableListOf(persistedTask("download-7", DownloadStatus.INTERRUPTED)),
        )
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repository)
        coordinator.restore()

        coordinator.resumeInterrupted()
        advanceUntilIdle()

        assertEquals(DownloadStatus.COMPLETED, coordinator.tasks.value.single().status)
    }


    @Test
    fun pausesQueuedTaskWithoutStartingIt() = runTest {
        val engine = OrderingEngine()
        val coordinator = DownloadCoordinator(
            engine,
            coordinatorScope(),
            initialMaxConcurrentDownloads = 1,
        )

        val first = coordinator.enqueue(media("first"), request("first"))
        advanceUntilIdle()
        val second = coordinator.enqueue(media("second"), request("second"))
        coordinator.pause(second)
        advanceUntilIdle()

        assertEquals(DownloadStatus.PAUSED, coordinator.tasks.value.associateBy { it.id }.getValue(second).status)
        engine.release(first)
        advanceUntilIdle()
        assertEquals(listOf(first), engine.started)
    }

    @Test
    fun pausesActiveTaskAndResumesIt() = runTest {
        val engine = PausingEngine()
        val coordinator = DownloadCoordinator(engine, coordinatorScope())

        val id = coordinator.enqueue(media("pause"), request("pause"))
        advanceUntilIdle()
        assertEquals(DownloadStatus.DOWNLOADING, coordinator.tasks.value.single().status)

        coordinator.pause(id)
        advanceUntilIdle()
        assertEquals(DownloadStatus.PAUSED, coordinator.tasks.value.single().status)

        coordinator.resume(id)
        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, coordinator.tasks.value.single().status)
        assertEquals(2, engine.calls)
    }

    @Test
    fun restoresPausedTasksAndResumesThem() = runTest {
        val repository = FakeRepository(
            tasks = mutableListOf(persistedTask("download-7", DownloadStatus.PAUSED)),
        )
        val coordinator = DownloadCoordinator(FakeDownloadEngine(), coordinatorScope(), repository)
        coordinator.restore()

        assertEquals(DownloadStatus.PAUSED, coordinator.tasks.value.single().status)

        coordinator.resume("download-7")
        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, coordinator.tasks.value.single().status)
    }


    @Test
    fun appliesSpeedLimitToEngineRequest() = runTest {
        val engine = LimitRecordingEngine()
        val coordinator = DownloadCoordinator(engine, coordinatorScope())
        coordinator.setSpeedLimit(2_000_000L)

        coordinator.enqueue(media("limit"), request("limit"))
        advanceUntilIdle()

        assertEquals(2_000_000L, engine.limitBytesPerSecond)
    }

    @Test
    fun passesCookiesToEngineAndDeletesTemporaryFile() = runTest {
        val engine = LimitRecordingEngine()
        val cookies = FakeCookieRepository()
        val coordinator = DownloadCoordinator(engine, coordinatorScope(), cookieRepository = cookies)

        coordinator.enqueue(media("cookies"), request("cookies"))
        advanceUntilIdle()

        assertEquals("/tmp/openyt-fake-cookies.txt", engine.cookiesFilePath)
        assertEquals(listOf("/tmp/openyt-fake-cookies.txt"), cookies.deleted)
    }

    @Test
    fun defersQueuedTasksOutsideDownloadWindow() = runTest {
        var now = 12 * 60
        val coordinator = DownloadCoordinator(
            FakeDownloadEngine(),
            coordinatorScope(),
            timeOfDayMinutes = { now },
        )
        coordinator.setDownloadWindow(DownloadWindow(22 * 60, 6 * 60))

        coordinator.enqueue(media("window"), request("window"))
        advanceUntilIdle()
        assertEquals(DownloadStatus.QUEUED, coordinator.tasks.value.single().status)

        coordinator.setDownloadWindow(null)
        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, coordinator.tasks.value.single().status)
    }

    @Test
    fun wakesUpWhenDownloadWindowOpens() = runTest {
        var now = 12 * 60
        val coordinator = DownloadCoordinator(
            FakeDownloadEngine(),
            coordinatorScope(),
            timeOfDayMinutes = { now },
        )
        coordinator.setDownloadWindow(DownloadWindow(12 * 60 + 1, 13 * 60))

        coordinator.enqueue(media("wakeup"), request("wakeup"))
        runCurrent()
        assertEquals(DownloadStatus.QUEUED, coordinator.tasks.value.single().status)

        now = 12 * 60 + 1
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(DownloadStatus.COMPLETED, coordinator.tasks.value.single().status)
    }

    private fun TestScope.coordinatorScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

    private fun persistedTask(id: String, status: DownloadStatus) = DownloadTask(
        id = id,
        media = FakeDownloadEngine.SAMPLE_MEDIA,
        request = request(),
        status = status,
    )

    private fun request(url: String = "https://example.com/watch?v=1") = DownloadRequest(
        url = url,
        destinationDirectory = "/tmp/openyt",
    )

    private fun media(id: String) = FakeDownloadEngine.SAMPLE_MEDIA.copy(
        sourceId = id,
        title = id,
        webpageUrl = "https://example.com/$id",
    )

    private class ImmediateEngine(private val events: List<DownloadEvent>) : DownloadEngine {
        override suspend fun inspect(url: String): EngineResult<Inspection> =
            EngineResult.Failure(DownloadFailure.UnsupportedSource)

        override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> =
            flow { events.forEach { emit(it) } }

        override suspend fun cancel(downloadId: String) = Unit

        override suspend fun capabilities(): EngineCapabilities =
            EngineCapabilities("test", "0", supportsPause = false, supportsSubtitles = false)

        override fun close() = Unit
    }

    private class FakeRepository(
        var tasks: MutableList<DownloadTask> = mutableListOf(),
        var library: MutableList<LibraryEntry> = mutableListOf(),
    ) : DownloadRepository {
        override suspend fun loadTasks(): List<DownloadTask> = tasks.toList()

        override suspend fun insertTask(task: DownloadTask) {
            tasks.removeAll { it.id == task.id }
            tasks += task
        }

        override suspend fun updateTask(task: DownloadTask) = insertTask(task)

        override suspend fun deleteTask(id: String) {
            tasks.removeAll { it.id == id }
        }

        override suspend fun markActiveInterrupted() {
            tasks = tasks.map { task ->
                if (task.status.isActive) task.copy(status = DownloadStatus.INTERRUPTED) else task
            }.toMutableList()
        }

        override suspend fun loadLibrary(): List<LibraryEntry> = library.toList()

        override suspend fun insertLibraryEntry(entry: LibraryEntry) {
            library.removeAll { it.id == entry.id }
            library += entry
        }

        override suspend fun deleteLibraryEntry(id: String) {
            library.removeAll { it.id == id }
        }

        override suspend fun updateLibraryPath(id: String, filePath: String) {
            library.replaceAll { entry ->
                if (entry.id == id) entry.copy(filePath = filePath) else entry
            }
        }

        override suspend fun updateLibraryMetadata(id: String, title: String, author: String?) {
            library.replaceAll { entry ->
                if (entry.id == id) entry.copy(title = title, author = author) else entry
            }
        }
        override suspend fun addLibraryTag(entryId: String, tag: String) = Unit

        override suspend fun removeLibraryTag(entryId: String, tag: String) = Unit

        override suspend fun loadCollections(): List<Collection> = emptyList()

        override suspend fun insertCollection(collection: Collection) = Unit

        override suspend fun deleteCollection(id: String) = Unit

        override suspend fun loadCollectionMembership(): Map<String, Set<String>> = emptyMap()

        override suspend fun addEntryToCollection(collectionId: String, entryId: String) = Unit

        override suspend fun removeEntryFromCollection(collectionId: String, entryId: String) = Unit
        override suspend fun loadSmartCollections(): List<SmartCollection> = emptyList()
        override suspend fun insertSmartCollection(collection: SmartCollection) = Unit
        override suspend fun deleteSmartCollection(id: String) = Unit
    }

    private class GatedEngine : DownloadEngine {
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        override suspend fun inspect(url: String): EngineResult<Inspection> =
            EngineResult.Failure(DownloadFailure.UnsupportedSource)

        override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = flow {
            emit(DownloadEvent.Starting)
            gates.getOrPut(downloadId) { CompletableDeferred() }.await()
            emit(DownloadEvent.Completed("/tmp/$downloadId.bin", 1))
        }

        fun release(downloadId: String) {
            gates.getOrPut(downloadId) { CompletableDeferred() }.complete(Unit)
        }

        override suspend fun cancel(downloadId: String) = Unit

        override suspend fun capabilities(): EngineCapabilities =
            EngineCapabilities("test", "0", supportsPause = false, supportsSubtitles = false)

        override fun close() = Unit
    }

    private class OrderingEngine : DownloadEngine {
        val started: MutableList<String> = mutableListOf()
        private val releases = mutableMapOf<String, CompletableDeferred<Unit>>()

        override suspend fun inspect(url: String): EngineResult<Inspection> =
            EngineResult.Failure(DownloadFailure.UnsupportedSource)

        override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = flow {
            started += downloadId
            emit(DownloadEvent.Starting)
            emit(DownloadEvent.Progress(10.0))
            releases.getOrPut(downloadId) { CompletableDeferred() }.await()
            emit(DownloadEvent.Completed("/tmp/$downloadId.bin", 1))
        }

        fun release(downloadId: String) {
            releases.getOrPut(downloadId) { CompletableDeferred() }.complete(Unit)
        }

        override suspend fun cancel(downloadId: String) = Unit

        override suspend fun capabilities(): EngineCapabilities =
            EngineCapabilities("test", "0", supportsPause = false, supportsSubtitles = false)

        override fun close() = Unit
    }

    private class LimitRecordingEngine : DownloadEngine {
        var limitBytesPerSecond: Long? = null
        var cookiesFilePath: String? = null

        override suspend fun inspect(url: String): EngineResult<Inspection> =
            EngineResult.Failure(DownloadFailure.UnsupportedSource)

        override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = flow {
            limitBytesPerSecond = request.limitBytesPerSecond
            cookiesFilePath = request.cookiesFilePath
            emit(DownloadEvent.Starting)
            emit(DownloadEvent.Completed("/tmp/$downloadId.bin", 1))
        }

        override suspend fun cancel(downloadId: String) = Unit

        override suspend fun capabilities(): EngineCapabilities =
            EngineCapabilities("test", "0", supportsPause = false, supportsSubtitles = false)

        override fun close() = Unit
    }

    private class FakeCookieRepository : CookieRepository {
        val deleted = mutableListOf<String>()
        private var configured = true

        override suspend fun isConfigured(): Boolean = configured

        override suspend fun importCookies(content: String) {
            configured = true
        }

        override suspend fun clear() {
            configured = false
        }

        override suspend fun createTemporaryFile(): String? =
            if (configured) "/tmp/openyt-fake-cookies.txt" else null

        override suspend fun deleteTemporaryFile(path: String) {
            deleted += path
        }
    }

    private class PausingEngine : DownloadEngine {
        var calls: Int = 0
            private set
        private val pauseGate = CompletableDeferred<Unit>()

        override suspend fun inspect(url: String): EngineResult<Inspection> =
            EngineResult.Failure(DownloadFailure.UnsupportedSource)

        override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = flow {
            calls += 1
            emit(DownloadEvent.Starting)
            emit(DownloadEvent.Progress(20.0))
            if (calls == 1) {
                pauseGate.await()
                emit(DownloadEvent.Canceled)
            } else {
                emit(DownloadEvent.Completed("/tmp/$downloadId.bin", 1))
            }
        }

        override suspend fun pause(downloadId: String) {
            pauseGate.complete(Unit)
        }

        override suspend fun cancel(downloadId: String) {
            pauseGate.complete(Unit)
        }

        override suspend fun capabilities(): EngineCapabilities =
            EngineCapabilities("test", "0", supportsPause = true, supportsSubtitles = false)

        override fun close() = Unit
    }

    private class FlakyEngine(private val failures: Int) : DownloadEngine {
        var calls: Int = 0
            private set

        override suspend fun inspect(url: String): EngineResult<Inspection> =
            EngineResult.Failure(DownloadFailure.UnsupportedSource)

        override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = flow {
            calls += 1
            emit(DownloadEvent.Starting)
            if (calls <= failures) {
                emit(DownloadEvent.Failed(DownloadFailure.Network))
            } else {
                emit(DownloadEvent.Completed("/tmp/$downloadId.bin", 1))
            }
        }

        override suspend fun cancel(downloadId: String) = Unit

        override suspend fun capabilities(): EngineCapabilities =
            EngineCapabilities("test", "0", supportsPause = false, supportsSubtitles = false)

        override fun close() = Unit
    }

    private class SuspendingEngine : DownloadEngine {
        private val canceled = CompletableDeferred<Unit>()
        var cancelCalls: Int = 0
            private set

        override suspend fun inspect(url: String): EngineResult<Inspection> =
            EngineResult.Failure(DownloadFailure.UnsupportedSource)

        override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = flow {
            emit(DownloadEvent.Starting)
            emit(DownloadEvent.Progress(12.5))
            canceled.await()
            emit(DownloadEvent.Canceled)
        }

        override suspend fun cancel(downloadId: String) {
            cancelCalls++
            canceled.complete(Unit)
        }

        override suspend fun capabilities(): EngineCapabilities =
            EngineCapabilities("test", "0", supportsPause = false, supportsSubtitles = false)

        override fun close() = Unit
    }
}
