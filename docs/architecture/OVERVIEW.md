# Architecture overview

OpenYT is a Kotlin Multiplatform application with a pure domain/application core,
shared Compose UI, and thin platform hosts.

## Modules

```text
core/        Pure Kotlin Multiplatform (JVM + Android)
  domain/      Models, value objects, structured errors
  application/ Use cases and ports (DownloadEngine, repositories)
engine-ytdlp/ Pure Kotlin yt-dlp JSON/progress/error parsing (no process or I/O)
data/        SQLDelight schema, repositories, verified downloads, platform drivers
media-ffmpeg/ External FFmpeg processing and tagging adapter (shared JVM/Android)
browserExtension/ Kotlin/JS URL handoff; no extraction or download service
ui/          Compose Multiplatform screens and design system
androidApp/  Android host: Activity, platform port implementations
desktopApp/  Desktop host: main window, platform port implementations
```

Dependency direction:

```text
androidApp ──► ui ──► core
desktopApp ──► ui ──► core
androidApp ──► data ──► core
desktopApp ──► data ──► core
```

Nothing points back into a host. `core` has no platform or UI dependency.

## Core abstractions

- `DownloadEngine` — the only way to inspect and download media. Implementations
  are replaceable (`AndroidYtDlpEngine`, `DesktopYtDlpEngine`, `FakeDownloadEngine`).
- `MediaInfo`, `MediaFormat`, `DownloadRequest`, `DownloadEvent` — typed contracts.
- `DownloadFailure` — structured error taxonomy; raw tool output never reaches
  the UI.
- Use cases orchestrate ports and expose deterministic results to presentation.

## UI

Shared Compose screens receive state and callbacks. They never construct process
arguments, filesystem paths, or engine options. Host-specific behavior (sharing,
storage permission, window chrome) is injected through small ports.

## Library

`LibraryQuery` is a pure function over library entries: search, kind/availability/
duplicate filters, and duplicate detection (same path or normalized title+size).
File existence is checked through the `FileAvailability` port; the UI never touches
the filesystem. Duplicate detection is advisory and never deletes files. Library
entries persist a thumbnail URL; Coil loads it over the network with a placeholder
fallback. Collections are a many-to-many relation persisted in `collection` and
`collection_entry`; `LibraryController` owns them and never deletes media.

## Visual identity

`ui/theme/Theme.kt` defines complete light and dark Material3 palettes, including
surface container roles, so cards, navigation, chips, and backgrounds share one
neutral scale with a teal accent. `BrandMark` pairs the shared `BrandIconPainter`
with scalable text. The desktop host uses the same painter for the window and
supplies native icon files to jpackage. Android supplies adaptive launcher layers.
The [brand guidelines](../branding/README.md) link to the SVG master and platform
assets.

Provider glyphs use shared XML vectors supported by Android and desktop. Storage
messages are grouped inside `AppStrings` to keep the JVM constructor and generated
copy methods below the platform parameter limit. Storage rows are rendered lazily
and dialog actions remain outside the scrolling content.

## Technical metadata

`MediaProbe` is a core port for local file metadata. Android implements it with
`MediaExtractor`/`MediaMetadataRetriever`; desktop runs FFprobe and parses JSON.
The library details dialog probes on demand and never exposes raw tool output.

## Sharing and export

`MediaActions` is a core port for completed media. Android exposes files through a
read-only `FileProvider` URI and the system share/open choosers; desktop opens the
default app or reveals the file in the file manager. No direct third-party SDK is
embedded and no broad storage permission is required.

## Persistence and queue

SQLDelight/SQLite stores the queue snapshot, library, and app settings
(`download_task`, `library_entry`, `app_settings`). On startup, tasks left active are marked `INTERRUPTED`; nothing is
auto-resumed. Retry creates a fresh task. Removing a library entry deletes only the
record, never the file. Temporary directories are per task and cleaned at completion,
cancellation, and startup. See ADR-0005.

Final output confirmation uses tagged JSON paths and explicit UTF-8 on both hosts.
Schema 18 adds playback sessions, typed download options, subscriptions, and opt-in settings. Schema 14 also preserves the filename template and playlist item index so a restored
task repeats its original request. See [ADR-0020](../adr/0020-download-completion.md)
and the [video regression contracts](../testing/VIDEO_REGRESSION_CONTRACTS.md).

## Platform notes

- **Android**: yt-dlp is provided by `io.github.junkfood02.youtubedl-android`,
  which bundles Python 3.12, yt-dlp, FFmpeg, and QuickJS. The wrapper is GPL-3.0,
  so the app is GPL-3.0. See ADR-0002.
- **Desktop**: yt-dlp and FFmpeg are external executables invoked with structured
  argument lists (never a shell). See ADR-0003. The host adds a native folder
  chooser, an AWT drop target for links, and a jpackage distribution with the
  `java.sql` module. See ADR-0007.
- **Windows/macOS**: not yet validated; the desktop engine isolates OS differences
  behind a process port so validation can follow without touching the core.

## Explicit non-goals

- Reimplementing extraction, codecs, or DRM handling.
- Backend services, accounts, telemetry, or remote job processing.
- Executing unverified binaries or silent remote updates.

See [ADR-0021](../adr/0021-local-media-expansion.md) for playback/automation and
[ADR-0022](../adr/0022-verified-release-manifests.md) for release verification.
