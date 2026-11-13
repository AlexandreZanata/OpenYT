# OpenYT Synthetic Timeline Fixtures — Provenance and Manifest

## Authorship and Purpose
These test fixtures are synthetic media timeline files created specifically for the OpenYT test suite.
They provide deterministic, offline inputs for subtitle parsing, timed text search, study bookmarks,
clip planning, backup/restore, duplicate detection, and path sanitization.

- **Author / Originator:** OpenYT Project Contributors.
- **Creation Date:** 2026-11-12.
- **External Network Dependency:** None (100% offline, synthetic data).
- **Personal / Proprietary Data:** None.

## License
Dedicated to the public domain under **Creative Commons Zero 1.0 Universal (CC0-1.0)**.
To the extent possible under law, the authors waive all copyright and related rights.

## Checksum and File Manifest

| File | Size (Bytes) | SHA-256 Checksum | Purpose / Characteristics |
|---|---|---|---|
| `timeline_pt.srt` | 272 | `7f7decd41e7d0e55cd6f0d814e9d3161aff6e8b994c8f3f43730d3b7388c8580` | SubRip pt-BR subtitles, UTF-8 accented characters, overlapping cues |
| `timeline_pt.vtt` | 308 | `d6effab9b16140f111d0c5ebf200e67a6982d18178fece55911bc7670254dd88` | WebVTT pt-BR subtitles, UTF-8 accented characters, overlapping cues |
| `timeline_en.srt` | 237 | `22171c045680d47e7d991dd9dd0bf684171620d343c2ef1615a589e5bc8d0574` | SubRip en subtitles, matching timeline cues, overlapping cues |
| `timeline_en.vtt` | 270 | `bfbfd0b0386b772c7d35131acfbe4c5c369613ca8fc896fe4740ca840ee7dd6e` | WebVTT en subtitles, matching timeline cues, overlapping cues |
| `timeline_nosubs.txt` | 83 | `d0e6783dae824e0922e3eb54523ec2eafff48a7278c4ee46841de2688f70ae8f` | Subtitle-free media metadata representation |
| `identical_a.bin` | 64 | `ad7bb3e06863f44cf86eb6b146ec4601e83c476145557dd70d8a4bc5c84048d9` | First copy of identical content under distinct name (deduplication test) |
| `identical_b.bin` | 64 | `ad7bb3e06863f44cf86eb6b146ec4601e83c476145557dd70d8a4bc5c84048d9` | Second copy of identical content under distinct name (deduplication test) |
| `distinct_size64_a.bin` | 64 | `65279fe4fe4a9d91c9533742a298a1ce0c843fdeea87769bfa98f83299a9ad19` | File Alpha: exactly 64 bytes, distinct content pattern |
| `distinct_size64_b.bin` | 64 | `3b01173391fc272f9cd9341141b04ab9cf4f4fc188677817cf2b30109d469861` | File Beta: exactly 64 bytes, distinct content pattern (different hash) |
| `inert_malformed.srt` | 150 | `e4c1e81fade1e436489caf347d2c04cdf4008d126f2667d4747311e98516006e` | Corrupted SRT timestamps, bad numbers and negative time ranges |
| `inert_traversal.vtt` | 212 | `b2d71c126d1261b87ad2f4190d598c7a54835f936238585ef18905fbc5d06623` | Inert relative directory traversal patterns (`../../etc/passwd`) |
| `inert_oversized.srt` | 97 | `7636314a268256de7c34af4dd2631ff6589850e205edadb3db597f473d5ea1b4` | Boundary test cue with hour > 999 hours |

## Cues and Timing Specification

Total Timeline Duration: **10,000 ms (10.0 seconds)**

### Portuguese (pt-BR)
- **Cue 1:** `00:00:01,000` (1,000 ms) ➔ `00:00:04,500` (4,500 ms), Duration: 3,500 ms
  - Text: `Introdução à biblioteca pessoal de mídia do OpenYT.`
  - Overlaps with Cue 2 between `00:00:03,000` and `00:00:04,500` (1,500 ms window).
- **Cue 2:** `00:00:03,000` (3,000 ms) ➔ `00:00:07,000` (7,000 ms), Duration: 4,000 ms
  - Text: `Lição prática: pesquisa sincronizada e anotações com acentuação.`
  - Overlaps with Cue 1 between `00:00:03,000` and `00:00:04,500`.
- **Cue 3:** `00:00:07,500` (7,500 ms) ➔ `00:00:09,800` (9,800 ms), Duration: 2,300 ms
  - Text: `Preservação offline concluída com sucesso.`
  - Disjoint, no overlap.

### English (en)
- **Cue 1:** `00:00:01,000` (1,000 ms) ➔ `00:00:04,500` (4,500 ms), Duration: 3,500 ms
  - Text: `Introduction to OpenYT personal media library.`
  - Overlaps with Cue 2 between `00:00:03,000` and `00:00:04,500` (1,500 ms window).
- **Cue 2:** `00:00:03,000` (3,000 ms) ➔ `00:00:07,000` (7,000 ms), Duration: 4,000 ms
  - Text: `Practical study: timed search and study notes.`
  - Overlaps with Cue 1 between `00:00:03,000` and `00:00:04,500`.
- **Cue 3:** `00:00:07,500` (7,500 ms) ➔ `00:00:09,800` (9,800 ms), Duration: 2,300 ms
  - Text: `Offline preservation completed successfully.`
  - Disjoint, no overlap.

## Complex Unicode Filename Fixture
- Relative file path: `Vídeo d'Ação & Preservação [2026] #1.mp4`
- Tested characteristics:
  - Space characters
  - Single quote / apostrophe (`'`)
  - Ampersand (`&`)
  - Square brackets (`[` `]`)
  - Hash symbol (`#`)
  - Unicode accented letters: `í`, `Ã`, `ç`, `ã`
