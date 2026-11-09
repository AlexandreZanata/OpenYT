package com.openyt.desktop.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FfprobeJsonParserTest {
    @Test
    fun parsesVideoAndAudioStreams() {
        val info = FfprobeJsonParser.parse(SAMPLE)

        assertEquals(320, info?.width)
        assertEquals(240, info?.height)
        assertEquals("h264", info?.videoCodec)
        assertEquals("aac", info?.audioCodec)
        assertEquals(1_000L, info?.durationMillis)
        assertEquals(128, info?.bitrateKbps)
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", info?.container)
    }

    @Test
    fun rejectsMalformedJson() {
        assertNull(FfprobeJsonParser.parse("not json"))
        assertNull(FfprobeJsonParser.parse("{}"))
    }

    private companion object {
        val SAMPLE = """
            {
              "streams": [
                {
                  "codec_type": "video",
                  "codec_name": "h264",
                  "width": 320,
                  "height": 240,
                  "bit_rate": "100000"
                },
                {
                  "codec_type": "audio",
                  "codec_name": "aac",
                  "bit_rate": "28000"
                }
              ],
              "format": {
                "duration": "1.000000",
                "bit_rate": "128000",
                "format_name": "mov,mp4,m4a,3gp,3g2,mj2"
              }
            }
        """.trimIndent()
    }
}
