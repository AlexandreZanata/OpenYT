package com.openyt.data

import com.openyt.core.application.PortableArchiveOutputSession
import com.openyt.core.application.PortableArchiveReadPolicy
import com.openyt.core.application.PortableArchiveReadSummary
import com.openyt.core.application.PortableArchiveReaderPort
import com.openyt.core.application.PortableArchiveWriterPort
import com.openyt.core.application.PortableBackupException
import com.openyt.core.application.PortableBackupFailure
import com.openyt.core.application.PortableOwnedFileReceipt
import com.openyt.core.application.PortableReadEntryDisposition
import com.openyt.core.application.PortableSourceMetadata
import com.openyt.core.application.PortableStagedEntryResult
import com.openyt.core.application.PortableStorageAccessPort
import com.openyt.core.application.PortableStreamedBlobInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Abstracts raw stream opening so both Desktop local filesystem paths and Android
 * `content://` SAF URIs share the same O(buffer) streaming ZIP logic without
 * holding archives in memory.
 */
interface PortableStreamBridge {
    fun openInputStream(locator: String): InputStream
    fun openWriteTarget(locator: String, operationId: String, stagingRoot: File): OutputTarget
    fun inspectSource(locator: String): PortableSourceMetadata
    fun availableSpaceFor(locator: String, fallbackDir: File): Long

    interface OutputTarget {
        val outputStream: OutputStream
        fun commit()
        fun abort()
    }
}

class FilePortableStreamBridge : PortableStreamBridge {

    override fun openInputStream(locator: String): InputStream {
        val file = File(locator)
        if (!file.exists()) {
            throw PortableBackupFailure.SourceMissing(listOf(file.name)).toException()
        }
        if (!file.canRead()) {
            throw PortableBackupFailure.PermissionDenied(file.name).toException()
        }
        return file.inputStream()
    }

    override fun openWriteTarget(locator: String, operationId: String, stagingRoot: File): PortableStreamBridge.OutputTarget {
        val finalFile = File(locator)
        finalFile.parentFile?.mkdirs()
        stagingRoot.mkdirs()
        val tempFile = File(stagingRoot, "export-$operationId.zip.part")
        if (tempFile.exists()) tempFile.delete()
        val stream = tempFile.outputStream()
        return object : PortableStreamBridge.OutputTarget {
            override val outputStream: OutputStream = stream

            override fun commit() {
                if (finalFile.exists()) {
                    // Never overwrite an existing archive blindly; ensure parent dir exists
                    val parent = finalFile.parentFile ?: stagingRoot
                    val unique = uniqueFile(parent, finalFile.name)
                    moveFile(tempFile, unique)
                } else {
                    moveFile(tempFile, finalFile)
                }
            }

            override fun abort() {
                runCatching { stream.close() }
                tempFile.delete()
            }
        }
    }

    override fun inspectSource(locator: String): PortableSourceMetadata {
        val file = File(locator)
        return when {
            !file.exists() -> PortableSourceMetadata.Missing
            !file.isFile -> PortableSourceMetadata.Unreadable("Not a regular file")
            !file.canRead() -> PortableSourceMetadata.PermissionDenied(file.name)
            else -> PortableSourceMetadata.Readable(
                sizeBytes = file.length(),
                lastModifiedEpochMillis = file.lastModified(),
                displayName = file.name,
            )
        }
    }

    override fun availableSpaceFor(locator: String, fallbackDir: File): Long {
        val file = File(locator)
        val probe = generateSequence(file) { it.parentFile }
            .firstOrNull { it.exists() }
            ?: fallbackDir.apply { mkdirs() }
        return probe.usableSpace.coerceAtLeast(0L)
    }

