package com.openyt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadProfileResolverTest {

    private val resolver = DownloadProfileResolver()

    private fun sampleMedia(
        formats: List<MediaFormat>,
        subtitles: List<SubtitleTrack> = emptyList(),
        thumbnailUrl: String? = "https://example.com/thumb.jpg",
    ) = MediaInfo(
        sourceId = "test-video",
        source = "youtube",
        title = "Test Video",
        webpageUrl = "https://example.com/watch?v=123",
        formats = formats,
        subtitles = subtitles,
        thumbnailUrl = thumbnailUrl,
    )

    @Test
    fun studyProfilePrefersMp4AvcAndHumanSubtitles() {
        val formats = listOf(
            MediaFormat("v-vp9", MediaKind.VIDEO, "webm", videoCodec = "vp9", height = 1080, bitrateKbps = 2500.0),
            MediaFormat("v-avc", MediaKind.VIDEO, "mp4", videoCodec = "avc1.640028", height = 1080, bitrateKbps = 2000.0),
            MediaFormat("a-opus", MediaKind.AUDIO, "webm", audioCodec = "opus", bitrateKbps = 160.0),
            MediaFormat("a-aac", MediaKind.AUDIO, "m4a", audioCodec = "mp4a.40.2", bitrateKbps = 128.0),
        )
        val subtitles = listOf(
            SubtitleTrack(language = "pt", automatic = true),
            SubtitleTrack(language = "pt-BR", automatic = false),
            SubtitleTrack(language = "en", automatic = false),
        )
        val media = sampleMedia(formats, subtitles)
        val resolution = resolver.resolve(DownloadProfile.study("pt"), media)

        val success = assertIs<ProfileResolution.Success>(resolution)
        val plan = success.plan
        assertEquals("v-avc", plan.selectedVideoFormat?.id)
        assertEquals("a-aac", plan.selectedAudioFormat?.id)
        assertEquals("mp4", plan.container)
        assertEquals(1080, plan.effectiveHeight)

        val sub = assertNotNull(plan.selectedSubtitle)
        assertEquals("pt-BR", sub.language)
        assertEquals(false, sub.automatic)
        assertTrue(sub.embed)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun studyProfileEmitsWarningsWhenSubtitlesOrThumbnailMissing() {
        val formats = listOf(
            MediaFormat("v1", MediaKind.VIDEO, "mp4", videoCodec = "avc1", height = 720),
            MediaFormat("a1", MediaKind.AUDIO, "m4a", audioCodec = "aac"),
        )
        val media = sampleMedia(formats, subtitles = emptyList(), thumbnailUrl = null)
        val resolution = resolver.resolve(DownloadProfile.study("pt"), media)

        val success = assertIs<ProfileResolution.Success>(resolution)
        val warnings = success.plan.warnings
        assertTrue(warnings.any { it is ProfileWarning.MissingSubtitle })
        assertTrue(warnings.any { it is ProfileWarning.MissingThumbnail })
        assertNull(success.plan.selectedSubtitle)
    }

    @Test
    fun humanVsAutomaticSubtitleMatchingBehavior() {
        val formats = listOf(MediaFormat("v1", MediaKind.VIDEO, "mp4", height = 720))
        val subtitles = listOf(SubtitleTrack(language = "pt", automatic = true))
        val media = sampleMedia(formats, subtitles)

        // 1. Without allowAutomatic -> no match, warning
        val strictProfile = DownloadProfile.study("pt")
        val strictRes = assertIs<ProfileResolution.Success>(resolver.resolve(strictProfile, media))
        assertNull(strictRes.plan.selectedSubtitle)
        assertTrue(strictRes.plan.warnings.any { it is ProfileWarning.MissingSubtitle })

        // 2. With allowAutomatic = true -> matches auto subtitle
        val autoProfile = DownloadProfile.study("pt").copy(
            subtitlePreference = ProfileSubtitlePreference.Preferred("pt", allowAutomatic = true),
        )
        val autoRes = assertIs<ProfileResolution.Success>(resolver.resolve(autoProfile, media))
        val sub = assertNotNull(autoRes.plan.selectedSubtitle)
        assertEquals("pt", sub.language)
        assertTrue(sub.automatic)
    }

    @Test
    fun audioOnlySourceReturnsIncompatibleForVideoProfilesAndSucceedsForAudioProfile() {
        val audioFormats = listOf(
            MediaFormat("a-low", MediaKind.AUDIO, "m4a", audioCodec = "aac", bitrateKbps = 64.0),
            MediaFormat("a-high", MediaKind.AUDIO, "m4a", audioCodec = "aac", bitrateKbps = 256.0, sizeBytes = 5_000_000),
        )
        val media = sampleMedia(audioFormats)

        // Video profiles fail actionably
        val videoRes = resolver.resolve(DownloadProfile.study("pt"), media)
        val incomp = assertIs<ProfileResolution.Incompatible>(videoRes)
        assertEquals(IncompatibilityReason.AUDIO_ONLY_SOURCE, incomp.reason)
        assertEquals(ActionableAlternative.SWITCH_TO_AUDIO, incomp.actionableAlternative)

        // Audio profile succeeds
        val audioRes = resolver.resolve(DownloadProfile.AudioOnly, media)
        val success = assertIs<ProfileResolution.Success>(audioRes)
        assertEquals("a-high", success.plan.selectedAudioFormat?.id)
        assertNull(success.plan.selectedVideoFormat)
        assertEquals("m4a", success.plan.container)
        assertEquals(5_000_000L, success.plan.estimatedSizeBytes)
    }

    @Test
    fun saveSpaceProfileRespects480pCeilingAndEmitsEstimateNotice() {
        val formats = listOf(
            MediaFormat("v-1080", MediaKind.VIDEO, "mp4", videoCodec = "avc1", height = 1080),
            MediaFormat("v-720", MediaKind.VIDEO, "mp4", videoCodec = "avc1", height = 720),
            MediaFormat("v-480", MediaKind.VIDEO, "mp4", videoCodec = "avc1", height = 480, sizeBytes = 10_000_000),
            MediaFormat("v-360", MediaKind.VIDEO, "mp4", videoCodec = "avc1", height = 360),
            MediaFormat("a1", MediaKind.AUDIO, "m4a", audioCodec = "aac", sizeBytes = 2_000_000),
        )
        val media = sampleMedia(formats)
        val resolution = resolver.resolve(DownloadProfile.SaveSpace, media)

        val success = assertIs<ProfileResolution.Success>(resolution)
        assertEquals("v-480", success.plan.selectedVideoFormat?.id)
        assertEquals(480, success.plan.effectiveHeight)
        assertEquals(12_000_000L, success.plan.estimatedSizeBytes)
        assertTrue(success.plan.warnings.any { it is ProfileWarning.EstimatedSize })
    }

    @Test
    fun tvStrictProfileRejectsSourceWithoutNativeMp4AvcAac() {
        // Only WebM / VP9 / Opus available
        val formats = listOf(
            MediaFormat("v-webm", MediaKind.VIDEO, "webm", videoCodec = "vp9", height = 1080),
            MediaFormat("a-webm", MediaKind.AUDIO, "webm", audioCodec = "opus"),
        )
        val media = sampleMedia(formats)
        val resolution = resolver.resolve(DownloadProfile.WatchOnTv, media)

        val incomp = assertIs<ProfileResolution.Incompatible>(resolution)
        assertEquals(IncompatibilityReason.NO_COMPATIBLE_TV_FORMAT, incomp.reason)
        assertEquals(ActionableAlternative.MANUAL_SELECTION, incomp.actionableAlternative)
    }

    @Test
    fun tvStrictProfileSucceedsWhenNativeMp4AvcAacIsPresent() {
        val formats = listOf(
            MediaFormat("v-webm", MediaKind.VIDEO, "webm", videoCodec = "vp9", height = 1080),
            MediaFormat("v-mp4", MediaKind.VIDEO, "mp4", videoCodec = "avc1.4d401f", height = 720),
            MediaFormat("a-aac", MediaKind.AUDIO, "m4a", audioCodec = "mp4a.40.2"),
        )
        val media = sampleMedia(formats)
        val resolution = resolver.resolve(DownloadProfile.WatchOnTv, media)

        val success = assertIs<ProfileResolution.Success>(resolution)
        assertEquals("v-mp4", success.plan.selectedVideoFormat?.id)
        assertEquals("a-aac", success.plan.selectedAudioFormat?.id)
        assertEquals("mp4", success.plan.container)
    }

    @Test
    fun combinedStreamFormatPickedWithoutCompanionAudio() {
        val formats = listOf(
            MediaFormat("f18", MediaKind.VIDEO, "mp4", videoCodec = "avc1", audioCodec = "mp4a", height = 360, sizeBytes = 8_000_000),
            MediaFormat("a1", MediaKind.AUDIO, "m4a", audioCodec = "mp4a", sizeBytes = 2_000_000),
        )
        val media = sampleMedia(formats)
        val resolution = resolver.resolve(DownloadProfile.SaveSpace, media)

        val success = assertIs<ProfileResolution.Success>(resolution)
        assertEquals("f18", success.plan.selectedVideoFormat?.id)
        assertNull(success.plan.selectedAudioFormat)
        assertEquals(8_000_000L, success.plan.estimatedSizeBytes)
    }

    @Test
    fun unknownCodecEmitsWarningAndResolvesSafely() {
        val formats = listOf(
            MediaFormat("v-unknown", MediaKind.VIDEO, "mp4", videoCodec = "unknown", height = 720),
            MediaFormat("a1", MediaKind.AUDIO, "m4a", audioCodec = "aac"),
        )
        val media = sampleMedia(formats)
        val resolution = resolver.resolve(DownloadProfile.SaveSpace.copy(maxHeight = 720), media)

        val success = assertIs<ProfileResolution.Success>(resolution)
        assertEquals("v-unknown", success.plan.selectedVideoFormat?.id)
        assertTrue(success.plan.warnings.any { it is ProfileWarning.UnknownCodec })
    }

    @Test
    fun absentResolutionHandledWithoutCrashing() {
        val formats = listOf(
            MediaFormat("v-nores", MediaKind.VIDEO, "mp4", videoCodec = "avc1", height = null, width = null),
            MediaFormat("a1", MediaKind.AUDIO, "m4a", audioCodec = "aac"),
        )
        val media = sampleMedia(formats)
        val resolution = resolver.resolve(DownloadProfile.SaveSpace, media)

        val success = assertIs<ProfileResolution.Success>(resolution)
        assertEquals("v-nores", success.plan.selectedVideoFormat?.id)
        assertNull(success.plan.effectiveHeight)
    }

    @Test
    fun deterministicTieBreakingPicksEarlierFormatId() {
        val formats = listOf(
            MediaFormat("v-b", MediaKind.VIDEO, "mp4", videoCodec = "avc1", height = 720, bitrateKbps = 1500.0),
            MediaFormat("v-a", MediaKind.VIDEO, "mp4", videoCodec = "avc1", height = 720, bitrateKbps = 1500.0),
            MediaFormat("a1", MediaKind.AUDIO, "m4a", audioCodec = "aac"),
        )
        val media = sampleMedia(formats)
        val resolution = resolver.resolve(DownloadProfile.study("pt"), media)

        val success = assertIs<ProfileResolution.Success>(resolution)
        assertEquals("v-a", success.plan.selectedVideoFormat?.id)
    }

    @Test
    fun emptyFormatsReturnsIncompatibleNoFormats() {
        val media = sampleMedia(emptyList())
        val resolution = resolver.resolve(DownloadProfile.study("pt"), media)

        val incomp = assertIs<ProfileResolution.Incompatible>(resolution)
        assertEquals(IncompatibilityReason.NO_FORMATS_AVAILABLE, incomp.reason)
        assertEquals(ActionableAlternative.MANUAL_SELECTION, incomp.actionableAlternative)
    }

    @Test
    fun fallbackWarningWhenAllFormatsExceedMaxHeight() {
        val formats = listOf(
            MediaFormat("v-1080", MediaKind.VIDEO, "mp4", videoCodec = "avc1", height = 1080),
            MediaFormat("a1", MediaKind.AUDIO, "m4a", audioCodec = "aac"),
        )
        val media = sampleMedia(formats)
        val resolution = resolver.resolve(DownloadProfile.SaveSpace, media) // maxHeight = 480

        val success = assertIs<ProfileResolution.Success>(resolution)
        assertEquals("v-1080", success.plan.selectedVideoFormat?.id)
        assertTrue(success.plan.warnings.any { it is ProfileWarning.FallbackResolution })
    }
}
