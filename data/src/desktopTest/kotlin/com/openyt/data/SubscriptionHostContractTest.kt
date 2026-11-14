package com.openyt.data

import com.openyt.core.application.*
import com.openyt.core.automation.*
import com.openyt.core.domain.*
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlin.test.*

class SubscriptionHostContractTest {
    @Test fun actualFeedAndSpaceCapabilitiesPreserveDeferredWorkAcrossRestart() = runBlocking<Unit> {
        val root = Files.createTempDirectory("openyt-native-rules-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val episode = AtomicReference("baseline")
        val responder = thread(isDaemon = true) {
            while (!server.isClosed) try { server.accept().use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val body = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><item><guid>${episode.get()}</guid><title>Native rules</title><itunes:duration>00:00:42</itunes:duration><enclosure url="https://example.com/${episode.get()}.mp3" length="100"/></item></channel></rss>""".toByteArray()
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
            } } catch (_: Exception) { if (server.isClosed) break }
        }
        val engine = object : DownloadEngine {
            override suspend fun inspect(url: String): EngineResult<Inspection> = error("Known feed metadata must avoid inspection")
            override fun download(request: DownloadRequest, downloadId: String): Flow<DownloadEvent> = error("Discovery cannot start downloads")
            override suspend fun cancel(downloadId: String) = Unit
            override suspend fun capabilities() = EngineCapabilities("fixture", null, false, false)
            override fun close() = Unit
        }
        try {
            val database = createOpenYtDatabase(root.resolve("rules.db").path)
            val repository = SqlDelightSubscriptions(database)
            val downloads = SqlDelightDownloadRepository(database)
            val environment = JvmSubscriptionEnvironment()
            val actual = environment.state(root.path)
            assertNotNull(actual.connected)
            assertTrue((actual.availableBytes ?: 0) > 0)
            assertNull(actual.wifi); assertNull(actual.unmetered); assertNull(actual.charging)
            assertFalse(environment.supportsDeviceConditions)
            val coordinator = DownloadCoordinator(engine, scope, repository = downloads)
            coordinator.restore()
            fun controller() = SubscriptionController(repository, RssFeedReader(), engine, coordinator, { root.path }, { 10 }, { "native" }, scope, environment = environment)
            val first = controller()
            assertTrue(first.add("Native", "http://127.0.0.1:${server.localPort}/feed", SubscriptionKind.FEED, 60, false,
                SubscriptionFilters(minimumDurationSeconds = 10)))
            assertEquals(0, first.check("native", queueOnly = true))
            assertTrue(coordinator.tasks.value.isEmpty())
            episode.set("next")
            first.updateFilters("native", SubscriptionFilters(minimumDurationSeconds = 10, minimumFreeBytes = actual.availableBytes!! + 1_000_000_000_000))
            assertEquals(0, first.check("native", queueOnly = true))
            assertEquals(SubscriptionReason.LOW_SPACE, first.rules.value.single().candidates.single().reason)
            val restarted = controller()
            restarted.restore()
            restarted.updateFilters("native", SubscriptionFilters(minimumDurationSeconds = 10))
            assertEquals(1, restarted.check("native", queueOnly = true))
            assertEquals(0, restarted.check("native", queueOnly = true))
            assertEquals("native:next", coordinator.tasks.value.single().request.options.originKey)
            assertTrue(coordinator.awaitPersistence())
            assertEquals(1, downloads.loadTasks().size)
            println("Native rules ${System.getProperty("os.name")}: connected=${actual.connected}, spaceKnown=true, deviceConditions=false; RSS duration/size, baseline, reserve, restart, unique queue verified")
        } finally { scope.cancel(); server.close(); responder.join(2_000); root.deleteRecursively() }
    }
}
