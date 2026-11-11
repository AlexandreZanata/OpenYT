package com.openyt.media.ffmpeg

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
class FfmpegMediaTagger(
    private val executable: suspend () -> String?,
    private val processEnvironment: () -> Map<String, String> = { emptyMap() },
) : MediaTagger {
    override suspend fun writeTags(
        path: String,
        metadata: AudioMetadata,
        coverUrl: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val ffmpeg = (try { executable() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }) ?: return@withContext false
        val target = File(path)
        if (!target.isFile) return@withContext false

        val extension = target.extension.lowercase()
        val temp = File(target.parentFile, ".${target.name}.openyt-tag-${java.util.UUID.randomUUID()}.${extension.ifBlank { "tmp" }}")
        var cover: File? = null
        try {
            val supportsCover = coverUrl != null && extension in COVER_EXTENSIONS
            if (supportsCover) {
                cover = runCatching { downloadCover(coverUrl!!) }.getOrNull()
            }

            val arguments = mutableListOf(ffmpeg, "-n", "-i", target.absolutePath)
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
                .apply { environment().putAll(processEnvironment()) }
                .start()
            val reader = kotlin.concurrent.thread(isDaemon = true) { runCatching { process.inputStream.bufferedReader().forEachLine {} } }
            val finished = try { kotlinx.coroutines.runInterruptible { process.waitFor(120, TimeUnit.SECONDS) } }
                finally { if (process.isAlive) process.destroyForcibly() }
            reader.join(1_000)
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            temp.delete()
            cover?.delete()
        }
    }

    private fun downloadCover(url: String): File {
        val uri = URI(url)
        require(uri.scheme in setOf("https", "http") && uri.userInfo == null)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "OpenYT")
        val file = File.createTempFile("openyt-cover-", ".jpg")
        try {
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 10_000_000)
                        output.write(buffer, 0, count)
                    }
                }
            }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val COVER_EXTENSIONS = setOf("m4a", "mp4", "mov", "mp3", "flac")
    }
}
