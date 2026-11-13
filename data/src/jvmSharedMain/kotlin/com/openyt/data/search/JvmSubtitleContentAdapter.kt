package com.openyt.data.search

import com.openyt.core.application.FileMetadata
import com.openyt.core.application.SubtitleContentPort
import com.openyt.data.JvmMediaFileInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

class JvmSubtitleContentAdapter(
    private val inspector: JvmMediaFileInspector = JvmMediaFileInspector(),
) : SubtitleContentPort {

    override fun exists(path: String): Boolean = inspector.exists(path)

    override fun inspect(path: String): FileMetadata? = inspector.inspect(path)

    override suspend fun readContent(path: String, maxBytes: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            if (!file.exists() || !file.isFile || file.length() > maxBytes) return@withContext null
            file.readText(StandardCharsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun computeDigest(path: String): String? = inspector.computeSha256(path)
}
