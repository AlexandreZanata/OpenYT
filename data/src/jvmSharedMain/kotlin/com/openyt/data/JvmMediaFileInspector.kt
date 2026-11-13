package com.openyt.data

import com.openyt.core.application.FileMetadata
import com.openyt.core.application.MediaFileInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * JVM/Android file inspector performing file checks and streaming SHA-256 digests
 * on [Dispatchers.IO].
 */
class JvmMediaFileInspector : MediaFileInspector {
    override fun exists(path: String): Boolean = runCatching {
        File(path).exists()
    }.getOrDefault(false)

    override fun inspect(path: String): FileMetadata? = runCatching {
        val file = File(path)
        if (!file.exists() || !file.isFile) null
        else FileMetadata(
            sizeBytes = file.length(),
            lastModifiedEpochMillis = file.lastModified(),
        )
    }.getOrNull()

    override suspend fun computeSha256(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            if (!file.exists() || !file.isFile) return@withContext null
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            FileInputStream(file).use { stream ->
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