    private fun moveFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

class JvmPortableArchiveWriter(
    private val stagingRoot: File,
    private val streamBridge: PortableStreamBridge = FilePortableStreamBridge(),
    private val compressionLevel: Int = Deflater.BEST_SPEED,
) : PortableArchiveWriterPort {

    override suspend fun openOutputSession(
        archiveLocator: String,
        operationId: String,
    ): Result<PortableArchiveOutputSession> = withContext(Dispatchers.IO) {
        runCatching {
            val opStagingDir = File(stagingRoot, "export-$operationId").apply { mkdirs() }
            val target = streamBridge.openWriteTarget(archiveLocator, operationId, opStagingDir)
            val archiveDigest = MessageDigest.getInstance("SHA-256")
            val digestStream = DigestOutputStream(BufferedOutputStream(target.outputStream, BUFFER_BYTES), archiveDigest)
            val zipOut = ZipOutputStream(digestStream, StandardCharsets.UTF_8).apply {
                setLevel(compressionLevel)
            }
            JvmWriteSession(
                archiveLocator = archiveLocator,
                opStagingDir = opStagingDir,
                outputTarget = target,
                zipOut = zipOut,
                archiveDigest = archiveDigest,
                streamBridge = streamBridge,
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                Result.failure(mapStreamException(error))
            },
        )
    }

    private class JvmWriteSession(
        override val archiveLocator: String,
        private val opStagingDir: File,
        private val outputTarget: PortableStreamBridge.OutputTarget,
        private val zipOut: ZipOutputStream,
        private val archiveDigest: MessageDigest,
        private val streamBridge: PortableStreamBridge,
    ) : PortableArchiveOutputSession {
        private var closed = false
        private var aborted = false

        override suspend fun writeFileEntry(
            archivePath: String,
            sourceLocator: String,
            onProgress: suspend (bytesWrittenDelta: Long) -> Unit,
        ): Result<PortableStreamedBlobInfo> = withContext(Dispatchers.IO) {
            try {
                val entryDigest = MessageDigest.getInstance("SHA-256")
                var bytesWritten = 0L
                val entry = ZipEntry(archivePath)
                zipOut.putNextEntry(entry)
                streamBridge.openInputStream(sourceLocator).use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        entryDigest.update(buffer, 0, read)
                        zipOut.write(buffer, 0, read)
                        bytesWritten += read
                        onProgress(read.toLong())
                    }
                }
                zipOut.closeEntry()
                Result.success(
                    PortableStreamedBlobInfo(
                        archivePath = archivePath,
                        byteCount = bytesWritten,
                        sha256 = entryDigest.digest().toHexLower(),
                    ),
                )
            } catch (cancel: CancellationException) {
                abortAndCleanup()
                throw cancel
            } catch (error: Throwable) {
                Result.failure(mapStreamException(error))
            }
        }

        override suspend fun writeJsonEntry(
            archivePath: String,
            content: String,
        ): Result<PortableStreamedBlobInfo> = withContext(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive()
                val bytes = content.toByteArray(StandardCharsets.UTF_8)
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).toHexLower()
                val entry = ZipEntry(archivePath)
                zipOut.putNextEntry(entry)
                zipOut.write(bytes)
                zipOut.closeEntry()
                Result.success(
                    PortableStreamedBlobInfo(
                        archivePath = archivePath,
                        byteCount = bytes.size.toLong(),
                        sha256 = sha,
                    ),
                )
            } catch (cancel: CancellationException) {
                abortAndCleanup()
                throw cancel
            } catch (error: Throwable) {
                Result.failure(mapStreamException(error))
            }
        }

        override suspend fun commit(): Result<String> = withContext(Dispatchers.IO) {
            if (aborted) {
                return@withContext Result.failure(PortableBackupFailure.Canceled().toException())
            }
            try {
                if (!closed) {
                    zipOut.flush()
                    zipOut.close()
                    closed = true
                }
                outputTarget.commit()
                opStagingDir.deleteRecursively()
                Result.success(archiveDigest.digest().toHexLower())
            } catch (cancel: CancellationException) {
                abortAndCleanup()
                throw cancel
            } catch (error: Throwable) {
                abortAndCleanup()
                Result.failure(mapStreamException(error))
            }
        }

        override suspend fun abortAndCleanup() {
            aborted = true
            runCatching {
                if (!closed) {
                    zipOut.close()
                    closed = true
                }
            }
            runCatching { outputTarget.abort() }
            runCatching { opStagingDir.deleteRecursively() }
        }
    }
}

