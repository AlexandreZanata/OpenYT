package com.openyt.core.automation

import com.openyt.core.application.DownloadCoordinator
import com.openyt.core.domain.*
import com.openyt.core.testing.FakeDownloadEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadExecutionConstraintsTest {
    private class Engine : DownloadEngine by FakeDownloadEngine() {
        val requests = mutableListOf<DownloadRequest>()
        val events = mutableMapOf<String, Channel<DownloadEvent>>()
        override fun download(request: DownloadRequest, downloadId: String) = flow {
            requests += request
            val channel = Channel<DownloadEvent>(Channel.UNLIMITED)
            events[downloadId] = channel
            emit(DownloadEvent.Starting)
            for (event in channel) emit(event)
        }
        override suspend fun pause(downloadId: String) { events[downloadId]?.send(DownloadEvent.Paused); events[downloadId]?.close() }
        fun complete(id: String) { events[id]!!.trySend(DownloadEvent.Completed("/fixture/$id.mp4", 100)); events[id]!!.close() }
    }

    @Test fun queuedRulesRecheckDeviceConditionsAndDoNotBlockUnconstrainedManualWork() = runTest {
        val engine = Engine()
        var state = SubscriptionDeviceState(true, wifi = true)
        val coordinator = DownloadCoordinator(engine, backgroundScope, initialMaxConcurrentDownloads = 1,
            executionGate = SubscriptionDownloadGate(SubscriptionEnvironment { state }))
        val media = FakeDownloadEngine.SAMPLE_MEDIA
        val first = coordinator.enqueue(media, DownloadRequest("https://example.com/first", destinationDirectory = "/tmp"))
        runCurrent()
        val constrained = coordinator.enqueue(media, DownloadRequest("https://example.com/rule", destinationDirectory = "/tmp",
            options = DownloadOptions(executionConstraints = DownloadExecutionConstraints(wifiOnly = true))))
        state = state.copy(wifi = false)
        engine.complete(first)
        runCurrent()
        assertTrue(coordinator.tasks.value.single { it.id == constrained }.waitingForConstraints)
        assertEquals(1, engine.requests.size)
        val manual = coordinator.enqueue(media, DownloadRequest("https://example.com/manual", destinationDirectory = "/tmp"))
        runCurrent()
        assertEquals("https://example.com/manual", engine.requests.last().url)
        state = state.copy(wifi = true)
        engine.complete(manual)
        coordinator.refreshExecutionConstraints()
        runCurrent()
        assertEquals("https://example.com/rule", engine.requests.last().url)
        assertFalse(coordinator.tasks.value.single { it.id == constrained }.waitingForConstraints)
    }

    @Test fun runningRulePausesWhenPowerConditionIsLostAndKeepsItsSnapshot() = runTest {
        val engine = Engine()
        var state = SubscriptionDeviceState(true, charging = true)
        val coordinator = DownloadCoordinator(engine, backgroundScope,
            executionGate = SubscriptionDownloadGate(SubscriptionEnvironment { state }))
        val saved = DownloadOptions(executionConstraints = DownloadExecutionConstraints(chargingOnly = true))
        val id = coordinator.enqueue(FakeDownloadEngine.SAMPLE_MEDIA, DownloadRequest("https://example.com/rule", destinationDirectory = "/tmp", options = saved))
        runCurrent()
        state = state.copy(charging = false)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(DownloadStatus.PAUSED, coordinator.tasks.value.single().status)
        assertEquals(saved, coordinator.tasks.value.single().request.options)
        state = state.copy(charging = true)
        coordinator.resume(id)
        runCurrent()
        assertEquals(2, engine.requests.size)
    }
}
