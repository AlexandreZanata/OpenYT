package com.openyt.desktop.downloader

import com.openyt.core.domain.DownloadEvent
import com.openyt.core.domain.DownloadRequest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Test
import kotlin.test.*

object InspectionHangFixture {
    @JvmStatic fun main(args: Array<String>) { File(args.single()).writeText("ready"); Thread.sleep(60_000) }
}

class InspectionCancellationTest {
    @Test fun cancelledInspectionReapsItsProcessAndPreservesAnUnrelatedDownload() = runBlocking<Unit> {
        val java = File(System.getProperty("java.home"), "bin/${if (System.getProperty("os.name").contains("Windows")) "java.exe" else "java"}").path
        val inspected = CopyOnWriteArrayList<Process>()
        val downloading = CopyOnWriteArrayList<Process>()
        val childClasspath = listOf(InspectionHangFixture::class.java, kotlin.Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val root = Files.createTempDirectory("openyt-inspection-cancel-").toFile()
        val engine = DesktopYtDlpEngine(ExecutableLocator(environment = { if (it == "OPENYT_YTDLP") java else null }, bundledDirectory = { null })) { args ->
            val ready = root.resolve(if ("--dump-single-json" in args) "inspection-ready" else "download-ready")
            ProcessBuilder(java, "-cp", childClasspath, InspectionHangFixture::class.java.name, ready.path).start().also {
                if ("--dump-single-json" in args) inspected += it else downloading += it
            }
        }
        try {
            val transfer = async(Dispatchers.IO) { engine.download(DownloadRequest("https://example.com/video", destinationDirectory = root.path), "active").toList() }
            withTimeout(10_000) { while (!root.resolve("download-ready").isFile) delay(10) }
            val inspection = launch(Dispatchers.Default) { engine.inspect("https://example.com/playlist") }
            withTimeout(10_000) { while (!root.resolve("inspection-ready").isFile) delay(10) }
            withTimeout(3_000) { inspection.cancelAndJoin() }
            assertFalse(inspected.single().isAlive, "Inspection child must be reaped")
            assertTrue(downloading.single().isAlive, "Canceling inspection must preserve other transfers")
            engine.cancel("active")
            assertTrue(withTimeout(3_000) { transfer.await() }.last() is DownloadEvent.Canceled)
        } finally { engine.close(); root.deleteRecursively() }
    }
}
