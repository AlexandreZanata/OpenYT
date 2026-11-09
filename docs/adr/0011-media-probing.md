# ADR-0011: Local media technical probing

- Status: accepted
- Date: 2026-11-09

## Context

Library details only showed path, size, kind, and availability. Users benefit from
duration, resolution, codecs, bitrate, and container, but these require platform
media APIs or FFprobe.

## Decision

- Add a `MediaProbe` port returning `MediaTechnicalInfo`; failures return null and
  never surface raw tool output.
- Android uses `MediaExtractor` for tracks/codecs and
  `MediaMetadataRetriever` for duration.
- Desktop runs FFprobe with structured arguments and parses JSON in a pure, tested
  parser. FFprobe is optional; the UI shows "not available" when absent.
- Probe only when the details dialog opens and only for existing files.
- Do not cache technical metadata yet; files can change outside the app.

## Consequences

- Technical details are available on both platforms without new backend services.
- Some Android containers may omit audio/bitrate fields; the UI simply hides them.
- Desktop requires FFprobe on the machine, consistent with external yt-dlp/FFmpeg.
