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
}
