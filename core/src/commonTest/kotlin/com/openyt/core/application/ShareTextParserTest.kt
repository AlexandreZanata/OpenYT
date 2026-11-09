package com.openyt.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareTextParserTest {
    @Test
    fun extractsUrlFromShareText() {
        assertEquals(
            "https://youtu.be/abc123",
            ShareTextParser.firstUrl("Olha este vídeo: https://youtu.be/abc123 que legal"),
        )
    }

    @Test
    fun trimsTrailingPunctuation() {
        assertEquals(
            "https://example.com/watch?v=1",
            ShareTextParser.firstUrl("(https://example.com/watch?v=1)."),
        )
    }

    @Test
    fun ignoresTextWithoutHttpUrl() {
        assertNull(ShareTextParser.firstUrl("just some text"))
        assertNull(ShareTextParser.firstUrl("ftp://example.com/a"))
        assertNull(ShareTextParser.firstUrl(null))
        assertNull(ShareTextParser.firstUrl("   "))
    }

    @Test
    fun picksFirstHttpUrlWhenMultipleArePresent() {
        assertEquals(
            "https://example.com/first",
            ShareTextParser.firstUrl("https://example.com/first e https://example.com/second"),
        )
    }
}
