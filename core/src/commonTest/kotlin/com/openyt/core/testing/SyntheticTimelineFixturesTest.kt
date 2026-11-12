package com.openyt.core.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyntheticTimelineFixturesTest {

    @Test
    fun timelinePtHasCorrectCuesAccentsAndOverlaps() {
        val pt = SyntheticTimelineFixtures.TimelinePt
        assertEquals("pt-BR", pt.language)
        assertEquals(SyntheticTimelineFixtures.TIMELINE_DURATION_MS, pt.durationMs)
        assertEquals(3, pt.cues.size)

        val cue1 = pt.cues[0]
        val cue2 = pt.cues[1]
        val cue3 = pt.cues[2]

        assertEquals(1, cue1.index)
        assertEquals(1_000L, cue1.startMs)
        assertEquals(4_500L, cue1.endMs)
        assertEquals(3_500L, cue1.durationMs)
        assertTrue(cue1.text.contains("Introdução"))
        assertTrue(cue1.text.contains("mídia"))

        assertEquals(2, cue2.index)
        assertEquals(3_000L, cue2.startMs)
        assertEquals(7_000L, cue2.endMs)
        assertEquals(4_000L, cue2.durationMs)
        assertTrue(cue2.text.contains("Lição"))
        assertTrue(cue2.text.contains("sincronizada"))
        assertTrue(cue2.text.contains("anotações"))
        assertTrue(cue2.text.contains("acentuação"))

        assertEquals(3, cue3.index)
        assertEquals(7_500L, cue3.startMs)
        assertEquals(9_800L, cue3.endMs)
        assertEquals(2_300L, cue3.durationMs)
        assertTrue(cue3.text.contains("Preservação"))
        assertTrue(cue3.text.contains("concluída"))

        // Overlapping cue checks: cue 1 overlaps cue 2 between 3000ms and 4500ms
        assertTrue(cue1.overlapsWith(cue2))
        assertTrue(cue2.overlapsWith(cue1))

        // Cue 2 and Cue 3 do not overlap
        assertFalse(cue2.overlapsWith(cue3))
        assertFalse(cue3.overlapsWith(cue2))

        // Checksums match manifest
        assertEquals("7f7decd41e7d0e55cd6f0d814e9d3161aff6e8b994c8f3f43730d3b7388c8580", pt.srtSha256)
        assertEquals("d6effab9b16140f111d0c5ebf200e67a6982d18178fece55911bc7670254dd88", pt.vttSha256)
    }

    @Test
    fun timelineEnHasMatchingCuesAndOverlaps() {
        val en = SyntheticTimelineFixtures.TimelineEn
        assertEquals("en", en.language)
        assertEquals(SyntheticTimelineFixtures.TIMELINE_DURATION_MS, en.durationMs)
        assertEquals(3, en.cues.size)

        val cue1 = en.cues[0]
        val cue2 = en.cues[1]
        val cue3 = en.cues[2]

        assertEquals(1_000L, cue1.startMs)
        assertEquals(4_500L, cue1.endMs)
        assertEquals("Introduction to OpenYT personal media library.", cue1.text)

        assertEquals(3_000L, cue2.startMs)
        assertEquals(7_000L, cue2.endMs)
        assertEquals("Practical study: timed search and study notes.", cue2.text)

        assertEquals(7_500L, cue3.startMs)
        assertEquals(9_800L, cue3.endMs)
        assertEquals("Offline preservation completed successfully.", cue3.text)

        assertTrue(cue1.overlapsWith(cue2))
        assertFalse(cue2.overlapsWith(cue3))

        assertEquals("22171c045680d47e7d991dd9dd0bf684171620d343c2ef1615a589e5bc8d0574", en.srtSha256)
        assertEquals("bfbfd0b0386b772c7d35131acfbe4c5c369613ca8fc896fe4740ca840ee7dd6e", en.vttSha256)
    }

    @Test
    fun subtitleFreeItemIsDocumentedWithoutCues() {
        val nosubs = SyntheticTimelineFixtures.SubtitleFree
        assertEquals("timeline_nosubs.txt", nosubs.fileName)
        assertEquals(83, nosubs.sizeBytes)
        assertTrue(nosubs.content.contains("Subtitles: None."))
        assertEquals("d0e6783dae824e0922e3eb54523ec2eafff48a7278c4ee46841de2688f70ae8f", nosubs.sha256)
    }

    @Test
    fun identicalFilesHaveDifferentNamesIdenticalBytesAndEqualHash() {
        val fileA = SyntheticTimelineFixtures.IdenticalFileA
        val fileB = SyntheticTimelineFixtures.IdenticalFileB

        assertNotEquals(fileA.fileName, fileB.fileName)
        assertEquals("identical_a.bin", fileA.fileName)
        assertEquals("identical_b.bin", fileB.fileName)
        assertEquals(64, fileA.sizeBytes)
        assertEquals(64, fileB.sizeBytes)
        assertTrue(fileA.content.contentEquals(fileB.content))
        assertEquals(fileA.sha256, fileB.sha256)
        assertEquals("ad7bb3e06863f44cf86eb6b146ec4601e83c476145557dd70d8a4bc5c84048d9", fileA.sha256)
    }

    @Test
    fun distinctFilesHaveEqualSizeAndDifferentHashes() {
        val alpha = SyntheticTimelineFixtures.DistinctSize64Alpha
        val beta = SyntheticTimelineFixtures.DistinctSize64Beta

        assertEquals(64, alpha.sizeBytes)
        assertEquals(64, beta.sizeBytes)
        assertFalse(alpha.content.contentEquals(beta.content))
        assertNotEquals(alpha.sha256, beta.sha256)
        assertEquals("65279fe4fe4a9d91c9533742a298a1ce0c843fdeea87769bfa98f83299a9ad19", alpha.sha256)
        assertEquals("3b01173391fc272f9cd9341141b04ab9cf4f4fc188677817cf2b30109d469861", beta.sha256)
    }

    @Test
    fun complexUnicodeFilenamePreservesSpecialCharacters() {
        val filename = SyntheticTimelineFixtures.COMPLEX_UNICODE_FILENAME
        assertEquals("Vídeo d'Ação & Preservação [2026] #1.mp4", filename)
        assertTrue(filename.contains(" "))
        assertTrue(filename.contains("'"))
        assertTrue(filename.contains("&"))
        assertTrue(filename.contains("["))
        assertTrue(filename.contains("]"))
        assertTrue(filename.contains("#"))
        assertTrue(filename.contains("í"))
        assertTrue(filename.contains("A"))
        assertTrue(filename.contains("ç"))
        assertTrue(filename.contains("ã"))
    }

    @Test
    fun inertSecurityFixturesAreInertStrings() {
        val malformed = SyntheticTimelineFixtures.InertMalformed
        assertEquals("inert_malformed.srt", malformed.fileName)
        assertEquals(150, malformed.sizeBytes)
        assertTrue(malformed.content.contains("NOT_A_NUMBER"))
        assertTrue(malformed.content.contains("BAD_TIMESTAMP"))
        assertTrue(malformed.content.contains("-5"))

        val traversal = SyntheticTimelineFixtures.InertTraversal
        assertEquals("inert_traversal.vtt", traversal.fileName)
        assertEquals(212, traversal.sizeBytes)
        assertTrue(traversal.content.contains("../../etc/passwd"))
        assertTrue(traversal.content.contains("..\\..\\windows\\system32"))

        val oversized = SyntheticTimelineFixtures.InertOversized
        assertEquals("inert_oversized.srt", oversized.fileName)
        assertEquals(97, oversized.sizeBytes)
        assertTrue(oversized.content.contains("999:59:59,000"))
    }
}
