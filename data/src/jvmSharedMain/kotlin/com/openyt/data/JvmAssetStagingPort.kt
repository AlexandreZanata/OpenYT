package com.openyt.data

import com.openyt.core.application.AssetStagingPort
import com.openyt.core.application.DiskFullException
import com.openyt.core.application.StagedArtifact
import com.openyt.core.application.TruncatedCopyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class JvmAssetStagingPort(
    val assetsDirectory: File,
    var simulatedDiskSpace: Long? = null,
    var simulatedTruncationAtBytes: Long? = null,
    var simulatedRevokedPermission: Boolean = false,
) : AssetStagingPort {

    val stagingDirectory: File = File(assetsDirectory, ".staging")

    init {
        assetsDirectory.mkdirs()
        stagingDirectory.mkdirs()
    }

    override fun getAvailableSpace(): Long =
        simulatedDiskSpace ?: assetsDirectory.usableSpace

    override suspend fun prepareStagingDirectory(operationId: String): String = withContext(Dispatchers.IO) {
        val cleanId = sanitizeId(operationId)
        val dir = File(stagingDirectory, cleanId)
        dir.mkdirs()
        dir.absolutePath
    }

    override suspend fun stageAndHash(
        operationId: String,
        sourcePath: String,
        stagingPath: String,
        byteLimit: Long?,
        onBytesWritten: ((Long) -> Unit)?,
    ): Result<StagedArtifact> = withContext(Dispatchers.IO) {
        if (simulatedRevokedPermission) {
            return@withContext Result.failure(SecurityException("Storage permission revoked for $sourcePath"))
        }

        val sourceFile = File(sourcePath)
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return@withContext Result.failure(IllegalArgumentException("Source file does not exist: $sourcePath"))
        }

        val stagingFile = File(stagingPath)
        stagingFile.parentFile?.mkdirs()

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var totalBytes = 0L
        val maxSimulated = simulatedDiskSpace

        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(stagingFile).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break

                        // Truncation simulation injection
                        val toWrite = if (simulatedTruncationAtBytes != null) {
                            val remaining = (simulatedTruncationAtBytes!! - totalBytes).coerceAtLeast(0L).toInt()
                            read.coerceAtMost(remaining)
                        } else read

                        if (toWrite > 0) {
                            output.write(buffer, 0, toWrite)
                            digest.update(buffer, 0, toWrite)
                            totalBytes += toWrite
                            onBytesWritten?.invoke(totalBytes)
                        }

                        if (maxSimulated != null && totalBytes > maxSimulated) {
                            throw DiskFullException(maxSimulated, totalBytes)
                        }

                        if (simulatedTruncationAtBytes != null && totalBytes >= simulatedTruncationAtBytes!!) {
                            // Stop prematurely to simulate truncated copy
                            break
                        }
                    }
                }
            }

            if (byteLimit != null && totalBytes < byteLimit) {
                return@withContext Result.failure(TruncatedCopyException(byteLimit, totalBytes))
            }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            Result.success(StagedArtifact(stagingFile.absolutePath, totalBytes, sha256))
        } catch (e: Exception) {
            stagingFile.delete()
            Result.failure(e)
        }
    }

    override suspend fun promote(stagingPath: String, targetLocator: String): Result<String> = withContext(Dispatchers.IO) {
        val stagingFile = File(stagingPath)
        val targetFile = File(assetsDirectory, targetLocator)
        targetFile.parentFile?.mkdirs()

        try {
            try {
                Files.move(
                    stagingFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(
                    stagingFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            Result.success(targetFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun cleanupStaging(operationId: String) = withContext(Dispatchers.IO) {
        val cleanId = sanitizeId(operationId)
        val dir = File(stagingDirectory, cleanId)
        // Strict boundary check: cleanup cannot escape owned staging directory or touch source media
        if (dir.canonicalPath.startsWith(stagingDirectory.canonicalPath) && dir != stagingDirectory) {
            dir.deleteRecursively()
        }
        Unit
    }

    override suspend fun cleanupOrphanedStaging(activeOperationIds: Set<String>) = withContext(Dispatchers.IO) {
        val subdirs = stagingDirectory.listFiles() ?: return@withContext
        for (subdir in subdirs) {
            if (subdir.isDirectory && subdir.name !in activeOperationIds) {
                if (subdir.canonicalPath.startsWith(stagingDirectory.canonicalPath)) {
                    subdir.deleteRecursively()
                }
            }
        }
    }

    override fun fileExists(path: String): Boolean = File(path).exists()

    override fun fileSize(path: String): Long = File(path).length()

    override suspend fun computeSha256(path: String): String? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || !file.isFile) return@withContext null
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeId(id: String): String =
        id.replace('/', '_').replace('\\', '_').replace("..", "").trim()
}
