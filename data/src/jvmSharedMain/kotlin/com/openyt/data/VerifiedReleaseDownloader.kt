package com.openyt.data

import com.openyt.core.application.UpdateInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installs only assets listed in a manifest signed by the production release key. */
class VerifiedReleaseDownloader {
    suspend fun download(update: UpdateInfo, suffix: String, directory: File): File? = withContext(Dispatchers.IO) {
        val asset = update.assets.singleOrNull { it.name.endsWith(suffix, true) } ?: return@withContext null
        val manifest = update.assets.singleOrNull { it.name == "SHA256SUMS.txt" } ?: return@withContext null
        val signature = update.assets.singleOrNull { it.name == "SHA256SUMS.txt.sig" } ?: return@withContext null
        if (!safeName(asset.name)) return@withContext null
        directory.mkdirs()
        val temporary = File.createTempFile(".openyt-verified-", ".part", directory)
        try {
            val checksums = fetch(manifest.url, 1_048_576)
            val signed = fetch(signature.url, 16_384)
            if (!verifyManifest(checksums, signed)) return@withContext null
            val expected = checksumFor(checksums.toString(Charsets.UTF_8), asset.name) ?: return@withContext null
            connection(asset.url).let { connection ->
                try {
                    check(connection.responseCode in 200..299)
                    connection.inputStream.use { input -> temporary.outputStream().use { input.copyTo(it) } }
                } finally { connection.disconnect() }
            }
            if (!matchesChecksum(temporary, expected)) return@withContext null
            val output = File(directory, asset.name)
            java.nio.file.Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            output
        } catch (_: Exception) {
            null
        } finally { temporary.delete() }
    }

    private fun fetch(url: String, limit: Int): ByteArray = connection(url).let { connection ->
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
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    private fun connection(url: String): HttpURLConnection {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null)
        return (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "OpenYT")
        }
    }

    companion object {
        fun safeName(name: String): Boolean = name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,200}")) && !name.contains("..")

        fun checksumFor(manifest: String, name: String): String? {
            val entries = manifest.lineSequence().mapNotNull {
                Regex("^([a-fA-F0-9]{64}) [ *](.+)$").matchEntire(it)?.let { match -> match.groupValues[2] to match.groupValues[1].lowercase() }
            }.filter { it.first == name }.toList()
            return entries.singleOrNull()?.second
        }

        fun matchesChecksum(file: File, expected: String): Boolean {
            if (!expected.matches(Regex("[a-fA-F0-9]{64}")) || !file.isFile || file.length() == 0L) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)
        }

        fun verifyManifest(manifest: ByteArray, signature: ByteArray, certificate: ByteArray = Base64.getDecoder().decode(RELEASE_CERTIFICATE)): Boolean = runCatching {
            val key = CertificateFactory.getInstance("X.509").generateCertificate(certificate.inputStream()).publicKey
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(manifest)
                verify(signature)
            }
        }.getOrDefault(false)
    }
}