class JvmPortableArchiveReader(
    private val streamBridge: PortableStreamBridge = FilePortableStreamBridge(),
) : PortableArchiveReaderPort {

    override suspend fun readAndStageStream(
        archiveLocator: String,
        stagingDir: String,
        policy: PortableArchiveReadPolicy,
        onProgress: suspend (compressedBytesRead: Long, entriesProcessed: Int) -> Unit,
    ): Result<PortableArchiveReadSummary> = withContext(Dispatchers.IO) {
        val stageDirFile = File(stagingDir).apply { mkdirs() }
        try {
            val archiveDigest = MessageDigest.getInstance("SHA-256")
            var compressedBytesRead = 0L
            var entryIndex = 0
            var totalExpandedBytes = 0L
            var manifestBytes: ByteArray? = null
            val stagedBlobs = linkedMapOf<String, PortableStagedEntryResult>()
            val seenPaths = mutableSetOf<String>()
            val seenPathsLower = mutableSetOf<String>()

            streamBridge.openInputStream(archiveLocator).use { raw ->
                val countingStream = CountingDigestInputStream(BufferedInputStream(raw, BUFFER_BYTES), archiveDigest) { delta ->
                    compressedBytesRead += delta
                }
                ZipInputStream(countingStream, StandardCharsets.UTF_8).use { zipIn ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zipIn.nextEntry ?: break
                        entryIndex++
                        if (entryIndex > policy.maxEntryCount) {
                            throw PortableBackupFailure.SizeLimitExceeded(
                                limitName = "archiveEntryCount",
                                actual = entryIndex.toLong(),
                                limit = policy.maxEntryCount.toLong(),
                            ).toException()
                        }

                        val disposition = policy.inspectEntry(
                            entryIndex = entryIndex,
                            rawName = entry.name,
                            isDirectory = entry.isDirectory,
                            declaredSizeBytes = entry.size,
                        ).getOrThrow()

                        when (disposition) {
                            PortableReadEntryDisposition.SKIP_DIRECTORY -> {
                                zipIn.closeEntry()
                            }

                            PortableReadEntryDisposition.BUFFER_MANIFEST -> {
                                val normalized = entry.name
                                if (!seenPaths.add(normalized) || !seenPathsLower.add(normalized.lowercase())) {
                                    throw PortableBackupFailure.SecurityViolation("Duplicate manifest entry").toException()
                                }
                                val bytes = readBoundedBytes(zipIn, policy.maxManifestBytes, entry.size)
                                manifestBytes = bytes
                                totalExpandedBytes += bytes.size.toLong()
                                if (totalExpandedBytes > policy.maxTotalExpandedBytes) {
                                    throw PortableBackupFailure.SizeLimitExceeded(
                                        limitName = "expandedArchiveBytes",
                                        actual = totalExpandedBytes,
                                        limit = policy.maxTotalExpandedBytes,
                                    ).toException()
                                }
                                zipIn.closeEntry()
                            }

                            PortableReadEntryDisposition.STAGE_BLOB -> {
                                val normalized = entry.name
                                if (!seenPaths.add(normalized) || !seenPathsLower.add(normalized.lowercase())) {
                                    throw PortableBackupFailure.SecurityViolation("Duplicate archive entry: $normalized").toException()
                                }
                                val stagedFile = File(stageDirFile, "blob_${stagedBlobs.size}.part")
                                val remainingBudget = (policy.maxTotalExpandedBytes - totalExpandedBytes).coerceAtLeast(0L)
                                val stagedInfo = streamToStagedFile(
                                    zipIn = zipIn,
                                    stagedFile = stagedFile,
                                    declaredSize = entry.size,
                                    maxAllowedBytes = remainingBudget,
                                )
                                totalExpandedBytes += stagedInfo.first
                                stagedBlobs[normalized] = PortableStagedEntryResult(
                                    archivePath = normalized,
                                    byteCount = stagedInfo.first,
                                    sha256 = stagedInfo.second,
                                    stagedFileLocator = stagedFile.absolutePath,
                                )
                                zipIn.closeEntry()
                            }
                        }
                        onProgress(compressedBytesRead, entryIndex)
                    }
                    // Drain any remaining Central Directory / EOCD tail bytes BEFORE closing zipIn (which closes countingStream)
                    val drainBuf = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val r = countingStream.read(drainBuf)
                        if (r <= 0) break
                    }
                }
            }

            Result.success(
                PortableArchiveReadSummary(
                    archiveSha256 = archiveDigest.digest().toHexLower(),
                    archiveByteCount = compressedBytesRead,
                    entryCount = entryIndex,
                    totalExpandedBytes = totalExpandedBytes,
                    manifestBytes = manifestBytes,
                    stagedBlobsByPath = stagedBlobs,
                ),
            )
        } catch (cancel: CancellationException) {
            stageDirFile.deleteRecursively()
            throw cancel
        } catch (error: Throwable) {
            stageDirFile.deleteRecursively()
            Result.failure(mapStreamException(error))
        }
    }

    private suspend fun readBoundedBytes(
        zipIn: ZipInputStream,
        limit: Long,
        declaredSize: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = zipIn.read(buf)
            if (read <= 0) break
            total += read
            if (total > limit) {
                throw PortableBackupFailure.SizeLimitExceeded(
                    limitName = "manifest.json",
                    actual = total,
                    limit = limit,
                ).toException()
            }
            if (declaredSize >= 0L && total > declaredSize) {
                throw PortableBackupFailure.SecurityViolation(
                    "Entry expanded beyond declared ZIP size ($total > $declaredSize)",
                ).toException()
            }
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private suspend fun streamToStagedFile(
        zipIn: ZipInputStream,
        stagedFile: File,
        declaredSize: Long,
        maxAllowedBytes: Long,
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buf = ByteArray(BUFFER_BYTES)
        stagedFile.outputStream().buffered(BUFFER_BYTES).use { out ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = zipIn.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxAllowedBytes) {
                    throw PortableBackupFailure.SizeLimitExceeded(
                        limitName = "expandedArchiveBytes",
                        actual = total,
                        limit = maxAllowedBytes,
                    ).toException()
                }
                if (declaredSize >= 0L && total > declaredSize) {
                    throw PortableBackupFailure.SecurityViolation(
                        "Blob expanded beyond declared ZIP size ($total > $declaredSize)",
                    ).toException()
                }
                digest.update(buf, 0, read)
                out.write(buf, 0, read)
            }
        }
        return total to digest.digest().toHexLower()
    }
}

