package com.openyt.data

import com.openyt.core.application.FolderScanner
import com.openyt.core.application.ScannedMedia
import com.openyt.core.domain.MediaKind
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileFolderScanner(
    private val maxDepth: Int = 5,
    private val maxFiles: Int = 10_000,
) : FolderScanner {
    override suspend fun scan(directory: String): List<ScannedMedia> = withContext(Dispatchers.IO) {
        val root = File(directory)
        if (!root.isDirectory) return@withContext emptyList()

        root.walkTopDown()
            .maxDepth(maxDepth)
            .onEnter { file -> !file.name.startsWith(".") && file.name != "node_modules" }
            .filter { it.isFile }
            .take(maxFiles)
            .mapNotNull { file ->
                val kind = kindFor(file.extension.lowercase()) ?: return@mapNotNull null
                ScannedMedia(
                    path = file.absolutePath,
                    title = file.nameWithoutExtension,
                    kind = kind,
                    sizeBytes = file.length(),
                    modifiedAtEpochMillis = file.lastModified(),
                )
            }
            .sortedBy { it.path }
            .toList()
    }

    private fun kindFor(extension: String): MediaKind? = when (extension) {
        "mp4", "mkv", "webm", "mov", "avi", "m4v", "ts" -> MediaKind.VIDEO
        "m4a", "mp3", "opus", "ogg", "flac", "wav", "aac", "wma" -> MediaKind.AUDIO
        else -> null
    }
}
