package com.openyt.data

import com.openyt.core.domain.AssetOperationException
import com.openyt.core.domain.AssetOperationFailure
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmArtworkFetchPortTest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0

    // Valid 1x1 JPEG bytes (starts with FF D8 FF)
    private val validJpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
        0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x48,
    )

    // Valid PNG magic bytes (89 50 4E 47 0D 0A 1A 0A)
    private val validPng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00,
        0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port

        server.createContext("/valid.jpg") { exchange ->
            exchange.responseHeaders.set("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, validJpeg.size.toLong())
            exchange.responseBody.use { it.write(validJpeg) }
        }

        server.createContext("/valid.png") { exchange ->
            exchange.responseHeaders.set("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, validPng.size.toLong())
            exchange.responseBody.use { it.write(validPng) }
        }

        server.createContext("/redir1") { exchange ->
            exchange.responseHeaders.set("Location", "http://127.0.0.1:$serverPort/valid.jpg")
            exchange.sendResponseHeaders(302, -1)
        }

        server.createContext("/loopA") { exchange ->
            exchange.responseHeaders.set("Location", "http://127.0.0.1:$serverPort/loopB")
            exchange.sendResponseHeaders(302, -1)
        }

        server.createContext("/loopB") { exchange ->
            exchange.responseHeaders.set("Location", "http://127.0.0.1:$serverPort/loopA")
            exchange.sendResponseHeaders(302, -1)
        }

        server.createContext("/disallowedScheme") { exchange ->
            exchange.responseHeaders.set("Location", "file:///etc/passwd")
            exchange.sendResponseHeaders(302, -1)
        }

        server.createContext("/expired404") { exchange ->
            exchange.sendResponseHeaders(404, -1)
        }

        server.createContext("/expired410") { exchange ->
            exchange.sendResponseHeaders(410, -1)
        }

        server.createContext("/large.jpg") { exchange ->
            val large = validJpeg + ByteArray(50_000) { 0x41 }
            exchange.responseHeaders.set("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, large.size.toLong())
            exchange.responseBody.use { it.write(large) }
        }

        server.createContext("/corrupt.jpg") { exchange ->
            val text = "<html><body>Not an image</body></html>".toByteArray()
            exchange.sendResponseHeaders(200, text.size.toLong())
            exchange.responseBody.use { it.write(text) }
        }

        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun fetchesValidJpegSuccessfully(): Unit = runBlocking {
        val dest = File.createTempFile("openyt-fetch-jpeg-", ".part")
        dest.delete()
        try {
            val port = JvmArtworkFetchPort()
            val result = port.fetchArtworkTo("http://127.0.0.1:$serverPort/valid.jpg", dest.absolutePath)

            assertTrue(result.isSuccess)
            val meta = result.getOrThrow()
            assertEquals("image/jpeg", meta.mimeType)
            assertEquals("jpg", meta.formatExtension)
            assertEquals(validJpeg.size.toLong(), meta.byteCount)
            assertTrue(dest.exists())
            assertEquals(validJpeg.size.toLong(), dest.length())
        } finally {
            dest.delete()
        }
    }

    @Test
    fun fetchesValidPngSuccessfully(): Unit = runBlocking {
        val dest = File.createTempFile("openyt-fetch-png-", ".part")
        dest.delete()
        try {
            val port = JvmArtworkFetchPort()
            val result = port.fetchArtworkTo("http://127.0.0.1:$serverPort/valid.png", dest.absolutePath)

            assertTrue(result.isSuccess)
            val meta = result.getOrThrow()
            assertEquals("image/png", meta.mimeType)
            assertEquals("png", meta.formatExtension)
            assertEquals(validPng.size.toLong(), meta.byteCount)
            assertTrue(dest.exists())
        } finally {
            dest.delete()
        }
    }

    @Test
    fun followsRedirectWithinLimit(): Unit = runBlocking {
        val dest = File.createTempFile("openyt-fetch-redir-", ".part")
        dest.delete()
        try {
            val port = JvmArtworkFetchPort()
            val result = port.fetchArtworkTo("http://127.0.0.1:$serverPort/redir1", dest.absolutePath)

            assertTrue(result.isSuccess)
            val meta = result.getOrThrow()
            assertEquals("jpg", meta.formatExtension)
            assertTrue(dest.exists())
        } finally {
            dest.delete()
        }
    }

    @Test
    fun detectsRedirectLoopAndFails(): Unit = runBlocking {
        val dest = File.createTempFile("openyt-fetch-loop-", ".part")
        dest.delete()
        try {
            val port = JvmArtworkFetchPort()
            val result = port.fetchArtworkTo("http://127.0.0.1:$serverPort/loopA", dest.absolutePath)

            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            assertIs<AssetOperationException>(ex)
            assertIs<AssetOperationFailure.RedirectLoop>(ex.failure)
            assertFalse(dest.exists(), "Destination file must be deleted on redirect loop failure")
        } finally {
            dest.delete()
        }
    }

    @Test
    fun rejectsDisallowedRedirectScheme(): Unit = runBlocking {
        val dest = File.createTempFile("openyt-fetch-scheme-", ".part")
        dest.delete()
        try {
            val port = JvmArtworkFetchPort()
            val result = port.fetchArtworkTo("http://127.0.0.1:$serverPort/disallowedScheme", dest.absolutePath)

            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            assertIs<AssetOperationException>(ex)
            assertIs<AssetOperationFailure.VerificationFailed>(ex.failure)
            assertFalse(dest.exists())
        } finally {
            dest.delete()
        }
    }

    @Test
    fun expiredUrlReturnsStructuredFailure(): Unit = runBlocking {
        val dest = File.createTempFile("openyt-fetch-expired-", ".part")
        dest.delete()
        try {
            val port = JvmArtworkFetchPort()
            val result404 = port.fetchArtworkTo("http://127.0.0.1:$serverPort/expired404", dest.absolutePath)
            assertTrue(result404.isFailure)
            val ex404 = result404.exceptionOrNull()
            assertIs<AssetOperationException>(ex404)
            val expired404 = assertIs<AssetOperationFailure.ExpiredUrl>(ex404.failure)
            assertEquals(404, expired404.statusCode)

            val result410 = port.fetchArtworkTo("http://127.0.0.1:$serverPort/expired410", dest.absolutePath)
            assertTrue(result410.isFailure)
            val ex410 = result410.exceptionOrNull()
            assertIs<AssetOperationException>(ex410)
            val expired410 = assertIs<AssetOperationFailure.ExpiredUrl>(ex410.failure)
            assertEquals(410, expired410.statusCode)
        } finally {
            dest.delete()
        }
    }

    @Test
    fun oversizedImageAbortsAndDeletesFile(): Unit = runBlocking {
        val dest = File.createTempFile("openyt-fetch-oversized-", ".part")
        dest.delete()
        try {
            val port = JvmArtworkFetchPort()
            // 50KB payload exceeds 5KB limit
            val result = port.fetchArtworkTo("http://127.0.0.1:$serverPort/large.jpg", dest.absolutePath, maxSizeBytes = 5000L)

            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            assertIs<AssetOperationException>(ex)
            assertIs<AssetOperationFailure.OversizedAsset>(ex.failure)
            assertFalse(dest.exists(), "Destination file must be deleted when byte limit is exceeded")
        } finally {
            dest.delete()
        }
    }

    @Test
    fun corruptImageWithoutValidMagicIsRejected(): Unit = runBlocking {
        val dest = File.createTempFile("openyt-fetch-corrupt-", ".part")
        dest.delete()
        try {
            val port = JvmArtworkFetchPort()
            val result = port.fetchArtworkTo("http://127.0.0.1:$serverPort/corrupt.jpg", dest.absolutePath)

            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            assertIs<AssetOperationException>(ex)
            assertIs<AssetOperationFailure.CorruptImage>(ex.failure)
            assertFalse(dest.exists(), "Destination file must be deleted when image magic is corrupt")
        } finally {
            dest.delete()
        }
    }

    @Test
    fun offlineHostReturnsStructuredNetworkOffline(): Unit = runBlocking {
        val dest = File.createTempFile("openyt-fetch-offline-", ".part")
        dest.delete()
        try {
            val port = JvmArtworkFetchPort(simulatedNetworkOffline = true)
            val result = port.fetchArtworkTo("http://127.0.0.1:$serverPort/valid.jpg", dest.absolutePath)

            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            assertIs<AssetOperationException>(ex)
            assertIs<AssetOperationFailure.NetworkOffline>(ex.failure)
            assertFalse(dest.exists())
        } finally {
            dest.delete()
        }
    }
}
