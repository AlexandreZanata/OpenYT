# ADR-0020 — Download completion and recovery

- Status accepted
- Date 2026-11-11

## Context

Two user videos show downloaded, playable Windows files alongside failed tasks
and an empty library. The storage panel then reports those files as orphans.
The videos contain no yt-dlp or FFmpeg diagnostics, so they cannot establish a
specific conversion failure.

The engine previously parsed an unframed path from stdout and assumed UTF-8
without specifying the subprocess encoding. Non-UTF-8 Windows output and UNC
paths could fail that check even after a successful download. Queue persistence
also omitted the filename template and playlist item index, so retrying after a
restart could run a different request.

## Decision

- Both hosts disable ambient yt-dlp configuration and specify UTF-8. OpenYT owns
  its download options through the existing settings and engine port.
- The shared final-path protocol is
  `after_move:OPENYT_FILE:%(filepath)j`. JSON escaping preserves Unicode,
  backslashes, and quoted characters. Ordinary log lines cannot confirm a file.
- Success requires a successful engine exit and a nonempty file at the reported
  final path. An existing intermediate file cannot override a processing error.
- Missing or empty final output is the structured `OutputMissing` failure. Its
  localized message explains how to check the destination and import existing
  files. Older stored failures with the same reason are mapped to this type.
- Schema 14 preserves the filename template and playlist item index. Legacy
  playlist tasks that stored an entry URL as their source ID use that stored
  entry URL when the index is unavailable. Other legacy requests keep their URL
  and receive the previous default template.
- Storage comparison and folder import normalize separators and case for Windows
  paths while preserving POSIX case sensitivity. Import remains idempotent and
  never moves or deletes media.
- Clearing finished work preserves paused tasks. Pause bookkeeping is cleared
  when the engine emits an explicit paused or terminal event.

## Verification

See [regression contracts](../testing/VIDEO_REGRESSION_CONTRACTS.md). Deterministic
process fixtures exercise Windows path/encoding behavior on every desktop OS in
CI. A separate loopback test runs real yt-dlp and FFmpeg to merge video and convert
audio, without contacting an external media provider. Real-source tests retain
their existing explicit opt-in.

Native Windows installation and playback still require a Windows machine. Linux
process tests and Android device checks are recorded separately from that claim.
