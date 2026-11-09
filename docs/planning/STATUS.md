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

## Phase 4 completed

- Android share-sheet intake (`ACTION_SEND`, `text/plain`): the first http(s) URL is
  extracted, validated, and inspected automatically; `singleTop` handles shares while
  the app is open.
- Settings persist in SQLite (`app_settings`, schema v2 with a `1.sqm` migration):
  theme (system/light/dark), language (pt-BR/en), and concurrent downloads (1–4).
  Theme and language apply immediately and survive process restarts.
- Concurrency can be changed at runtime; queued downloads start when a slot opens
  and cancelling a queued task prevents it from starting.
- Accessibility: screen titles expose heading semantics, download status uses a
  polite live region, progress bars expose a spoken description, navigation icons
  and concurrency buttons have content descriptions; UI verified at 1.3× font scale.
- Database migration v1→v2 was applied on a real device with existing queue and
  library rows preserved.

Validation:

- 34 core tests, 13 engine tests, 8 data tests, 6 Android device tests.
- Share intent delivered to the package and auto-inspected "Big Buck Bunny" on the
  device (MIUI routes `ACTION_SEND` through its resolver unless the package is
  targeted explicitly; the installed filter is verified).
- Settings changed to English/Dark/3, force-stopped, relaunched, and restored;
  then reset to pt-BR/System/2 and restored again.
- Font scale 1.3 rendered correctly; progress semantics exposed "Big Buck Bunny: 0%".

## Known limitations

- No automatic retry/backoff; retry is manual.
- Share intake accepts `text/plain` only; `ACTION_VIEW`, multiple-item shares, and
  the system chooser selection flow are not covered by automated tests.
- Download folder is fixed to app-specific storage; SAF selection is not implemented.
- No pause support (engine only supports cancellation).
- No subtitle download, collections, media playback, search, or filters yet.
- Only arm64-v8a Android artifacts; no ABI splits.
- Windows/macOS desktop compile is verified in CI, but runtime downloads, JS runtime
  detection, and packaging are not.
- Strings are centralized in Kotlin but not yet backed by Compose resources.
- Corrupt databases disable persistence with a logged error and the app continues in
  memory.

## Next step (Phase 5/6)

1. Desktop layout pass: sidebar, drag-and-drop links/files, keyboard shortcuts, and
   multi-selection.
2. Library search, filters, collections, missing-file detection, and duplicates.
3. SAF folder selection and storage usage on Android.
4. Structured diagnostics with redaction and release packaging.

Every claim above must be backed by a command or test recorded in the repository.
