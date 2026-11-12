package com.openyt.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeUrlSanitizerTest {

    @Test
    fun validatesHttpAndHttpsSchemes() {
        val http = SafeUrlSanitizer.validateArtworkUrl("http://example.com/art.jpg")
        assertTrue(http.isSuccess)
        assertEquals("http://example.com/art.jpg", http.getOrNull())

        val https = SafeUrlSanitizer.validateArtworkUrl("https://example.com/art.png")
        assertTrue(https.isSuccess)
        assertEquals("https://example.com/art.png", https.getOrNull())
    }

    @Test
    fun rejectsDisallowedSchemes() {
        val file = SafeUrlSanitizer.validateArtworkUrl("file:///etc/passwd")
        assertTrue(file.isFailure)

        val ftp = SafeUrlSanitizer.validateArtworkUrl("ftp://example.com/art.jpg")
        assertTrue(ftp.isFailure)

        val js = SafeUrlSanitizer.validateArtworkUrl("javascript:alert(1)")
        assertTrue(js.isFailure)

        val blank = SafeUrlSanitizer.validateArtworkUrl("   ")
        assertTrue(blank.isFailure)
    }

    @Test
    fun detectsAndStripsCredentials() {
        val credUrl = "https://admin:secret123@cdn.example.com/artwork/cover.webp?token=xyz"
        assertTrue(SafeUrlSanitizer.hasCredentials(credUrl))

        val clean = SafeUrlSanitizer.stripCredentials(credUrl)
        assertEquals("https://cdn.example.com/artwork/cover.webp?token=xyz", clean)
        assertFalse(SafeUrlSanitizer.hasCredentials(clean))

        val validated = SafeUrlSanitizer.validateArtworkUrl(credUrl)
        assertTrue(validated.isSuccess)
        assertEquals("https://cdn.example.com/artwork/cover.webp?token=xyz", validated.getOrNull())
    }

    @Test
    fun masksCredentialsForDiagnosticsAndLogs() {
        val credUrl = "https://user:password@img.youtube.com/vi/123/hqdefault.jpg"
        val masked = SafeUrlSanitizer.maskCredentialsForLogs(credUrl)
        assertEquals("https://***:***@img.youtube.com/vi/123/hqdefault.jpg", masked)

        val cleanUrl = "https://img.youtube.com/vi/123/hqdefault.jpg"
        assertEquals(cleanUrl, SafeUrlSanitizer.maskCredentialsForLogs(cleanUrl))
    }
}
