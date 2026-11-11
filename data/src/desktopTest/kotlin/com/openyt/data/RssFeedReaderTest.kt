package com.openyt.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RssFeedReaderTest {
    @Test
    fun readsRssAndAtomEnclosures() {
        val rss = RssFeedReader.parse("""<rss><channel><item><guid>1</guid><title>Ação</title><enclosure url="https://example.com/1.mp3"/></item></channel></rss>""")
        assertEquals("Ação", rss.single().title)
        assertEquals("1", rss.single().id)
        val atom = RssFeedReader.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>2</id><title>Episode</title><link rel="enclosure" href="https://example.com/2.m4a"/></entry></feed>""")
        assertEquals("https://example.com/2.m4a", atom.single().url)
    }
    @Test
    fun rejectsExternalEntitiesAndLocalFileEnclosures() {
        assertFailsWith<IllegalArgumentException> { RssFeedReader.parse("""<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss>&x;</rss>""") }
        assertEquals(emptyList(), RssFeedReader.parse("""<rss><channel><item><enclosure url="file:///etc/passwd"/></item></channel></rss>"""))
    }
}
