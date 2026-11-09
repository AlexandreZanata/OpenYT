# ADR-0002: Android extraction through youtubedl-android

- Status: accepted with risks
- Date: 2026-11-09

## Context

yt-dlp is a Python application. Android has no system Python, and yt-dlp requires
Python >= 3.11 (verified against yt-dlp 2026.10.26). YouTube extraction additionally
requires a JavaScript runtime (EJS): without one, yt-dlp warns that extraction is
deprecated and some formats are missing (verified on Linux).

Candidates considered:

| Option | State | Notes |
|--------|-------|-------|
| `io.github.junkfood02.youtubedl-android` 0.18.1 | Active | Bundles Python 3.12.11, yt-dlp 2025.11.12, FFmpeg 7.1.1, QuickJS; GPL-3.0; updates yt-dlp from GitHub releases at runtime |
| Build Python + yt-dlp ourselves | Full control | High maintenance, large effort, still GPL/PSF obligations |
| Python-in-Termux / user-provided runtime | Active | Poor UX, not a self-contained app |
| Native ARChon-style wrappers | Unmaintained | Rejected |

## Decision

Use `io.github.junkfood02.youtubedl-android` for Android, behind the
`DownloadEngine` port. The engine injects a QuickJS runtime (the wrapper passes
`--js-runtimes quickjs:<path>` automatically). Allow a controlled, explicit
yt-dlp update operation; do not silently execute arbitrary downloaded binaries.

License consequence: linking this GPL-3.0 wrapper makes ClipBaú GPL-3.0.

## Risks

- The bundled yt-dlp (2025.11.12) predates the current release; YouTube changes
  frequently. `updateYoutubeDL` must be exercised and verified on device.
- The wrapper's `yt-dlp-ejs` packaging is not confirmed. If QuickJS alone is
  insufficient, records/formats will be missing; this must be measured, not assumed.
- Native library size is large; restrict to arm64-v8a initially and split APKs later.
- The wrapper targets older compile SDKs; validate on Android 16 (API 36).

## Toolchain initialization

`YoutubeDL.init(context)` alone is insufficient. The `ffmpeg` artifact exposes a
separate `FFmpeg.getInstance().init(context)` that extracts `libffmpeg.zip.so` into
`noBackupFilesDir/youtubedl-android/packages/ffmpeg`. Without it, ffmpeg/ffprobe fail
at runtime (`libavdevice.so.61` missing), breaking merge and audio extraction. Both
initializers are called before any engine work.

Temporary files are isolated per task with `--paths temp:<task-dir>` and removed on
completion, failure, or cancellation.

## Verification required before claiming YouTube support

Real-device run of: init, metadata inspect, format list, download, merge, progress,
cancel, and a structured failure. Record the yt-dlp version executed.