class JvmPortableStorageAccessPort(
    private val stagingRoot: File,
    private val streamBridge: PortableStreamBridge = FilePortableStreamBridge(),
) : PortableStorageAccessPort {

    override fun availableBytesForDestination(locator: String): Long =
        streamBridge.availableSpaceFor(locator, stagingRoot)

    override fun availableBytesForStaging(): Long =
        stagingRoot.apply { mkdirs() }.usableSpace.coerceAtLeast(0L)

    override fun inspectSource(sourceLocator: String): PortableSourceMetadata =
        streamBridge.inspectSource(sourceLocator)

    override suspend fun prepareStagingDirectory(operationId: String): String = withContext(Dispatchers.IO) {
        val dir = File(stagingRoot, operationId)
        dir.mkdirs()
        dir.canonicalPath
    }

    override suspend fun cleanupStagingDirectory(operationId: String) {
        withContext(Dispatchers.IO) {
            File(stagingRoot, operationId).deleteRecursively()
            File(stagingRoot, "export-$operationId").deleteRecursively()
        }
    }

    override suspend fun cleanupOrphanedStaging(activeOperationIds: Set<String>) {
        withContext(Dispatchers.IO) {
            if (!stagingRoot.exists()) return@withContext
            stagingRoot.listFiles()?.forEach { child ->
                val opId = child.name.removePrefix("export-")
                if (opId !in activeOperationIds) {
                    child.deleteRecursively()
                }
            }
        }
    }

    override suspend fun copyStagedFileToOwnedTarget(
        stagedPath: String,
        destinationRoot: String,
        relativeSubdir: String,
        desiredFileName: String,
        expectedSha256: String,
        expectedByteCount: Long,
    ): Result<PortableOwnedFileReceipt> = withContext(Dispatchers.IO) {
        try {
            val source = File(stagedPath)
            if (!source.isFile) {
                return@withContext Result.failure(PortableBackupFailure.SourceMissing(listOf(desiredFileName)).toException())
            }
            val rootDir = File(destinationRoot).canonicalFile
            val subDir = if (relativeSubdir.isBlank()) rootDir else File(rootDir, relativeSubdir).canonicalFile
            if (subDir.path != rootDir.path && !subDir.path.startsWith(rootDir.path + File.separator)) {
                return@withContext Result.failure(PortableBackupFailure.SecurityViolation("Target escapes destination root").toException())
            }
            subDir.mkdirs()

            val target = uniqueFile(subDir, desiredFileName)
            val tempTarget = File(subDir, ".${target.name}.${System.nanoTime()}.openyt-part")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            try {
                source.inputStream().buffered(BUFFER_BYTES).use { input ->
                    tempTarget.outputStream().buffered(BUFFER_BYTES).use { out ->
                        val buf = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val r = input.read(buf)
                            if (r <= 0) break
                            digest.update(buf, 0, r)
                            out.write(buf, 0, r)
                            total += r
                        }
                    }
                }
                val actualSha = digest.digest().toHexLower()
                if (total != expectedByteCount || !actualSha.equals(expectedSha256, ignoreCase = true)) {
                    tempTarget.delete()
                    return@withContext Result.failure(PortableBackupFailure.ChecksumMismatch(desiredFileName).toException())
                }
                val finalTarget = uniqueFile(subDir, desiredFileName)
                try {
                    Files.move(tempTarget.toPath(), finalTarget.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(tempTarget.toPath(), finalTarget.toPath())
                }
                val relative = rootDir.toPath().relativize(finalTarget.toPath()).toString().replace('\\', '/')
                Result.success(
                    PortableOwnedFileReceipt(
                        targetLocator = finalTarget.absolutePath,
                        relativeLocator = relative,
                        byteCount = total,
                        sha256 = actualSha,
                        lastModifiedEpochMillis = finalTarget.lastModified(),
                    ),
                )
            } catch (cancel: CancellationException) {
                tempTarget.delete()
                throw cancel
            } catch (error: Throwable) {
                tempTarget.delete()
                throw error
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Result.failure(mapStreamException(error))
        }
    }

    override suspend fun checkFileSha256(locator: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER_BYTES)
            streamBridge.openInputStream(locator).use { input ->
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    digest.update(buf, 0, r)
                }
            }
            digest.digest().toHexLower()
        }.getOrNull()
    }
}

