package com.openyt.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.openyt.core.automation.SubscriptionKind
import com.openyt.core.domain.DownloadStatus
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class AndroidBackgroundSubscriptionTest {
    @Test fun optedInRealWorkManagerChecksWithoutAnActivityAndQueuesOnlyUntilAppOpens() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as OpenYtApplication).container
        assertTrue(container.awaitInitialization())
        assertFalse("Background proof must run without a visible activity", container.isAppVisible)
        val device = AndroidSubscriptionEnvironment(context).state(container.downloadsDirectory)
        assertNotNull(device.connected)
        assertNotNull(device.charging)
        assertTrue("Actual destination must report free space", (device.availableBytes ?: 0) > 0)
        android.util.Log.i("OpenYTContracts", "Android subscription capabilities: $device")
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val running = AtomicBoolean(true)
        val feedRequests = java.util.concurrent.atomic.AtomicInteger()
        val episodeId = "background-${UUID.randomUUID()}"
        val body = """<rss><channel><item><guid>$episodeId</guid><title>Background fixture</title><enclosure url="https://example.com/$episodeId.mp3"/></item></channel></rss>""".toByteArray()
        val responder = thread(isDaemon = true) {
            while (running.get()) {
                try { server.accept().use { socket ->
                    feedRequests.incrementAndGet()
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/rss+xml\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body)
                    }
                } } catch (_: Exception) { if (server.isClosed) break }
            }
        }
        val controller = checkNotNull(container.subscriptions)
        val scheduler = container.backgroundScheduler
        val manager = WorkManager.getInstance(context)
        var ruleId: String? = null
        try {
            assertFalse("Background discovery defaults off", scheduler.enabled.value)
            val url = "http://127.0.0.1:${server.localPort}/feed.xml"
            assertTrue(controller.add("Background fixture", url, SubscriptionKind.FEED, 60, true))
            ruleId = controller.rules.value.single { it.url == url }.id
            scheduler.setEnabled(true)
            val request = OneTimeWorkRequestBuilder<SubscriptionDiscoveryWorker>().build()
            manager.enqueue(request).result.get(30, TimeUnit.SECONDS)
            val clock = System.nanoTime()
            var state: WorkInfo.State? = null
            while (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - clock) < 45) {
                state = manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)?.state
                if (state?.isFinished == true) break
                Thread.sleep(100)
            }
            assertEquals(WorkInfo.State.SUCCEEDED, state)
            val task = container.coordinator.tasks.value.singleOrNull { it.request.options.originKey == "$ruleId:$episodeId" }
            val rule = controller.rules.value.firstOrNull { it.id == ruleId }
            assertNotNull("Expected queued episode; requests=${feedRequests.get()}, failures=${controller.failedIds.value.size}, waiting=${controller.waitingReasons.value[ruleId]}, candidates=${rule?.candidates?.map { it.state to it.reason }}, device=${AndroidSubscriptionEnvironment(context).state(container.downloadsDirectory)}", task)
            checkNotNull(task)
            assertEquals(DownloadStatus.QUEUED, task.status)
            assertTrue(task.request.options.executionConstraints!!.requireAppOpen)
            assertNull(task.filePath)
            scheduler.setEnabled(false)
            assertFalse(scheduler.enabled.value)
            assertTrue(manager.getWorkInfosForUniqueWork(AndroidSubscriptionScheduler.WORK_NAME).get(30, TimeUnit.SECONDS).all { it.state == WorkInfo.State.CANCELLED })
            container.coordinator.cancel(task.id)
            container.coordinator.dismiss(task.id)
            assertTrue(container.coordinator.awaitPersistence())
        } finally {
            scheduler.setEnabled(false)
            ruleId?.let { controller.remove(it) }
            running.set(false); server.close(); responder.join(2_000)
        }
    }
}
