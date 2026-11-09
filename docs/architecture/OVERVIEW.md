# Architecture overview

OpenYT is a Kotlin Multiplatform application with a pure domain/application core,
shared Compose UI, and thin platform hosts.

## Modules

```text
core/        Pure Kotlin Multiplatform (JVM + Android)
  domain/      Models, value objects, structured errors
  application/ Use cases and ports (DownloadEngine, repositories)
engine-ytdlp/ Pure Kotlin yt-dlp JSON/progress/error parsing (no process or I/O)
data/        SQLDelight schema, repository, and platform drivers
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

## Persistence and queue

SQLDelight/SQLite stores the queue snapshot, library, and app settings
(`download_task`, `library_entry`, `app_settings`). On startup, tasks left active are marked `INTERRUPTED`; nothing is
auto-resumed. Retry creates a fresh task. Removing a library entry deletes only the
record, never the file. Temporary directories are per task and cleaned at completion,
cancellation, and startup. See ADR-0005.

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
