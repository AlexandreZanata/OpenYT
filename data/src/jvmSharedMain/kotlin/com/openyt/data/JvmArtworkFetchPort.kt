package com.openyt.data

import com.openyt.core.application.ArtworkFetchPort
import com.openyt.core.application.FetchedArtworkMetadata
import com.openyt.core.application.SafeUrlSanitizer
import com.openyt.core.domain.AssetOperationFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * JVM/Android implementation of [ArtworkFetchPort] using [HttpURLConnection] with
 * strict redirect validation, byte bounding, cancellation, and image format verification.
 */
class JvmArtworkFetchPort(
    var simulatedNetworkOffline: Boolean = false,
    var simulatedRedirectLoop: Boolean = false,
    var simulatedExpiredUrl: Boolean = false,
    var connectionFactory: ((String) -> HttpURLConnection)? = null,
) : ArtworkFetchPort {

    override suspend fun fetchArtworkTo(
        url: String,
        destinationPath: String,
        maxSizeBytes: Long,
    ): Result<FetchedArtworkMetadata> = withContext(Dispatchers.IO) {
        val destFile = File(destinationPath)
        try {
            if (simulatedNetworkOffline) {
                throw AssetOperationFailure.NetworkOffline().toException()
            }
            if (simulatedRedirectLoop) {
                throw AssetOperationFailure.RedirectLoop(url, 6).toException()
            }
            if (simulatedExpiredUrl) {
                throw AssetOperationFailure.ExpiredUrl(url, 404).toException()
            }

            destFile.parentFile?.mkdirs()

            var currentUrl = SafeUrlSanitizer.stripCredentials(url)
            val visitedUrls = mutableSetOf<String>()
            var redirectCount = 0
            var finalConnection: HttpURLConnection? = null

            // Manual redirect loop handling (max 5 redirects)
            while (true) {
                if (!coroutineContext.isActive) {
                    throw CancellationException("Artwork fetch canceled")
                }

                if (!visitedUrls.add(currentUrl)) {
                    throw AssetOperationFailure.RedirectLoop(currentUrl, redirectCount).toException()
                }

                val conn = openConnection(currentUrl)
                val responseCode = try {
                    conn.responseCode
                } catch (e: UnknownHostException) {
                    conn.disconnect()
                    throw AssetOperationFailure.NetworkOffline("Unknown host: ${e.message}").toException()
                } catch (e: ConnectException) {
                    conn.disconnect()
                    throw AssetOperationFailure.NetworkOffline("Connection failed: ${e.message}").toException()
                } catch (e: SocketTimeoutException) {
                    conn.disconnect()
                    throw AssetOperationFailure.NetworkOffline("Connection timed out: ${e.message}").toException()
                } catch (e: SocketException) {
                    conn.disconnect()
                    throw AssetOperationFailure.NetworkOffline("Socket error: ${e.message}").toException()
                }

                if (responseCode in REDIRECT_CODES) {
                    redirectCount++
                    if (redirectCount > MAX_REDIRECTS) {
                        conn.disconnect()
                        throw AssetOperationFailure.RedirectLoop(currentUrl, redirectCount).toException()
                    }

                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) {
                        throw AssetOperationFailure.VerificationFailed("Redirect response missing Location header").toException()
                    }

                    val nextUri = URI(currentUrl).resolve(location)
                    val nextScheme = nextUri.scheme?.lowercase()
                    if (nextScheme != "http" && nextScheme != "https") {
                        throw AssetOperationFailure.VerificationFailed("Disallowed redirect scheme: $nextScheme").toException()
                    }

                    currentUrl = SafeUrlSanitizer.stripCredentials(nextUri.toString())
                    continue
                }

                if (responseCode == 404 || responseCode == 410 || responseCode == 403) {
                    conn.disconnect()
                    throw AssetOperationFailure.ExpiredUrl(currentUrl, responseCode).toException()
                }

                if (responseCode !in 200..299) {
                    conn.disconnect()
                    throw AssetOperationFailure.Unexpected("HTTP $responseCode: ${conn.responseMessage}").toException()
                }

                finalConnection = conn
                break
            }

            val connection = checkNotNull(finalConnection)
            try {
                // Stream to destination file with byte limiting and digest computation
                val digest = MessageDigest.getInstance("SHA-256")
                val headerBytes = ByteArray(16)
                var headerRead = 0
                var totalBytes = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (!coroutineContext.isActive) {
                                throw CancellationException("Artwork download canceled")
                            }

                            val count = input.read(buffer)
                            if (count < 0) break

                            if (headerRead < 16) {
                                val toCopy = minOf(16 - headerRead, count)
                                System.arraycopy(buffer, 0, headerBytes, headerRead, toCopy)
                                headerRead += toCopy
                            }

                            totalBytes += count
                            if (totalBytes > maxSizeBytes) {
                                throw AssetOperationFailure.OversizedAsset(totalBytes, maxSizeBytes).toException()
                            }

                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }

                if (totalBytes == 0L) {
                    throw AssetOperationFailure.CorruptImage("Fetched artwork is empty (0 bytes)").toException()
                }

                // Verify image format magic numbers
                val imageFormat = detectImageFormat(headerBytes, headerRead)
                    ?: throw AssetOperationFailure.CorruptImage("Unsupported or corrupt image format").toException()

                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                Result.success(
                    FetchedArtworkMetadata(
                        byteCount = totalBytes,
                        mimeType = imageFormat.mimeType,
                        formatExtension = imageFormat.extension,
                        sha256 = sha256,
                    )
                )
            } finally {
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            destFile.delete()
            throw e
        } catch (t: Throwable) {
            destFile.delete()
            Result.failure(t)
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        connectionFactory?.let { return it(url) }
        val uri = URI(url)
        val conn = uri.toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "OpenYT")
        return conn
    }

    private fun detectImageFormat(header: ByteArray, count: Int): ImageFormat? {
        if (count < 3) return null

        // JPEG: FF D8 FF
        if ((header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xFF) == 0xD8 &&
            (header[2].toInt() and 0xFF) == 0xFF
        ) {
            return ImageFormat("image/jpeg", "jpg")
        }

        // PNG: 89 50 4E 47
        if (count >= 4 &&
            (header[0].toInt() and 0xFF) == 0x89 &&
            (header[1].toInt() and 0xFF) == 0x50 &&
            (header[2].toInt() and 0xFF) == 0x4E &&
            (header[3].toInt() and 0xFF) == 0x47
        ) {
            return ImageFormat("image/png", "png")
        }

        // GIF: GIF87a or GIF89a
        if (count >= 4 &&
            (header[0].toInt() and 0xFF) == 0x47 &&
            (header[1].toInt() and 0xFF) == 0x49 &&
            (header[2].toInt() and 0xFF) == 0x46 &&
            (header[3].toInt() and 0xFF) == 0x38
        ) {
            return ImageFormat("image/gif", "gif")
        }

        // WEBP: RIFF....WEBP
        if (count >= 12 &&
            (header[0].toInt() and 0xFF) == 0x52 && // R
            (header[1].toInt() and 0xFF) == 0x49 && // I
            (header[2].toInt() and 0xFF) == 0x46 && // F
            (header[3].toInt() and 0xFF) == 0x46 && // F
            (header[8].toInt() and 0xFF) == 0x57 && // W
            (header[9].toInt() and 0xFF) == 0x45 && // E
            (header[10].toInt() and 0xFF) == 0x42 && // B
            (header[11].toInt() and 0xFF) == 0x50    // P
        ) {
            return ImageFormat("image/webp", "webp")
        }

        // BMP: BM
        if ((header[0].toInt() and 0xFF) == 0x42 &&
            (header[1].toInt() and 0xFF) == 0x4D
        ) {
            return ImageFormat("image/bmp", "bmp")
        }

        return null
    }

    private data class ImageFormat(val mimeType: String, val extension: String)

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
