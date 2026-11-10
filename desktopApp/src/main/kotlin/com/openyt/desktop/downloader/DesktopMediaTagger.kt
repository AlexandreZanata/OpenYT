package com.openyt.desktop.downloader

import com.openyt.core.application.AudioMetadata
import com.openyt.core.application.MediaTagger
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes embedded metadata with the bundled FFmpeg. The original file is never
 * renamed; the tagged copy replaces it only after FFmpeg succeeds and the copy
 * is non-empty.
 */
class DesktopMediaTagger(
    private val locator: ExecutableLocator = ExecutableLocator(),
) : MediaTagger {
    override suspend fun writeTags(
        path: String,
        metadata: AudioMetadata,
        coverUrl: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val ffmpeg = locator.findFfmpeg() ?: return@withContext false
        val target = File(path)
        if (!target.isFile) return@withContext false

        val extension = target.extension.lowercase()
        val temp = File(target.parentFile, ".${target.name}.openyt-tag.${extension.ifBlank { "tmp" }}")
        var cover: File? = null
        try {
            val supportsCover = coverUrl != null && extension in COVER_EXTENSIONS
            if (supportsCover) {
                cover = runCatching { downloadCover(coverUrl!!) }.getOrNull()
            }

            val arguments = mutableListOf(ffmpeg, "-y", "-i", target.absolutePath)
            if (cover != null) {
                arguments += listOf("-i", cover.absolutePath)
            }
            arguments += listOf("-map_metadata", "0", "-metadata", "title=${metadata.title}")
            metadata.artist?.let { arguments += listOf("-metadata", "artist=$it") }
            if (cover != null) {
                arguments += listOf("-map", "0:a", "-map", "1:v", "-c", "copy")
                arguments += listOf("-disposition:v", "attached_pic")
            } else {
                arguments += listOf("-c", "copy")
            }
            arguments += temp.absolutePath

            val process = ProcessBuilder(arguments)
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext false
            }
            if (process.exitValue() != 0 || !temp.isFile || temp.length() == 0L) {
                return@withContext false
            }

            runCatching {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrThrow()
            true
        } catch (_: Exception) {
            false
        } finally {
            temp.delete()
            cover?.delete()
        }
    }

    private fun downloadCover(url: String): File {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "OpenYT")
        try {
            val file = File.createTempFile("openyt-cover-", ".jpg")
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            return file
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val COVER_EXTENSIONS = setOf("m4a", "mp4", "mov")
    }
}
