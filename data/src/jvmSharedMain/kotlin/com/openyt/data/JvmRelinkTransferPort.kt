package com.openyt.data

import com.openyt.core.application.PortableOwnedFileReceipt
import com.openyt.core.application.RelinkFailure
import com.openyt.core.application.RelinkTransferPort
import com.openyt.core.domain.MediaRevision
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class JvmRelinkTransferPort(
    private val stagingRoot: File,
    private val streamBridge: PortableStreamBridge = FilePortableStreamBridge(),
) : RelinkTransferPort {

    private val storageAccess = JvmPortableStorageAccessPort(stagingRoot = stagingRoot, streamBridge = streamBridge)

    override suspend fun inspectLocalTarget(locator: String): MediaRevision? = withContext(Dispatchers.IO) {
        val file = File(locator)
        if (!file.exists() || !file.isFile || !file.canRead()) return@withContext null
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val r = input.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
        }
        MediaRevision(
            sizeBytes = file.length(),
            lastModifiedEpochMillis = file.lastModified(),
            contentSha256 = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    override suspend fun copyScopedSourceToOwned(
        operationId: String,
        sourceLocator: String,
        destinationRoot: String,
        desiredFileName: String,
        expectedSizeBytes: Long,
        expectedSha256: String?,
        storageReserveBytes: Long,
    ): Result<PortableOwnedFileReceipt> = withContext(Dispatchers.IO) {
        val destDir = File(destinationRoot).apply { mkdirs() }
        val available = storageAccess.availableBytesForDestination(destDir.absolutePath)
        if (expectedSizeBytes + storageReserveBytes > available) {
            return@withContext Result.failure(
                RelinkFailure.InsufficientSpace(
                    requiredBytes = expectedSizeBytes,
                    availableBytes = available,
                    reserveBytes = storageReserveBytes,
                ).toException(),
            )
        }

        val opStageDir = File(stagingRoot, "relink-$operationId").apply { mkdirs() }
        val safeName = sanitizeRecoveryFileName(desiredFileName)
        val stagedFile = File(opStageDir, "payload.bin")

        try {
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val buf = ByteArray(64 * 1024)
            streamBridge.openInputStream(sourceLocator).use { input ->
                stagedFile.outputStream().buffered(64 * 1024).use { out ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val r = input.read(buf)
                        if (r <= 0) break
                        md.update(buf, 0, r)
                        out.write(buf, 0, r)
                        total += r
                    }
                }
            }
            val actualSha = md.digest().joinToString("") { "%02x".format(it) }
            if (expectedSizeBytes > 0L && total != expectedSizeBytes) {
                return@withContext Result.failure(
                    RelinkFailure.StalePlan("Copied SAF payload size differed ($total != $expectedSizeBytes)").toException(),
                )
            }
            if (!expectedSha256.isNullOrBlank() && !actualSha.equals(expectedSha256, ignoreCase = true)) {
                return@withContext Result.failure(
                    RelinkFailure.StalePlan("Copied SAF payload checksum changed").toException(),
                )
            }

            storageAccess.copyStagedFileToOwnedTarget(
                stagedPath = stagedFile.absolutePath,
                destinationRoot = destDir.absolutePath,
                relativeSubdir = "recovered",
                desiredFileName = safeName,
                expectedSha256 = actualSha,
                expectedByteCount = total,
            )
        } catch (cancel: CancellationException) {
            opStageDir.deleteRecursively()
            throw cancel
        } catch (_: SecurityException) {
            opStageDir.deleteRecursively()
            Result.failure(RelinkFailure.PermissionDenied("Permission denied reading scoped recovery source").toException())
        } catch (_: FileNotFoundException) {
            opStageDir.deleteRecursively()
            Result.failure(RelinkFailure.SourceMissing("Selected recovery source no longer exists").toException())
        } catch (error: Throwable) {
            opStageDir.deleteRecursively()
            Result.failure(
                if (error is com.openyt.core.application.RelinkException) {
                    error
                } else {
                    RelinkFailure.IoError(error.message ?: "SAF recovery copy failed").toException()
                },
            )
        }
    }

    override suspend fun cleanupOperationStaging(operationId: String) {
        withContext(Dispatchers.IO) {
            File(stagingRoot, "relink-$operationId").deleteRecursively()
        }
    }

    private fun sanitizeRecoveryFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1F:*?\"<>|]"), "_")
            .trim()
        return if (base.isBlank() || base == "." || base == "..") "recovered-media.bin" else base.take(180)
    }
}