private class CountingDigestInputStream(
    private val delegate: InputStream,
    private val digest: MessageDigest,
    private val onBytesRead: (Int) -> Unit,
) : InputStream() {
    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) {
            digest.update(b.toByte())
            onBytesRead(1)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = delegate.read(b, off, len)
        if (count > 0) {
            digest.update(b, off, count)
            onBytesRead(count)
        }
        return count
    }

    override fun close() {
        delegate.close()
    }
}

private fun uniqueFile(directory: File, desiredName: String): File {
    val initial = File(directory, desiredName)
    if (!initial.exists()) return initial
    val dot = desiredName.lastIndexOf('.')
    val base = if (dot > 0) desiredName.substring(0, dot) else desiredName
    val ext = if (dot > 0) desiredName.substring(dot) else ""
    var counter = 1
    while (true) {
        val candidate = File(directory, "$base ($counter)$ext")
        if (!candidate.exists()) return candidate
        counter++
    }
}

private fun mapStreamException(error: Throwable): Throwable = when (error) {
    is CancellationException -> error
    is PortableBackupException -> error
    is SecurityException -> PortableBackupFailure.PermissionDenied("Access denied").toException()
    is FileNotFoundException -> PortableBackupFailure.SourceMissing(listOf(error.message ?: "missing")).toException()
    else -> PortableBackupFailure.IoError(error.message ?: "Archive stream error").toException()
}

private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

private const val BUFFER_BYTES = 64 * 1024
