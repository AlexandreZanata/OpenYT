package com.openyt.desktop

import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDropParserTest {
    @Test
    fun extractsUrlFromDroppedText() {
        assertEquals(
            "https://archive.org/details/BigBuckBunny_124",
            extractDroppedUrl(StringSelection("Veja https://archive.org/details/BigBuckBunny_124")),
        )
    }

    @Test
    fun ignoresDroppedTextWithoutHttpUrl() {
        assertNull(extractDroppedUrl(StringSelection("no link here")))
        assertNull(extractDroppedUrl(StringSelection("file:///tmp/a.mp4")))
    }
}
