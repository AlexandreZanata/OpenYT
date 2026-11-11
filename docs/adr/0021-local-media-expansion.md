# ADR-0021: Local playback, processing, and automation

- Status: accepted
- Date: 2026-11-11

## Decision

The shared core owns typed download options, playback checkpoints, local
subscription rules, URL handoff validation, and the media-processing port.
SQLDelight schema 18 persists these settings without resetting existing language
choices or library records. New installations follow the OS language, with
Portuguese and English catalogs and an explicit override.

`media-ffmpeg` is a shared JVM/Android adapter to the existing external FFmpeg.
It creates uniquely named sibling outputs with no overwrite, validates success,
and preserves the original. Lossless cuts align to codec keyframes. It also
provides metadata embedding to both hosts. No codec is implemented in Kotlin.

Android uses Media3 and system PiP. Desktop uses VLCJ with a separately installed
libvlc, a floating window, shortcuts, a seek control, and persistent positions.
The desktop timer checkpoints the actually loaded item, separately from the
selected queue item. A Resume action consumes the saved queue after restart.
Without libvlc, the OS player remains the documented fallback; embedded queue,
resume, and mini-player controls require libvlc.

RSS/Atom and playlist rules run only while the app process is alive. The first
check records a baseline unless existing entries were explicitly requested.
Queue persistence is confirmed before saving seen IDs. Stable origin keys prevent
duplicate episodes if the process stops between those writes. Failed checks obey
the configured interval. Feed parsing rejects external entities and oversized XML.

Clipboard suggestions and download notifications default off. Clipboard reads
occur only while the host is in the foreground. Notifications do not replay
restored history. Desktop notification availability depends on a system tray.

The browser handoff source is Kotlin/JS, compiled into a browser extension using
the existing Kotlin compiler. It uses only `activeTab` and the installed browser's
runtime. It ships no Node service, scraper, or download engine. The deep link
prefills a URL and requires the user's normal in-app download action.

## Consequences

- Subtitle selection, audio presets, chapter preservation, opt-in SponsorBlock
  marking, bounded live capture, and segments use structured engine options.
- Standalone subtitles confirm their declared path after yt-dlp moves the sidecar;
  they never scan unrelated files to infer success.
- Subscription scheduling is not an Android background-service promise.
- SponsorBlock is a separately labeled, opt-in third-party request.
- Existing external dependencies and licenses are reused. Kotlin/JS uses the
  same Apache-2.0 Kotlin toolchain; no npm packages are required for the popup.
