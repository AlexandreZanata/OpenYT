package com.openyt.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openyt.android.downloader.AndroidYtDlpEngine
import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadRequest
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class AndroidInspectionCancellationTest {
    @Test fun cancellingActualPythonInspectionClosesItsSocketAndPreservesAnotherTransfer() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AndroidYtDlpEngine(context)
        engine.capabilities() // Qualify initialization before measuring cancellation.
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val sockets = CopyOnWriteArrayList<Socket>()
        val inspectionStarted = CountDownLatch(1)
        val downloadStarted = CountDownLatch(1)
        val inspectionClosed = CountDownLatch(1)
        val downloadClosed = CountDownLatch(1)
        val root = File(context.cacheDir, "inspection-cancel-contract").apply { mkdirs() }
        val responder = thread(isDaemon = true) {
            while (!server.isClosed) try {
                val socket = server.accept(); sockets += socket
                thread(isDaemon = true) {
                    var inspect = false
                    try { socket.use {
                        it.soTimeout = 10_000
                        val reader = it.getInputStream().bufferedReader()
                        inspect = reader.readLine().orEmpty().contains("/inspect")
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val output = it.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\n<html>".toByteArray())
                        output.flush()
                        (if (inspect) inspectionStarted else downloadStarted).countDown()
                        while (!server.isClosed) { output.write(' '.code); output.flush(); Thread.sleep(20) }
                    } } catch (_: Exception) { (if (inspect) inspectionClosed else downloadClosed).countDown() }
                }
            } catch (_: Exception) { if (server.isClosed) break }
        }
        var transfer: Deferred<List<DownloadEvent>>? = null
        var inspection: Job? = null
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            transfer = async(Dispatchers.IO) { engine.download(DownloadRequest("$base/download", destinationDirectory = root.path), "concurrent-inspection-contract").toList() }
            assertTrue(withContext(Dispatchers.IO) { downloadStarted.await(20, TimeUnit.SECONDS) })
            inspection = launch(Dispatchers.Default) { engine.inspect("$base/inspect") }
            assertTrue(withContext(Dispatchers.IO) { inspectionStarted.await(20, TimeUnit.SECONDS) })
            withTimeout(3_000) { inspection.cancelAndJoin() }
            assertTrue("The actual inspection HTTP socket must close", withContext(Dispatchers.IO) { inspectionClosed.await(3, TimeUnit.SECONDS) })
            assertEquals("Other transfer must stay alive", 1L, downloadClosed.count)
            engine.cancel("concurrent-inspection-contract")
            assertTrue(withTimeout(5_000) { transfer.await() }.last() is DownloadEvent.Canceled)
        } finally {
            inspection?.cancelAndJoin()
            engine.cancel("concurrent-inspection-contract")
            transfer?.cancelAndJoin()
            server.close(); sockets.forEach { runCatching { it.close() } }; responder.join(2_000)
            engine.close(); root.deleteRecursively()
        }
    }
}
