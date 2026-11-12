package com.openyt.core.testing

/**
 * Pure Kotlin synthetic media timeline fixtures shared by subtitle parsing,
 * timed search, study repetitions, clip batching and duplicate/file preservation tests.
 *
 * All fixtures are completely offline and deterministic, matching the checked-in
 * files and checksums in resources/fixtures/timeline/PROVENANCE.md.
 */
object SyntheticTimelineFixtures {

    const val TIMELINE_DURATION_MS = 10_000L

    data class Cue(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val text: String,
    ) {
        val durationMs: Long get() = endMs - startMs
        fun overlapsWith(other: Cue): Boolean =
            startMs < other.endMs && other.startMs < endMs
    }

    data class Timeline(
        val language: String,
        val durationMs: Long,
        val cues: List<Cue>,
        val srtContent: String,
        val vttContent: String,
        val srtSha256: String,
        val vttSha256: String,
    )

    data class BinaryFixture(
        val fileName: String,
        val sizeBytes: Int,
        val content: ByteArray,
        val sha256: String,
    )

    data class InertFixture(
        val fileName: String,
        val sizeBytes: Int,
        val content: String,
        val sha256: String,
    )

    val TimelinePt = Timeline(
        language = "pt-BR",
        durationMs = TIMELINE_DURATION_MS,
        cues = listOf(
            Cue(1, 1_000L, 4_500L, "Introdução à biblioteca pessoal de mídia do OpenYT."),
            Cue(2, 3_000L, 7_000L, "Lição prática: pesquisa sincronizada e anotações com acentuação."),
            Cue(3, 7_500L, 9_800L, "Preservação offline concluída com sucesso."),
        ),
        srtContent = """1
00:00:01,000 --> 00:00:04,500
Introdução à biblioteca pessoal de mídia do OpenYT.

2
00:00:03,000 --> 00:00:07,000
Lição prática: pesquisa sincronizada e anotações com acentuação.

3
00:00:07,500 --> 00:00:09,800
Preservação offline concluída com sucesso.
""",
        vttContent = """WEBVTT - OpenYT Synthetic Timeline pt-BR

00:00:01.000 --> 00:00:04.500
Introdução à biblioteca pessoal de mídia do OpenYT.

00:00:03.000 --> 00:00:07.000
Lição prática: pesquisa sincronizada e anotações com acentuação.

00:00:07.500 --> 00:00:09.800
Preservação offline concluída com sucesso.
""",
        srtSha256 = "7f7decd41e7d0e55cd6f0d814e9d3161aff6e8b994c8f3f43730d3b7388c8580",
        vttSha256 = "d6effab9b16140f111d0c5ebf200e67a6982d18178fece55911bc7670254dd88",
    )

    val TimelineEn = Timeline(
        language = "en",
        durationMs = TIMELINE_DURATION_MS,
        cues = listOf(
            Cue(1, 1_000L, 4_500L, "Introduction to OpenYT personal media library."),
            Cue(2, 3_000L, 7_000L, "Practical study: timed search and study notes."),
            Cue(3, 7_500L, 9_800L, "Offline preservation completed successfully."),
        ),
        srtContent = """1
00:00:01,000 --> 00:00:04,500
Introduction to OpenYT personal media library.

2
00:00:03,000 --> 00:00:07,000
Practical study: timed search and study notes.

3
00:00:07,500 --> 00:00:09,800
Offline preservation completed successfully.
""",
        vttContent = """WEBVTT - OpenYT Synthetic Timeline en

00:00:01.000 --> 00:00:04.500
Introduction to OpenYT personal media library.

00:00:03.000 --> 00:00:07.000
Practical study: timed search and study notes.

00:00:07.500 --> 00:00:09.800
Offline preservation completed successfully.
""",
        srtSha256 = "22171c045680d47e7d991dd9dd0bf684171620d343c2ef1615a589e5bc8d0574",
        vttSha256 = "bfbfd0b0386b772c7d35131acfbe4c5c369613ca8fc896fe4740ca840ee7dd6e",
    )

    val SubtitleFree = InertFixture(
        fileName = "timeline_nosubs.txt",
        sizeBytes = 83,
        content = """OpenYT Synthetic Media Item without subtitles.
Duration: 10000ms.
Subtitles: None.
""",
        sha256 = "d0e6783dae824e0922e3eb54523ec2eafff48a7278c4ee46841de2688f70ae8f",
    )

    private val identicalBytes = "OpenYT Synthetic Stream Byte Seed Fixed Block Content:0x4F70656E".encodeToByteArray()

    val IdenticalFileA = BinaryFixture(
        fileName = "identical_a.bin",
        sizeBytes = 64,
        content = identicalBytes,
        sha256 = "ad7bb3e06863f44cf86eb6b146ec4601e83c476145557dd70d8a4bc5c84048d9",
    )

    val IdenticalFileB = BinaryFixture(
        fileName = "identical_b.bin",
        sizeBytes = 64,
        content = identicalBytes,
        sha256 = "ad7bb3e06863f44cf86eb6b146ec4601e83c476145557dd70d8a4bc5c84048d9",
    )

    val DistinctSize64Alpha = BinaryFixture(
        fileName = "distinct_size64_a.bin",
        sizeBytes = 64,
        content = "OpenYT Synthetic File Alpha - Exactly 64 Bytes Content Pattern!\n".encodeToByteArray(),
        sha256 = "65279fe4fe4a9d91c9533742a298a1ce0c843fdeea87769bfa98f83299a9ad19",
    )

    val DistinctSize64Beta = BinaryFixture(
        fileName = "distinct_size64_b.bin",
        sizeBytes = 64,
        content = "OpenYT Synthetic File Beta -- Exactly 64 Bytes Content Pattern!\n".encodeToByteArray(),
        sha256 = "3b01173391fc272f9cd9341141b04ab9cf4f4fc188677817cf2b30109d469861",
    )

    const val COMPLEX_UNICODE_FILENAME = "Vídeo d'Ação & Preservação [2026] #1.mp4"

    val InertMalformed = InertFixture(
        fileName = "inert_malformed.srt",
        sizeBytes = 150,
        content = """NOT_A_NUMBER
00:00:01.000 --> BAD_TIMESTAMP
Missing end time and invalid formatting.

-5
99:99:99,999 --> 00:00:00,000
Backwards negative time range.
""",
        sha256 = "e4c1e81fade1e436489caf347d2c04cdf4008d126f2667d4747311e98516006e",
    )

    val InertTraversal = InertFixture(
        fileName = "inert_traversal.vtt",
        sizeBytes = 212,
        content = """WEBVTT - Inert Traversal Test

00:00:00.000 --> 00:00:02.000
Path payload: ../../etc/passwd and ..\..\windows\system32\config\SAM

00:00:02.500 --> 00:00:05.000
Escaped path: \u002e\u002e/\u002e\u002e/secret.key
""",
        sha256 = "b2d71c126d1261b87ad2f4190d598c7a54835f936238585ef18905fbc5d06623",
    )

    val InertOversized = InertFixture(
        fileName = "inert_oversized.srt",
        sizeBytes = 97,
        content = """1
999:59:59,000 --> 1000:00:00,000
Extremely large hour timestamp exceeding standard 24h limits.
""",
        sha256 = "7636314a268256de7c34af4dd2631ff6589850e205edadb3db597f473d5ea1b4",
    )
}
