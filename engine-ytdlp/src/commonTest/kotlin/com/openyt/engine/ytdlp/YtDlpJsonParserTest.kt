package com.openyt.engine.ytdlp

import com.openyt.core.domain.DownloadFailure
import com.openyt.core.domain.EngineResult
import com.openyt.core.domain.Inspection
import com.openyt.core.domain.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class YtDlpJsonParserTest {
    @Test
    fun parsesVideoAndAudioFormats() {
        val result = YtDlpJsonParser.parseInspection(SAMPLE_JSON)

        val media = assertIs<Inspection.Single>(assertIs<EngineResult.Success<Inspection>>(result).value).media
        assertEquals("abc123", media.sourceId)
        assertEquals("youtube", media.source)
        assertEquals("Example clip", media.title)
        assertEquals("Some Channel", media.author)
        assertEquals(120, media.durationSeconds)
        assertEquals(2, media.formats.size)
        assertEquals(MediaKind.VIDEO, media.formats[0].kind)
        assertEquals("mp4", media.formats[0].container)
        assertEquals(1920, media.formats[0].width)
        assertNull(media.formats[0].audioCodec)
        assertEquals(MediaKind.AUDIO, media.formats[1].kind)
        assertEquals(128_000, media.formats[1].sizeBytes)
    }

    @Test
    fun keepsFormatsWithoutCodecMetadata() {
        val result = YtDlpJsonParser.parseInspection(
            """{"id":"x","title":"T","formats":[{"format_id":"0","ext":"ogv","vcodec":null,"acodec":null,"width":533,"height":300}]}""",
        )

        val media = assertIs<Inspection.Single>(assertIs<EngineResult.Success<Inspection>>(result).value).media
        assertEquals(1, media.formats.size)
        assertEquals(MediaKind.VIDEO, media.formats[0].kind)
        assertEquals(533, media.formats[0].width)
    }

    @Test
    fun parsesPlaylistDocuments() {
        val result = YtDlpJsonParser.parseInspection(PLAYLIST_JSON)

        val playlist = assertIs<Inspection.Playlist>(
            assertIs<EngineResult.Success<Inspection>>(result).value,
        ).playlist
        assertEquals("p", playlist.sourceId)
        assertEquals("youtube", playlist.source)
        assertEquals("My playlist", playlist.title)
        assertEquals(2, playlist.entries.size)
        assertEquals(1, playlist.entries[0].index)
        assertEquals("https://www.youtube.com/watch?v=one", playlist.entries[0].url)
        assertEquals(2, playlist.entries[1].index)
    }

    @Test
    fun rejectsPlaylistWithoutDownloadableEntries() {
        val result = YtDlpJsonParser.parseInspection("""{"_type":"playlist","id":"p","title":"List","entries":[]}""")

        val failure = assertIs<EngineResult.Failure>(result).failure
        assertIs<DownloadFailure.ProcessingFailed>(failure)
    }

    @Test
    fun rejectsIncompleteMetadata() {
        val result = YtDlpJsonParser.parseInspection("""{"id":"x"}""")

        val failure = assertIs<EngineResult.Failure>(result).failure
        assertIs<DownloadFailure.ProcessingFailed>(failure)
    }

    @Test
    fun rejectsMalformedJson() {
        val result = YtDlpJsonParser.parseInspection("not json")

        val failure = assertIs<EngineResult.Failure>(result).failure
        assertIs<DownloadFailure.ProcessingFailed>(failure)
    }

    @Test
    fun toleratesMissingOptionalFields() {
        val result = YtDlpJsonParser.parseInspection(
            """{"id":"x","title":"T","formats":[]}""",
        )

        val media = assertIs<Inspection.Single>(assertIs<EngineResult.Success<Inspection>>(result).value).media
        assertNull(media.author)
        assertNull(media.durationSeconds)
        assertEquals("unknown", media.source)
    }

    private companion object {
        val PLAYLIST_JSON = """
            {
              "_type": "playlist",
              "id": "p",
              "title": "My playlist",
              "extractor": "youtube",
              "webpage_url": "https://www.youtube.com/playlist?list=p",
              "entries": [
                {
                  "id": "one",
                  "title": "First",
                  "url": "https://www.youtube.com/watch?v=one",
                  "duration": 60,
                  "thumbnails": [{"url": "https://img/one.jpg"}]
                },
                {
                  "id": "two",
                  "title": "Second",
                  "webpage_url": "https://www.youtube.com/watch?v=two",
                  "duration": 90
                }
              ]
            }
        """.trimIndent()

        val SAMPLE_JSON = """
            {
              "id": "abc123",
              "title": "Example clip",
              "uploader": "Some Channel",
              "duration": 120,
              "extractor_key": "Youtube",
              "extractor": "youtube",
              "webpage_url": "https://example.com/watch?v=abc123",
              "formats": [
                {
                  "format_id": "137",
                  "ext": "mp4",
                  "vcodec": "avc1.640028",
                  "acodec": "none",
                  "width": 1920,
                  "height": 1080,
                  "tbr": 4500.5,
                  "filesize": 1000
                },
                {
                  "format_id": "251",
                  "ext": "webm",
                  "vcodec": "none",
                  "acodec": "opus",
                  "filesize": 128000
                }
              ]
            }
        """.trimIndent()
    }
}
