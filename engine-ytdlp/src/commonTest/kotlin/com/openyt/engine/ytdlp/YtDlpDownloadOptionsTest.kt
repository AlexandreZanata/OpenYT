package com.openyt.engine.ytdlp

import com.openyt.core.domain.AudioPreset
import com.openyt.core.domain.DownloadOptions
import com.openyt.core.domain.DownloadRequest
import com.openyt.core.domain.MediaKind
import com.openyt.core.domain.ProfileProvenance
import com.openyt.core.domain.SubtitleSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpDownloadOptionsTest {

    @Test
    fun rawFormatSelectionWithoutProfilePreservesExistingBehavior() {
        val request = DownloadRequest(
            url = "https://example.com/watch?v=123",
            kind = MediaKind.VIDEO,
            formatId = "137+140",
            destinationDirectory = "/downloads",
        )
        val args = YtDlpDownloadOptions.arguments(request)
        assertEquals(listOf("-f", "137+140", "--embed-chapters"), args)
        assertFalse(args.contains("--merge-output-format"))
        assertFalse(args.contains("--remux-video"))
    }

    @Test
    fun videoWithProfileResolvedContainerPassesMergeAndRemuxFlags() {
        val request = DownloadRequest(
            url = "https://example.com/watch?v=123",
            kind = MediaKind.VIDEO,
            formatId = "137+140",
            destinationDirectory = "/downloads",
            options = DownloadOptions(
                profileProvenance = ProfileProvenance(
                    profileId = "study",
                    profileName = "Study",
                    resolvedContainer = "mp4",
                ),
            ),
        )
        val args = YtDlpDownloadOptions.arguments(request)
        assertTrue(args.contains("-f"))
        assertEquals("137+140", args[args.indexOf("-f") + 1])
        assertTrue(args.contains("--merge-output-format"))
        assertEquals("mp4", args[args.indexOf("--merge-output-format") + 1])
        assertTrue(args.contains("--remux-video"))
        assertEquals("mp4", args[args.indexOf("--remux-video") + 1])
    }

    @Test
    fun videoWithMkvResolvedContainerPassesMkvMergeAndRemuxFlags() {
        val request = DownloadRequest(
            url = "https://example.com/watch?v=123",
            kind = MediaKind.VIDEO,
            formatId = "22",
            destinationDirectory = "/downloads",
            options = DownloadOptions(
                profileProvenance = ProfileProvenance(
                    profileId = "tv",
                    profileName = "Watch on TV",
                    resolvedContainer = "mkv",
                ),
            ),
        )
        val args = YtDlpDownloadOptions.arguments(request)
        assertTrue(args.contains("--merge-output-format"))
        assertEquals("mkv", args[args.indexOf("--merge-output-format") + 1])
        assertTrue(args.contains("--remux-video"))
        assertEquals("mkv", args[args.indexOf("--remux-video") + 1])
    }

    @Test
    fun unsafeContainerInProfileProvenanceIsIgnored() {
        val request = DownloadRequest(
            url = "https://example.com/watch?v=123",
            kind = MediaKind.VIDEO,
            destinationDirectory = "/downloads",
            options = DownloadOptions(
                profileProvenance = ProfileProvenance(
                    profileId = "custom",
                    profileName = "Malicious",
                    resolvedContainer = "mp4; rm -rf /",
                ),
            ),
        )
        val args = YtDlpDownloadOptions.arguments(request)
        assertFalse(args.contains("--merge-output-format"))
        assertFalse(args.contains("--remux-video"))
    }

    @Test
    fun audioWithProfileContainerOverridesPresetExtension() {
        val request = DownloadRequest(
            url = "https://example.com/watch?v=123",
            kind = MediaKind.AUDIO,
            destinationDirectory = "/downloads",
            options = DownloadOptions(
                audioPreset = AudioPreset.M4A,
                profileProvenance = ProfileProvenance(
                    profileId = "audio",
                    profileName = "Audio",
                    resolvedContainer = "opus",
                ),
            ),
        )
        val args = YtDlpDownloadOptions.arguments(request)
        assertTrue(args.contains("-x"))
        assertTrue(args.contains("--audio-format"))
        assertEquals("opus", args[args.indexOf("--audio-format") + 1])
    }

    @Test
    fun subtitlesArePassedAsIndividualTypedArguments() {
        val request = DownloadRequest(
            url = "https://example.com/watch?v=123",
            kind = MediaKind.VIDEO,
            destinationDirectory = "/downloads",
            options = DownloadOptions(
                subtitle = SubtitleSelection(
                    language = "pt",
                    automatic = false,
                    embed = true,
                ),
            ),
        )
        val args = YtDlpDownloadOptions.arguments(request)
        assertTrue(args.contains("--write-subs"))
        assertFalse(args.contains("--write-auto-subs"))
        assertTrue(args.contains("--sub-langs"))
        assertEquals("pt", args[args.indexOf("--sub-langs") + 1])
        assertTrue(args.contains("--embed-subs"))
    }

    @Test
    fun automaticSubtitlesUseWriteAutoSubs() {
        val request = DownloadRequest(
            url = "https://example.com/watch?v=123",
            kind = MediaKind.VIDEO,
            destinationDirectory = "/downloads",
            options = DownloadOptions(
                subtitle = SubtitleSelection(
                    language = "en",
                    automatic = true,
                    embed = false,
                ),
            ),
        )
        val args = YtDlpDownloadOptions.arguments(request)
        assertTrue(args.contains("--write-auto-subs"))
        assertFalse(args.contains("--write-subs"))
        assertFalse(args.contains("--embed-subs"))
    }

    @Test
    fun subtitleOnlyDownloadSkipsVideoDownload() {
        val request = DownloadRequest(
            url = "https://example.com/watch?v=123",
            kind = MediaKind.SUBTITLE,
            destinationDirectory = "/downloads",
            options = DownloadOptions(
                subtitle = SubtitleSelection("pt"),
            ),
        )
        val args = YtDlpDownloadOptions.arguments(request)
        assertTrue(args.contains("--skip-download"))
        assertFalse(args.contains("-f"))
    }
}
