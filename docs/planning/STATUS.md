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

## Phase 5 completed

- Desktop layout uses a persistent sidebar and wide-screen list actions; a native
  folder chooser lets the user pick the download directory, persisted in settings.
- Desktop drag-and-drop accepts dropped text/URI links, extracts the first http(s)
  URL, and runs the same inspect flow as a share.
- Keyboard shortcuts: Ctrl+1…Ctrl+4 switch tabs; Ctrl+Q quits the desktop app.
- Wide desktop lists support multi-selection for batch remove (library) and dismiss
  (finished downloads).
- Compose Desktop packaging configured for deb/rpm/msi/dmg; the Linux `.deb`
  (`openyt_0.1.0_amd64.deb`) was built and the extracted `/opt/openyt` app was
  launched successfully with the bundled runtime and SQLite persistence.
- Packaging fix: the runtime includes the `java.sql` module, required by the JDBC
  SQLDelight driver; without it the packaged app silently fell back to in-memory.

Validation:

- Desktop drop parser tests pass; 35 core, 13 engine, 9 data, and 6 desktop tests.
- Linux package metadata verified (package `openyt`, version `0.1.0`, maintainer,
  description); the installed-layout binary runs from the extracted deb.
- Desktop app screenshot: `docs/assets/phase5-desktop.png`.
- Android v2→v3 database migration preserved queue and settings; 6 device tests pass.

## Phase 6 completed (library)

- Search by title or file name, plus filters for all/video/audio, missing files,
  and possible duplicates.
- Missing-file detection uses the `FileAvailability` port; results recompute when
  the library changes and on demand via "Verify files".
- Duplicate detection is advisory: entries sharing a file path or normalized
  title+size are marked "Possible duplicate". Nothing is deleted automatically.
- Library rows expose technical details (path, size, kind, availability/duplicate)
  in a dialog.
- Large-data behaviour: a pure `LibraryQuery` is covered by a 10,000-entry unit test;
  a real device loaded 5,003 entries, searched immediately, and showed the missing
  badge and "Missing" filter correctly.

Validation:

- 39 core tests, 13 engine tests, 9 data tests, 6 desktop tests, 6 Android device
  tests. CI green.
- Device library screenshot: `docs/assets/phase6-android-library.png`.
- The device was restored to its original two-entry library after the bulk test.

## Phase 7 completed (visual identification and library polish)

- Thumbnails are shown in the inspect result, Downloads, and Library on Android and
  desktop, using Coil 3 (OkHttp on both JVM targets) with a neutral placeholder.
- Library entries persist `thumbnail_url` (schema v4). The v3→v4 migration backfills
  existing library rows from their download task, so old downloads gain thumbnails
  without being re-downloaded.
- Library sorting by title is available alongside the existing filters.
- Safe duplicate cleanup removes extra library records while keeping one copy; the
  file is never deleted, and the confirmation dialog states this.
- Version bumped to 0.2.0 (Android versionCode 2; Linux deb `openyt_0.2.0_amd64.deb`).

Validation:

- Android: thumbnails rendered in Home, Downloads, and Library; duplicate removal
  left one entry and the 322 KB file on disk; screenshots in `docs/assets/phase7-*`.
- Desktop: the packaged app rendered a library thumbnail; screenshot in
  `docs/assets/phase7-desktop-thumbnail.png`.
- v3→v4 migration backfill covered by a data test; the real device migrated and
  showed thumbnails for pre-existing entries.
- 39 core tests, 13 engine tests, 10 data tests, 6 desktop tests, 6 Android device
  tests. CI green.

## Known limitations

- No automatic retry/backoff; retry is manual.
- Share intake accepts `text/plain` only; `ACTION_VIEW` and multiple-item shares are
  not implemented.
- Desktop drag-and-drop accepts links only; local file import is not implemented.
- Desktop folder chooser and multi-selection are not covered by UI automation in the
  current headless environment; their persistence and parsing are unit-tested.
- Windows/macOS packages are configured but not built or validated.
- Download folder on Android is still app-specific; SAF selection and storage usage
  are pending.
- No pause support (engine only supports cancellation).
- Collections, media playback, and subtitle download are not implemented.
- Thumbnails require the source URL; offline and uncached entries show the
  placeholder. No thumbnail files are stored permanently.
- Duplicate handling is detection only; there is no bulk duplicate cleanup action.
- Library technical details do not probe codecs with FFprobe; they show path, size,
  kind, and availability.
- Library filters reset when leaving the tab; sorting options are not implemented.
- Only arm64-v8a Android artifacts; no ABI splits.
- Strings are centralized in Kotlin but not yet backed by Compose resources.
- Corrupt databases disable persistence with a logged error and the app continues in
  memory.

## Next step

1. Collections and codec probing.
2. SAF folder selection, storage usage, and import of existing files on Android.
3. Structured diagnostics with redaction and Windows/macOS packaging validation.
4. Release/update strategy and signing.

Every claim above must be backed by a command or test recorded in the repository.
