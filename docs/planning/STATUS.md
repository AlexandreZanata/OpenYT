# Project status

Updated: 2026-11-09

## Completed

- Phase 0 viability, with real execution evidence:
  - Linux: metadata, download, integrity, progress, EJS requirement.
  - Android real device: metadata (archive.org and YouTube), 332 MB download,
    progress, cancel, and end-to-end UI inspection.
- Phase 1 foundation:
  - Gradle Kotlin DSL, version catalog, wrapper pinned to 8.14.4.
  - Modules: `core`, `engine-ytdlp`, `ui`, `androidApp`, `desktopApp`.
  - Pure domain/ports (`DownloadEngine`, structured failures, typed events).
  - URL validation and `InspectMediaUseCase` with unit tests.
  - yt-dlp JSON/error/progress parsing with unit tests.
  - Android engine backed by youtubedl-android (QuickJS bundled).
  - Desktop engine driving an external yt-dlp binary with argument lists.
  - Shared Compose UI (pt-BR strings, black/white theme, adaptive navigation).
  - CI: Linux unit/build job and cross-OS desktop compile matrix.

## Phase 2 completed

- Download action in the UI with video/audio selection and concrete format choice.
- In-session Downloads list with progress, speed, ETA, cancel, dismiss, and
  structured errors; completed files appear in the Library tab.
- Per-task temporary directories (`--paths temp:...`) are removed on completion,
  failure, and cancellation.
- Android real device (arm64): YouTube video download with FFmpeg merge and audio
  extraction to m4a pass; cancellation cleans temporary files; all 5 network tests
  pass.
- Linux desktop: YouTube merge is validated with `ffprobe`, confirming both video
  and audio streams; progress and cancel pass.
- Fixes discovered by real execution: call `FFmpeg.getInstance().init()` in addition
  to `YoutubeDL.init()`, pass `--no-quiet` when using `--print`, and keep per-task
  temp paths.

## Phase 3 completed

- SQLDelight/SQLite schema v1 (`download_task`, `library_entry`) with Android and
  desktop drivers; database lives in app-private/platform data storage.
- Queue and library survive process restarts. Active tasks are restored as
  `INTERRUPTED`; retry starts a fresh task instead of auto-resuming.
- Duplicate active requests (same URL, kind, format) reuse the existing task.
- Maximum of two concurrent downloads; queued tasks wait for a permit.
- Removing a library entry deletes only the record; the file is never deleted.
- Per-task temporary directories and stale `.openyt-tmp-*` directories are removed.
- Persistence writes are serialized; progress is persisted at 10% intervals.

Validation:

- 23 core tests, 13 engine tests, 5 data persistence tests, 1 Android database test.
- Android real device: force-stop during download restored the task as Interrupted;
  retry completed the file; force-stop after completion restored both the task and
  the library entry; removing the library entry kept the 59 MB file on disk.
- CI green on Linux build and desktop compile matrix.

## Known limitations

- No automatic retry/backoff; retry is manual.
- Concurrency is fixed at two downloads and not user-configurable.
- Schema v1 has no migration step yet; corrupt databases disable persistence with a
  logged error and the app continues in memory.
- No pause support (engine only supports cancellation).
- No subtitle download, collections, media playback, search, or filters yet.
- Only arm64-v8a Android artifacts; no ABI splits.
- Windows/macOS desktop compile is verified in CI, but runtime downloads, JS runtime
  detection, and packaging are not.
- Strings are centralized but not yet backed by Compose resources.
- No i18n locale switching yet (pt-BR is the default; English strings exist).
- App settings (folder, language, theme, concurrency) are not persisted.

## Next step (Phase 4/6)

1. Android share-sheet intake and a complete inspect → download → library flow.
2. Accessibility pass (TalkBack, focus, contrast, scaling) and Compose resources.
3. Library search, filters, collections, missing-file detection, and duplicates.
4. Persist app settings and make concurrency configurable.
5. Add structured diagnostics with redaction and release packaging.

Every claim above must be backed by a command or test recorded in the repository.
