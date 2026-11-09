package com.openyt.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UrlValidatorTest {
    private val validator = UrlValidator()

    @Test
    fun acceptsHttpAndHttpsUrls() {
        assertEquals(
            UrlValidation.Valid("https://example.com/watch?v=1"),
            validator.validate("https://example.com/watch?v=1"),
        )
        assertEquals(
            UrlValidation.Valid("http://example.com/a.mp4"),
            validator.validate("http://example.com/a.mp4"),
        )
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(
            UrlValidation.Valid("https://example.com/video"),
            validator.validate("  https://example.com/video  "),
        )
    }

    @Test
    fun rejectsEmptyAndBlankInput() {
        assertIs<UrlValidation.Invalid>(validator.validate(""))
        assertIs<UrlValidation.Invalid>(validator.validate("   "))
    }

    @Test
    fun rejectsUnsupportedSchemes() {
        assertIs<UrlValidation.Invalid>(validator.validate("file:///etc/passwd"))
        assertIs<UrlValidation.Invalid>(validator.validate("ftp://example.com/a"))
        assertIs<UrlValidation.Invalid>(validator.validate("javascript:alert(1)"))
    }

    @Test
    fun rejectsCredentialsInUrl() {
        assertIs<UrlValidation.Invalid>(validator.validate("https://user:pass@example.com/a"))
        assertIs<UrlValidation.Invalid>(validator.validate("https://user@example.com/a"))
    }

    @Test
    fun rejectsMalformedAndRelativeUrls() {
        assertIs<UrlValidation.Invalid>(validator.validate("example.com/video"))
        assertIs<UrlValidation.Invalid>(validator.validate("https://"))
        assertIs<UrlValidation.Invalid>(validator.validate("https://exa mple.com/a"))
    }

    @Test
    fun rejectsOverlongUrls() {
        assertIs<UrlValidation.Invalid>(validator.validate("https://example.com/" + "a".repeat(2048)))
    }
}
