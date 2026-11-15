package com.openyt.data

import com.openyt.core.application.PortableArchiveReadPolicy
import com.openyt.core.application.PortableArchivePathRules
import com.openyt.core.application.PortableBackupFailure
import com.openyt.core.application.PortableBackupFormat
import com.openyt.core.application.PortableReadEntryDisposition
import com.openyt.core.application.PortableSourceMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortableZipStreamingTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun streamsThroughNonSeekableSafStylePipesWithUnicodeNamesAndProgress() = runBlocking {
        val stagingRoot = tempFolder.newFolder("staging")
        val mediaFile = File(tempFolder.root, "Café_Ação_日本語_🎬.mp4").apply {
            writeBytes(ByteArray(256 * 1024) { idx -> ((idx * 31) and 0xFF).toByte() })
        }

        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 16 * 1024)

        val nonSeekableBridge = object : PortableStreamBridge {
            private val fileBridge = FilePortableStreamBridge()
            override fun openInputStream(locator: String): InputStream =
                if (locator == "saf://backup.zip") {
                    // Simulate Android non-seekable chunked InputStream
                    object : FilterInputStream(pipeIn) {
                        override fun markSupported(): Boolean = false
                        override fun available(): Int = 0
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            super.read(b, off, minOf(len, 197))
                    }
                } else {
                    fileBridge.openInputStream(locator)
                }

            override fun openWriteTarget(locator: String, operationId: String, stagingRoot: File): PortableStreamBridge.OutputTarget {
                val guardedOut = object : FilterOutputStream(pipeOut) {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        out.write(b, off, len)
                    }
                }
                return object : PortableStreamBridge.OutputTarget {
                    override val outputStream: OutputStream = guardedOut
                    override fun commit() {
                        guardedOut.flush()
                        guardedOut.close()
                    }
                    override fun abort() {
                        runCatching { guardedOut.close() }
                    }
                }
            }

            override fun inspectSource(locator: String): PortableSourceMetadata = fileBridge.inspectSource(locator)
            override fun availableSpaceFor(locator: String, fallbackDir: File): Long = fileBridge.availableSpaceFor(locator, fallbackDir)
        }

        val writer = JvmPortableArchiveWriter(stagingRoot, nonSeekableBridge)
        val reader = JvmPortableArchiveReader(nonSeekableBridge)

        var progressWritten = 0L
        lateinit var committedSha: String
        val writeJob = launch(kotlinx.coroutines.Dispatchers.IO) {
            val session = writer.openOutputSession("saf://backup.zip", "op-stream").getOrThrow()
            val blob = session.writeFileEntry("media/e1/${mediaFile.name}", mediaFile.absolutePath) { delta ->
                progressWritten += delta
            }.getOrThrow()
            assertEquals(mediaFile.length(), blob.byteCount)
            session.writeJsonEntry(PortableBackupFormat.MANIFEST_ENTRY_NAME, """{"formatVersion":1}""").getOrThrow()
            committedSha = session.commit().getOrThrow()
        }

        val extractDir = tempFolder.newFolder("extract")
        val policy = object : PortableArchiveReadPolicy {
            override val maxTotalExpandedBytes: Long = 10L * 1024 * 1024
            override fun inspectEntry(
                entryIndex: Int,
                rawName: String,
                isDirectory: Boolean,
                declaredSizeBytes: Long,
                isSymlink: Boolean,
            ): Result<PortableReadEntryDisposition> {
                if (isSymlink) {
                    return Result.failure(PortableBackupFailure.SecurityViolation("Symlink entry disallowed").toException())
                }
                if (isDirectory) return Result.success(PortableReadEntryDisposition.SKIP_DIRECTORY)
                return PortableArchivePathRules.validateEntryPath(rawName).map { normalized ->
                    if (normalized == PortableBackupFormat.MANIFEST_ENTRY_NAME) {
                        PortableReadEntryDisposition.BUFFER_MANIFEST
                    } else {
                        PortableReadEntryDisposition.STAGE_BLOB
                    }
                }
            }
        }

        val readSummary = reader.readAndStageStream("saf://backup.zip", extractDir.absolutePath, policy).getOrThrow()
        writeJob.join()

        assertEquals(committedSha, readSummary.archiveSha256)
        assertEquals(mediaFile.length(), progressWritten)
        assertEquals(2, readSummary.entryCount)
        assertEquals("""{"formatVersion":1}""", readSummary.manifestBytes?.decodeToString())
        val stagedBlob = readSummary.stagedBlobsByPath["media/e1/${mediaFile.name}"]!!
        assertEquals(mediaFile.length(), stagedBlob.byteCount)
        assertEquals(
            File(stagedBlob.stagedFileLocator!!).readBytes().toList(),
            mediaFile.readBytes().toList(),
        )
    }

    @Test
    fun cancellationAbortsMidStreamAndCleansUpTempArchive() = runBlocking {
        val stagingRoot = tempFolder.newFolder("cancel-staging")
        val targetArchive = File(tempFolder.root, "canceled.openyt-backup.zip")
        val largeSource = File(tempFolder.root, "slow.bin").apply {
            writeBytes(ByteArray(2 * 1024 * 1024))
        }
        val slowBridge = object : PortableStreamBridge by FilePortableStreamBridge() {
            override fun openInputStream(locator: String): InputStream {
                val base = FilePortableStreamBridge().openInputStream(locator)
                return object : FilterInputStream(base) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        Thread.sleep(5)
                        return super.read(b, off, minOf(len, 16 * 1024))
                    }
                }
            }
        }
        val writer = JvmPortableArchiveWriter(stagingRoot, slowBridge)
        val session = writer.openOutputSession(targetArchive.absolutePath, "op-cancel").getOrThrow()
        val job: Job = launch {
            session.writeFileEntry("media/e1/slow.bin", largeSource.absolutePath)
        }
        delay(30)
        job.cancelAndJoin()
        session.abortAndCleanup()

        assertFalse(targetArchive.exists())
        assertEquals(0, stagingRoot.listFiles()?.size ?: 0)
    }

    @Test
    fun zip64StreamsOverFourGibabytesWithoutFullBuffering() = runBlocking {
        val stagingRoot = tempFolder.newFolder("zip64-staging")
        val usableBytes = stagingRoot.usableSpace
        // Skip only if filesystem lacks 128 MiB headroom for compressed zero-stream test
        if (usableBytes < 128L * 1024L * 1024L) return@runBlocking

        val archiveFile = File(tempFolder.root, "zip64-4gib.openyt-backup.zip")
        val over4GibBytes = 4_300_000_000L // > 4 GiB (0x100000000)

        val zeroSourceBridge = object : PortableStreamBridge by FilePortableStreamBridge() {
            override fun openInputStream(locator: String): InputStream {
                if (locator != "virtual://zero-4gib") return FilePortableStreamBridge().openInputStream(locator)
                return object : InputStream() {
                    private var remaining = over4GibBytes
                    override fun read(): Int {
                        if (remaining <= 0L) return -1
                        remaining--
                        return 0
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining <= 0L) return -1
                        val toRead = minOf(len.toLong(), remaining).toInt()
                        java.util.Arrays.fill(b, off, off + toRead, 0.toByte())
                        remaining -= toRead
                        return toRead
                    }
                }
            }
        }

        val writer = JvmPortableArchiveWriter(stagingRoot, zeroSourceBridge)
        val session = writer.openOutputSession(archiveFile.absolutePath, "op-zip64").getOrThrow()
        val writtenInfo = session.writeFileEntry("media/big/4gib.bin", "virtual://zero-4gib").getOrThrow()
        assertEquals(over4GibBytes, writtenInfo.byteCount)
        session.writeJsonEntry(PortableBackupFormat.MANIFEST_ENTRY_NAME, """{"formatVersion":1}""").getOrThrow()
        val archiveSha = session.commit().getOrThrow()
        assertEquals(64, archiveSha.length)
        // Compressed size is only a few MiB despite >4 GiB uncompressed stream!
        assertTrue(archiveFile.length() < 64L * 1024L * 1024L)

        // Stream back through non-File ZipInputStream verifying all 4.3B bytes & SHA-256 without loading into RAM
        var verifiedBytes = 0L
        val readDigest = java.security.MessageDigest.getInstance("SHA-256")
        java.util.zip.ZipInputStream(archiveFile.inputStream().buffered(64 * 1024)).use { zipIn ->
            val entry = zipIn.nextEntry!!
            assertEquals("media/big/4gib.bin", entry.name)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = zipIn.read(buf)
                if (r <= 0) break
                readDigest.update(buf, 0, r)
                verifiedBytes += r
            }
        }
        assertEquals(over4GibBytes, verifiedBytes)
        val actualSha = readDigest.digest().joinToString("") { "%02x".format(it) }
        assertEquals(writtenInfo.sha256, actualSha)
    }

    @Test
    fun zip64StreamsOverFourGibabytesIncompressiblePayloadWithBoundedMemory() = runBlocking {
        Assume.assumeTrue(
            "Set OPENYT_RUN_ZIP64_INCOMPRESSIBLE_TESTS=1 on a host with >=10 GiB free space",
            System.getenv("OPENYT_RUN_ZIP64_INCOMPRESSIBLE_TESTS") == "1",
        )
        val stagingRoot = tempFolder.newFolder("zip64-incompressible-staging")
        val extractDir = tempFolder.newFolder("zip64-incompressible-extract")
        val usableBytes = stagingRoot.usableSpace
        Assume.assumeTrue(
            "Requires >=10 GiB free disk space, found ${usableBytes / (1024 * 1024)} MiB",
            usableBytes >= 10L * 1024L * 1024L * 1024L,
        )

        val archiveFile = File(tempFolder.root, "zip64-incompressible.openyt-backup.zip")
        val over4GibBytes = 4_350_000_000L // >4 GiB uncompressed AND >4 GiB compressed ZIP64 central directory
        val runtime = Runtime.getRuntime()
        System.gc()
        val baselineUsedBytes = runtime.totalMemory() - runtime.freeMemory()
        var peakHeapDeltaBytes = 0L
        fun sampleHeap() {
            val delta = (runtime.totalMemory() - runtime.freeMemory()) - baselineUsedBytes
            if (delta > peakHeapDeltaBytes) peakHeapDeltaBytes = delta
        }

        val incompressibleBridge = object : PortableStreamBridge by FilePortableStreamBridge() {
            override fun openInputStream(locator: String): InputStream {
                if (locator != "virtual://incompressible-4gib") {
                    return FilePortableStreamBridge().openInputStream(locator)
                }
                return object : InputStream() {
                    private var state = 0x9E3779B97F4A7C15UL.toLong()
                    private var remaining = over4GibBytes
                    private val scratch = ByteArray(64 * 1024)
                    private var scratchPos = scratch.size

                    private fun refill() {
                        var i = 0
                        while (i + 8 <= scratch.size) {
                            state += -0x61c8864680b583ebL
                            var z = state
                            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
                            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
                            z = z xor (z ushr 31)
                            scratch[i] = z.toByte()
                            scratch[i + 1] = (z ushr 8).toByte()
                            scratch[i + 2] = (z ushr 16).toByte()
                            scratch[i + 3] = (z ushr 24).toByte()
                            scratch[i + 4] = (z ushr 32).toByte()
                            scratch[i + 5] = (z ushr 40).toByte()
                            scratch[i + 6] = (z ushr 48).toByte()
                            scratch[i + 7] = (z ushr 56).toByte()
                            i += 8
                        }
                        scratchPos = 0
                    }

                    override fun read(): Int {
                        if (remaining <= 0L) return -1
                        if (scratchPos >= scratch.size) refill()
                        remaining--
                        return scratch[scratchPos++].toInt() and 0xFF
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining <= 0L) return -1
                        if (scratchPos >= scratch.size) refill()
                        val available = scratch.size - scratchPos
                        val toRead = minOf(len.toLong(), available.toLong(), remaining).toInt()
                        System.arraycopy(scratch, scratchPos, b, off, toRead)
                        scratchPos += toRead
                        remaining -= toRead
                        sampleHeap()
                        return toRead
                    }
                }
            }
        }

        try {
            val writer = JvmPortableArchiveWriter(stagingRoot, incompressibleBridge)
            val session = writer.openOutputSession(archiveFile.absolutePath, "op-zip64-incompressible").getOrThrow()
            val writtenInfo = session.writeFileEntry("media/big/4gib-random.bin", "virtual://incompressible-4gib").getOrThrow()
            assertEquals(over4GibBytes, writtenInfo.byteCount)
            session.writeJsonEntry(PortableBackupFormat.MANIFEST_ENTRY_NAME, """{"formatVersion":1}""").getOrThrow()
            val archiveSha = session.commit().getOrThrow()
            sampleHeap()
            assertEquals(64, archiveSha.length)
            assertTrue(archiveFile.length() > 4_294_967_296L, "Expected ZIP archive >4 GiB, was ${archiveFile.length()}")

            val reader = JvmPortableArchiveReader(FilePortableStreamBridge())
            val policy = object : PortableArchiveReadPolicy {
                override val maxTotalExpandedBytes: Long = 5_000_000_000L
                override fun inspectEntry(
                    entryIndex: Int,
                    rawName: String,
                    isDirectory: Boolean,
                    declaredSizeBytes: Long,
                    isSymlink: Boolean,
                ): Result<PortableReadEntryDisposition> {
                    if (isSymlink) {
                        return Result.failure(PortableBackupFailure.SecurityViolation("Symlink entry disallowed").toException())
                    }
                    if (isDirectory) return Result.success(PortableReadEntryDisposition.SKIP_DIRECTORY)
                    return PortableArchivePathRules.validateEntryPath(rawName).map { normalized ->
                        if (normalized == PortableBackupFormat.MANIFEST_ENTRY_NAME) {
                            PortableReadEntryDisposition.BUFFER_MANIFEST
                        } else {
                            PortableReadEntryDisposition.STAGE_BLOB
                        }
                    }
                }
            }

            val summary = reader.readAndStageStream(archiveFile.absolutePath, extractDir.absolutePath, policy).getOrThrow()
            sampleHeap()
            assertEquals(archiveSha, summary.archiveSha256)
            assertEquals("""{"formatVersion":1}""", summary.manifestBytes?.decodeToString())
            val stagedBlob = summary.stagedBlobsByPath["media/big/4gib-random.bin"]!!
            assertEquals(over4GibBytes, stagedBlob.byteCount)
            assertEquals(writtenInfo.sha256, stagedBlob.sha256)
            assertTrue(
                peakHeapDeltaBytes < 256L * 1024L * 1024L,
                "Heap growth exceeded 256 MiB during 4.35 GB ZIP64 round-trip: ${peakHeapDeltaBytes / (1024 * 1024)} MiB",
            )
            println("NX-B02 incompressible >4 GiB ZIP64 verified: archiveBytes=${archiveFile.length()}, payloadBytes=$over4GibBytes, peakHeapDeltaMiB=${peakHeapDeltaBytes / (1024 * 1024)}")
        } finally {
            archiveFile.delete()
            extractDir.deleteRecursively()
        }
    }
}
