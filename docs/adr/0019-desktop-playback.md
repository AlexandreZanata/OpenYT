# ADR 0019 — Desktop playback backend

## Status

Accepted (Program C2).

Windows packaging is superseded by [ADR-0024](0024-windows-media-and-shortcuts.md)
in 4.0.1: its installers bundle a verified libVLC runtime. Linux/macOS retain the
external-runtime policy below.

## Context

Program C2 asks for a desktop player and a documented dependency decision. The
project bundles yt-dlp, FFmpeg and FFprobe but not a media player. Candidates:

1. **VLCJ + libvlc** — embedded player with wide format support, but requires
   libvlc on the user's machine; not bundled.
2. **ffplay** — self-contained if bundled, but no macOS build in our current
   FFmpeg source and no in-app UI/queue.
3. **JavaFX Media** — bundled dependency, but limited containers/codecs
   (no WebM/MKV/Opus), which are common downloads.
4. **System default player** — always available, no in-app UI.

## Decision

Use **VLCJ** with runtime discovery of `libvlc`:

- If libvlc is found, play in an embedded VLCJ window.
- Otherwise, open the file with the operating system's default player.

`PlaybackLauncher.canPlay` is true on desktop in both cases, so the Library Play
action always works. The dependency (VLCJ 4.12.1, GPL-3.0, plus its JNA
transitive dependency) is added to `desktopApp` only.

## Consequences

- Embedded playback quality is limited by the user's VLC installation; without
  VLC the app still plays through the OS default.
- The desktop package grows by the VLCJ/JNA jars but not by a full media stack.
- A future C3 queue can drive VLCJ's `MediaListPlayer`; the port stays the same.
- Validated by `playback --check` (VLCJ discovery) and manual GUI playback.
