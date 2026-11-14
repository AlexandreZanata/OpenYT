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

    @Test
    fun durationAndSizeMetadataAreBoundedAndMalformedValuesRemainUnknown() {
        fun episode(duration: String, size: String = "123") = RssFeedReader.parse("""<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><item><enclosure url="https://example.com/episode.mp3" length="$size"/><itunes:duration>$duration</itunes:duration></item></channel></rss>""").single()
        assertEquals(3723L, episode("1:02:03").durationSeconds)
        assertEquals(123L, episode("42").sizeBytes)
        for (duration in listOf("1:60", "-1", "9:99:99", "999999999999999999999999:00", "9223372036854775807:59")) assertEquals(null, episode(duration).durationSeconds)
        assertEquals(null, episode("0", "-1").sizeBytes)
    }
}
