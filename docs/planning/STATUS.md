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

## Phase 8 completed (collections and desktop presentation)

- Collections are implemented as many-to-many membership (schema v5): create,
  rename rejection by duplicate name, assign/unassign entries, filter the library
  by collection, and delete collections. Deleting a collection never deletes files
  or library entries.
- Collection membership is persisted in SQLite; deleting a library entry also
  clears its memberships.
- Desktop library and download cards use a 240 dp thumbnail on wide screens with
  the details column beside it, a rounded/bordered image, and responsive actions.
  Mobile keeps the compact 96 dp thumbnail.
- Fixed a dark-theme desktop defect: the wide layout now paints the themed
  background with `Surface`, so labels no longer render on an unthemed light
  window.
- Version bumped to 0.3.0 (Android versionCode 3; `openyt_0.3.0_amd64.deb`).

Validation:

- Android: created "Favoritos", assigned the YouTube entry, filtered by it, and
  verified the persistent collection; screenshot in
  `docs/assets/phase8-android-collections.png`.
- Desktop: wide library rendered the larger thumbnail, collections, and filters on
  a consistent dark theme; screenshot in
  `docs/assets/phase8-desktop-large-thumbnail.png`.
- Collections CRUD/membership covered by core and data tests; Linux `.deb` 0.3.0
  built with correct metadata.

## Phase 9 completed (visual identity and technical details)

- Reworked the Material3 palettes for light and dark themes with a coherent
  black/neutral + teal identity. Selected chips, navigation indicators, progress,
  and the brand mark use teal containers; no lavender/purple surface roles remain.
  Surface container roles are defined explicitly, keeping cards and navigation bars
  on the same neutral scale as the background.
- Readable contrast in both themes; filters wrap with `FlowRow` instead of clipping.
- Added the OpenYT brand mark (play glyph + wordmark) to the desktop sidebar and the
  mobile Home header.
- Added a `MediaProbe` port: Android uses `MediaExtractor` +
  `MediaMetadataRetriever`; desktop uses FFprobe with a tested JSON parser. The
  library details dialog shows duration, resolution, video/audio codecs, bitrate,
  and container on demand.
- Version bumped to 0.4.0 (Android versionCode 4; `openyt_0.4.0_amd64.deb`).

Validation:

- Android: dark Home/Library, light Library, and technical details
  (0:18, 320x240, avc, mp4). Screenshots in `docs/assets/phase9-android-*`.
- Desktop: dark theme with brand, teal chips, collections, and the 240 dp thumbnail;
  screenshot in `docs/assets/phase9-desktop-dark.png`.
- Desktop probe integration generated a 320x240 H.264/AAC fixture and read it back
  through FFprobe.
- 43 core tests, 13 engine tests, 12 data tests, 9 desktop tests (4 network opt-in
  skipped), and 6 Android device tests. CI green.

## Phase 10 completed (sharing and export)

- Android sharing uses a `FileProvider` content URI and the system chooser
  (`ACTION_SEND`/`ACTION_SEND_MULTIPLE`) with read-only URI grants. Completed
  downloads and library entries expose "Compartilhar"; WhatsApp, Drive, YouTube,
  Quick Share, Messaging, and other installed handlers appear in the chooser.
- "Abrir" hands the file to a system viewer; no broad storage permission is added.
- Multi-selected library entries can be shared together on wide screens.
- Desktop provides "Abrir" and "Mostrar na pasta" (default app / file manager);
  desktop sharing is intentionally left to the OS.
- Version bumped to 0.5.0 (Android versionCode 5; `openyt_0.5.0_amd64.deb`).

Validation:

- Android chooser listed WhatsApp and Drive for the downloaded MP4; screenshot in
  `docs/assets/phase10-android-share-chooser.png`.
- "Open with" listed system video players.
- A FileProvider instrumentation test reads the shared file back through its
  content URI. The six existing device tests remain green.
- Debug APK and test APK build; Linux packaging remains in CI.

## Phase 11 completed (release pipeline and Codex-inspired identity)

- Added `.github/workflows/release.yml`: every push to `main` builds the Android
  release APK, Linux deb, Windows MSI, and macOS DMG on native runners, generates
  checksums, and creates/updates the `v<version>` GitHub release.
- `gradle.properties` is the single source of truth for `openyt.version` and
  `openyt.versionCode`; Android and desktop builds read it.
- Android release signing is optional through `ANDROID_KEYSTORE_BASE64` and related
  secrets. Without them the workflow publishes a debug-signed APK for testing.
- Adopted a Codex-inspired visual language: near-black surfaces, mint accent,
  monospaced OpenYT wordmark, rounded/bordered media cards. The OpenYT name and
  brand are preserved; no official Codex logo assets were copied.
- Version bumped to 0.6.0 (Android versionCode 6).

Validation:

- `:androidApp:assembleRelease` builds a signed (debug key fallback) APK locally.
- `openyt_0.6.0_amd64.deb` builds with correct metadata.
- Android screenshots: `docs/assets/phase11-android-codex-identity.png` and
  `phase11-android-codex-dark.png`.
- The release workflow runs on the next `main` push; Windows/macOS build results
  are reported by GitHub Actions.

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
- Media playback and subtitle download are not implemented.
- Android probing may omit audio codec/bitrate when the extractor does not expose
  them; desktop probing requires FFprobe on the machine.
- Collections have no rename UI (duplicate names are rejected; delete/recreate is
  available).
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

1. SAF folder selection, storage usage, and import of existing files on Android.
2. Structured diagnostics with redaction.
3. Code signing/notarization for Windows and macOS, and an update strategy.

Every claim above must be backed by a command or test recorded in the repository.
