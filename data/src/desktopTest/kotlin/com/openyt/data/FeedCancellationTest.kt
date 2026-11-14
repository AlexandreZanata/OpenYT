package com.openyt.data

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlin.test.*

class FeedCancellationTest {
    @Test fun cancelClosesAnActualTricklingResponseBeforeItsReadTimeout() = runBlocking<Unit> {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val headersSent = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val responder = thread(isDaemon = true) {
            try { server.accept().use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\n<rss>".toByteArray())
                output.flush(); headersSent.countDown()
                while (true) { output.write(' '.code); output.flush(); Thread.sleep(20) }
            } } catch (_: Exception) { disconnected.countDown() }
        }
        try {
            val job = launch(Dispatchers.Default) { RssFeedReader().episodes("http://127.0.0.1:${server.localPort}/slow") }
            assertTrue(withContext(Dispatchers.IO) { headersSent.await(5, TimeUnit.SECONDS) })
            val started = System.nanoTime()
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
            assertTrue(withContext(Dispatchers.IO) { disconnected.await(3, TimeUnit.SECONDS) }, "Cancel must close the actual HTTP connection")
        } finally { server.close(); responder.join(2_000) }
    }
}
