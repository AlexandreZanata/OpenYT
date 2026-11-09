package com.openyt.core.application

import com.openyt.core.domain.DownloadEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.DownloadStatus
import com.openyt.core.domain.EngineCapabilities
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.MediaInfo
import com.openyt.core.testing.FakeDownloadEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        val second = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, request())
        advanceUntilIdle()

        assertTrue(first != second)
        assertEquals(2, coordinator.tasks.value.size)
    }

    private fun TestScope.coordinatorScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

    private fun request() = DownloadRequest(
        url = "https://example.com/watch?v=1",
        destinationDirectory = "/tmp/openyt",
    )

    private class ImmediateEngine(private val events: List<DownloadEvent>) : DownloadEngine {
        override suspend fun inspect(url: String): EngineResult<MediaInfo> =
            EngineResult.Failure(DownloadFailure.UnsupportedSource)

        override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> =
            flow { events.forEach { emit(it) } }

        override suspend fun cancel(downloadId: String) = Unit

        override suspend fun capabilities(): EngineCapabilities =
            EngineCapabilities("test", "0", supportsPause = false, supportsSubtitles = false)

        override fun close() = Unit
    }

    private class SuspendingEngine : DownloadEngine {
        private val canceled = CompletableDeferred<Unit>()
        var cancelCalls: Int = 0
            private set

        override suspend fun inspect(url: String): EngineResult<MediaInfo> =
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
