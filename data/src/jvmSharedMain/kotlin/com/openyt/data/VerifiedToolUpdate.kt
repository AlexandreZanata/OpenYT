package com.openyt.data

import com.openyt.core.application.ToolUpdater
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Official stable yt-dlp only; downloaded bytes are checked before becoming executable. */
class VerifiedToolUpdate(
    private val assetName: String,
    private val target: suspend () -> File,
) : ToolUpdater {
    override suspend fun update(): Boolean = withContext(Dispatchers.IO) {
        var temporary: File? = null
        try {
            val release = Json.parseToJsonElement(read("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest", 2_000_000)).jsonObject
            val version = release["tag_name"]?.jsonPrimitive?.content ?: return@withContext false
            if (!version.matches(Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}"))) return@withContext false
            val base = "https://github.com/yt-dlp/yt-dlp/releases/download/$version/"
            val checksums = read(base + "SHA2-256SUMS", 1_000_000)
            val expected = VerifiedReleaseDownloader.checksumFor(checksums, assetName) ?: return@withContext false
            val destination = target()
            destination.parentFile.mkdirs()
            temporary = File.createTempFile(".yt-dlp-", ".part", destination.parentFile)
            connection(base + assetName).let { connection ->
                try {
                    check(connection.responseCode in 200..299)
                    connection.inputStream.use { input -> temporary.outputStream().use { input.copyTo(it) } }
                } finally { connection.disconnect() }
            }
            check(VerifiedReleaseDownloader.matchesChecksum(temporary, expected))
            check(temporary.setExecutable(true) || System.getProperty("os.name").contains("Windows", true))
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            File(destination.parentFile, "${destination.name}.sha256").writeText(expected)
            true
        } catch (_: Exception) { false }
        finally { temporary?.delete() }
    }

    private fun read(url: String, limit: Int): String = connection(url).let { connection ->
        try {
            check(connection.responseCode in 200..299)
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= limit)
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }

    private fun connection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 120_000
        setRequestProperty("User-Agent", "OpenYT")
    }
}
