package com.openyt.core.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SubtitleValidatorTest {

    @Test
    fun validatesValidSrtContent() {
        val srtText = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello, welcome to OpenYT!

            2
            00:00:04,500 --> 00:00:08,200
            Offline media library ready.
        """.trimIndent()

        val result = SubtitleValidator.validate(srtText.encodeToByteArray(), "video.srt")
        assertIs<SubtitleValidator.SubtitleValidationResult.Valid>(result)
        assertEquals(SubtitleValidator.SubtitleFormat.SRT, result.format)
        assertEquals(2, result.cueCount)
    }

    @Test
    fun validatesValidVttContent() {
        val vttText = """
            WEBVTT

            00:01.000 --> 00:04.000
            First caption

            00:04.500 --> 00:08.000
            Second caption
        """.trimIndent()

        val result = SubtitleValidator.validate(vttText.encodeToByteArray(), "video.vtt")
        assertIs<SubtitleValidator.SubtitleValidationResult.Valid>(result)
        assertEquals(SubtitleValidator.SubtitleFormat.VTT, result.format)
        assertEquals(2, result.cueCount)
    }

    @Test
    fun normalizesUtf8BomAndLineEndings() {
        val bomSrt = "\uFEFF1\r\n00:00:01,000 --> 00:00:03,000\r\nCaption\r\n"
        val result = SubtitleValidator.validate(bomSrt.encodeToByteArray())
        assertIs<SubtitleValidator.SubtitleValidationResult.Valid>(result)
        assertEquals(1, result.cueCount)
        assertTrue(!result.normalizedText.startsWith("\uFEFF"))
        assertTrue(!result.normalizedText.contains("\r\n"))
    }

    @Test
    fun rejectsEmptyBytes() {
        val result = SubtitleValidator.validate(ByteArray(0))
        assertIs<SubtitleValidator.SubtitleValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("empty", ignoreCase = true))
    }

    @Test
    fun rejectsBinaryOrCorruptedContent() {
        val binaryData = byteArrayOf(0x00, 0x1F, 0x7F, 0x00, 0x45, 0x4E, 0x44)
        val result = SubtitleValidator.validate(binaryData)
        assertIs<SubtitleValidator.SubtitleValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("binary", ignoreCase = true))
    }

    @Test
    fun rejectsContentWithoutTimestampCues() {
        val plainText = "This is just a regular text file with no subtitle arrows."
        val result = SubtitleValidator.validate(plainText.encodeToByteArray(), "subs.srt")
        assertIs<SubtitleValidator.SubtitleValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("timestamp", ignoreCase = true) || result.reason.contains("cue", ignoreCase = true))
    }

    @Test
    fun rejectsOversizedContent() {
        val smallLimit = 50L
        val largeText = "1\n00:00:01,000 --> 00:00:04,000\n" + "A".repeat(100)
        val result = SubtitleValidator.validate(largeText.encodeToByteArray(), maxSizeBytes = smallLimit)
        assertIs<SubtitleValidator.SubtitleValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("exceeds", ignoreCase = true))
    }
}
