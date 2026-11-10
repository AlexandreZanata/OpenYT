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
- Version set to 1.0.0 (Android versionCode 7) so the macOS app-image version is compatible with jpackage.

Validation:

- `:androidApp:assembleRelease` builds a signed (debug key fallback) APK locally.
- `openyt_1.0.0_amd64.deb` builds with correct metadata.
- Android screenshots: `docs/assets/phase11-android-codex-identity.png` and
  `phase11-android-codex-dark.png`.
- Release **v1.0.0** published successfully by the workflow with
  `androidApp-release.apk`, `openyt_1.0.0_amd64.deb`, `OpenYT-1.0.0.msi`,
  `OpenYT-1.0.0.dmg`, and `SHA256SUMS.txt`.
- macOS `jpackage` rejects zero-major versions; the first release uses 1.0.0 and
  the constraint is documented in `RELEASE_PIPELINE.md`.

## Phase 12 completed (SAF import, diagnostics, update check)

- Android import uses the Storage Access Framework document picker
  (`OpenMultipleDocuments`) and copies selected video/audio files into
  app-owned `downloads/imported/` storage. Imported entries are persisted,
  probed, shareable, and appear in the library without broad storage permissions.
- Diagnostics show app version, platform, engine, redacted paths, counts, and tool
  availability. Paths are redacted (`~/...`), URLs lose credentials/queries, and
  the formatted snapshot can be copied to the clipboard.
- Update check queries the latest GitHub release, compares SemVer, and opens the
  release page when a newer version exists. Automatic installation is intentionally
  not enabled for unsigned artifacts.
- Version bumped to 1.1.0 (Android versionCode 8).

Validation:

- Android SAF picker imported a real MP4 into `downloads/imported`; the library
  showed the entry with share/open/details actions.
- Diagnostics showed `1.1.0`, Android 16, redacted `~/downloads` and
  `~/databases/openyt.db`, counts, and tool availability; clipboard copy confirmed.
- Update check returned "no update" while the latest published release was older.
- 50 core tests, 13 engine tests, 12 data tests, 9 desktop tests (4 network opt-in
  skipped), and 8 Android device tests. Screenshot:
  `docs/assets/phase12-android-diagnostics.png`.

## Phase 13 completed (icons, SAF destination, assisted updates, production APK)

- Integrated PR #1 branding: shared BrandIcon/wordmark, window icon, adaptive
  Android launcher icon, and Linux/Windows/macOS installer icons. Verified the
  branded PNG is embedded in the `.deb` and the APK uses the adaptive launcher.
- Android SAF destination: choose an external tree with persistable permission;
  completed downloads are copied into it while the app keeps its local copy for
  library, sharing, and probing.
- Update flow now offers "Baixar e instalar": downloads the platform asset from the
  GitHub release and opens the OS package installer (assisted, not silent).
- Android production signing: a local 4096-bit RSA keystore was generated outside
  the repository and the four signing secrets were configured. The next APK is
  signed with `CN=OpenYT`, not the Android debug key.
- Windows/macOS trusted signing/notarization still requires external certificates
  and Apple credentials; the requirements are documented in `SIGNING.md`. Artifacts
  remain unsigned until those credentials are supplied.
- Version bumped to 1.2.0 (Android versionCode 9).

## Phase 14 completed (automatic update banner)

- On entering the app, `UpdateController` checks the latest GitHub release once per
  process, using the GitHub API without any server.
- The release is filtered to the device platform: APK on Android, DEB/MSI/DMG on
  desktop.
- When a newer release exists, a dismissible teal banner appears above every tab
  with the version, "Baixar e instalar" (assisted OS installer), and "Abrir release".
- Settings shares the same update state and can re-check manually; failures are
  shown explicitly there.
- Version bumped to 1.3.0 (Android versionCode 10).

Validation:

- Desktop auto-check found the published `v1.2.0` release from a `1.1.0` context and
  rendered the banner; screenshot in `docs/assets/phase14-update-banner.png`.
- `UpdateController` unit tests cover automatic-once, manual re-check, platform
  filtering, up-to-date, and failure.
- 55 core tests, 13 engine tests, 12 data tests, 9 desktop tests (4 network opt-in
  skipped). Android device runtime validation remains pending because the phone is
  disconnected from ADB.

## Phase 15 completed (self-contained desktop packages)

- Desktop packages now embed yt-dlp 2026.10.26, FFmpeg/FFprobe (static `b6.1.1`),
  and Deno 2.9.8; end users install only the OpenYT package.
- The packaging task downloads the pinned tools and includes them through
  `appResourcesRootDir`. At first run they are copied to a user-writable cache
  with the executable bit restored.
- `ExecutableLocator` prefers bundled tools, and JavaScript runtimes are passed
  to yt-dlp as `deno:<path>`/`node:<path>`.
- Linux `openyt_1.3.1_amd64.deb` built at 215 MB. The failing YouTube example
  downloads successfully with the bundled tools (network integration test).
- Version bumped to 1.3.1 (Android versionCode 11).

## Phase 16 completed (playlists and provider icons)

- Inspection now returns either a single media item or a playlist/channel with
  selectable entries. Playlist parsing is covered by unit tests and a real-network
  desktop integration test.
- Selected entries are enqueued as individual download tasks into a folder named
  after the playlist, with numbered file names; unsafe folder characters are
  sanitized and path traversal is rejected.
- Video and audio modes apply to every selected entry.
- Added provider SVGs from Simple Icons for YouTube, Instagram, TikTok, X,
  Facebook, SoundCloud, Vimeo, Twitch, Reddit, Pinterest, Dailymotion, Bandcamp,
  Threads, Telegram, Bilibili, and Rumble, with a generic icon fallback.
- Extraction remains delegated to yt-dlp, so site coverage follows its extractors.
  Image and subtitle downloads are still not implemented.
- Download cards use an animated, rounded gradient progress bar with inline
  percentage and speed/ETA beneath it.
- Version bumped to 1.4.1 (Android versionCode 13).

## Phase 17 completed (validation foundation)

- Desktop binary exposes a headless CLI (`--cli inspect|download|playlist|library|version`)
  that reuses the same core use cases as the UI.
- `scripts/validate-linux.sh` builds/extracts the real `.deb`, uses isolated
  `XDG_*` directories, and validates tools, inspect, download, playlist layout,
  library access and GUI startup with a screenshot.
- Compose desktop UI tests cover the animated progress bar (golden image) and the
  Downloads screen semantics.
- Database migration tests cover v6 to v7 and provider-source round-trips.
- CI has an opt-in scheduled/manual real-network integration job.

## Phase 18 completed (pipeline reliability core)

- Queue scheduling respects `DownloadPriority` (high, normal, low) with FIFO
  ordering inside the same priority. The priority can be changed from Downloads.
- Transient failures (network, unavailable, unexpected) retry automatically with
  exponential backoff (2s, 4s, 8s; max 3 attempts by default).
- Interrupted tasks can be resumed in bulk from the Downloads header.
- Engines now reject completions whose output file is missing or empty.
- Database schema 8 adds `priority` and `attempt` to `download_task`.
- CLI download/playlist accept `--priority high|normal|low`.
- Pause/resume, bandwidth limits and time windows remain for the next phase.
- Version bumped to 1.5.0 (Android versionCode 14).

## Phase 19 completed (pause and resume)

- New `PAUSED` status and real pause/resume without losing partial files.
- Desktop and Android engines keep the temporary directory when paused; stale
  temp cleanup now waits 7 days so paused/interrupted progress survives restarts.
- Pause works on queued tasks (immediately) and on active tasks (engine pause).
- Resume requeues the same task id so yt-dlp continues from the partial file.
- UI adds Pause, Resume and Cancel actions; paused tasks survive database restore.
- Real-network integration test pauses a YouTube download, keeps the temp
  directory, resumes it and verifies the final file.

- Version bumped to 1.6.0 (Android versionCode 15).

## Phase 20 completed (bandwidth and download window)

- Global speed limit (unlimited, 1, 5 or 10 MB/s) passed to the engine as
  `--limit-rate`.
- Download window presets (disabled, 00–06, 22–06); queued tasks wait outside the
  window and wake automatically when it opens.
- Settings persisted in schema 9 (`speed_limit_bytes_per_second`,
  `download_window_start_minutes`, `download_window_end_minutes`).
- CLI accepts `--limit-bytes-per-second`; Linux validation downloads with a
  200 kB/s limit.

- Version bumped to 1.7.0 (Android versionCode 16).

## Phase 21 completed (per-entry playlist formats)

- Playlist entries can open a format dialog that inspects only that entry on demand.
- The dialog lists video/audio formats, "best available" and "use default"; the
  selection is stored as an override for that entry.
- `EnqueuePlaylistUseCase` accepts per-entry `PlaylistEntrySelection` overrides and
  falls back to the playlist-wide video/audio choice.
- Core planner test and Compose dialog UI test cover the new path.

- Version bumped to 1.8.0 (Android versionCode 17).

## Phase 22 completed (organization presets and templates)

- New organization presets: playlist, playlist + channel, playlist + year,
  channel, and year, using yt-dlp metadata for channel/year folders.
- Editable file-name template with validation (no path separators, no `..`, must
  contain `%(ext)s`); invalid templates fall back to the default.
- Presets and template are persisted in schema 10 and applied to both single
  downloads and playlist entries.
- CLI accepts `--preset` and `--template`; Linux validation downloads a playlist
  entry with the channel preset and asserts the uploader folder.

- Version bumped to 1.9.0 (Android versionCode 18).

## Phase 23 completed (encrypted local cookies)

- Optional browser cookies import: paste a Netscape `cookies.txt` in Settings.
- Cookies are encrypted at rest with AES-GCM in the app-private directory; the
  key is generated locally and never leaves the device.
- Each download receives a short-lived 0600 plaintext file, deleted after the
  task finishes; cookie content is never logged or printed.
- In-app warning about authorised content and terms.
- CLI adds `cookies --import/--check/--clear`; Linux validation asserts the
  encrypted file exists, does not contain the canary, and is removed on clear.

- Version bumped to 2.0.0 (Android versionCode 19). Program A is complete.

## Phase 24 completed (library tags)

- Library entries can have tags; tags are stored in the new `library_tag` table
  (schema 11) and survive restarts.
- Tag search is case-insensitive and included in the Library query alongside
  title, file name and source.
- Details dialog adds/removes tags with chips and an input field.
- CLI `library --entry <id> --add-tag/--remove-tag` and JSON output include tags;
  Linux validation seeds an entry, adds and removes a tag on the packaged image.

- Version bumped to 2.1.0 (Android versionCode 20).

## Phase 25 completed (smart collections)

- Named smart collections stored in schema 12 and evaluated at read time.
- Filters support search text, kind, exact tag, min/max size and "added within
  N days"; membership updates automatically as the library changes.
- Library UI adds smart collection chips, a creation dialog, and deletion.
- CLI: `--create-smart`, `--delete-smart` and `--smart <id>` (evaluated output);
  Linux validation creates a tag-based smart collection and asserts membership.

- Version bumped to 2.2.0 (Android versionCode 21).

## Phase 26 completed (metadata search)

- Library entries store the author (schema 13), populated from the download media.
- Search covers title, author, file name, source and tags, with relevance ranking
  (exact/prefix title first, then author, tag, source and file name).
- CLI `library --search <query>` returns the ranked results; Linux validation seeds
  mixed entries, asserts an author match and an empty result.

- Version bumped to 2.3.0 (Android versionCode 22).

## Phase 27 completed (metadata editing and embedded tags)

- Library details dialog edits title and author without renaming the file.
- Desktop embeds title/artist (and cover art for MP4/M4A) with the bundled FFmpeg;
  the tagged copy replaces the original only after success.
- Android reports embedding as unsupported because youtubedl-android does not
  expose a public FFmpeg command runner; the DB metadata is still updated.
- Opt-in tagger test generates a fixture with FFmpeg, writes tags and re-probes
  them; Linux validation runs it with the packaged tools.

- Version bumped to 2.4.0 (Android versionCode 23).

## Phase 28 completed (library export/import)

- JSON archive (schema version 1) with entries, tags, collections, membership,
  smart collections and optional file paths.
- Merge policy keeps local paths and merges tags; replace policy uses archive
  paths. Missing files and duplicate paths are imported and flagged by the UI.
- Library UI can export to a JSON text (copy) and import by pasting JSON.
- CLI `library --export/--import`; Linux validation exports, wipes and imports
  the database, asserting entries, tags, collections and smart collections.

- Version bumped to 2.5.0 (Android versionCode 24).

## Phase 29 completed (watch folder import)

- Folder scanner (desktop/JVM) finds supported video/audio files without copying,
  moving or deleting anything.
- Folder import is idempotent by file path; repeated scans skip existing entries.
- Library UI adds "Import folder" with a path field and result counts; CLI adds
  `library --scan <directory>`.
- Linux validation creates a fixture folder, imports twice, asserts counts and
  compares file hashes before/after.

- Version bumped to 2.6.0 (Android versionCode 25).

## Phase 30 completed (storage panel)

- Read-only storage report: totals, largest files, duplicate groups, missing
  entries and orphan files found on disk but absent from the library.
- Library UI opens the report in a dialog; no file is deleted automatically.
- CLI adds `library --storage [--storage-dir <dir>]`.
- Linux validation uses an isolated database with a duplicate, a missing entry
  and an orphan file, asserts counts and verifies files are unchanged.

- Version bumped to 2.7.0 (Android versionCode 26).

## Known limitations

- No automatic retry/backoff; retry is manual.
- Share intake accepts `text/plain` only; `ACTION_VIEW` and multiple-item shares are
  not implemented.
- Desktop drag-and-drop accepts links only; local file import is not implemented.
- Desktop folder chooser and multi-selection are not covered by UI automation in the
  current headless environment; their persistence and parsing are unit-tested.
- Windows/macOS packages are built in CI but not validated on real devices.
- Download folder on Android is still app-specific; SAF selection and storage usage
  are pending.
- No pause support (engine only supports cancellation).
- Playlist items download with the best available quality; per-entry format selection
  is not implemented. Folder organization uses the playlist title only.
- Media playback and subtitle download are not implemented.
- SAF destination copies completed files to the chosen tree; the app keeps its local
  copy instead of moving the only file.
- Updates download the installer and require OS/user confirmation; silent install is
  not implemented. Automatic checks run once per process; manual re-check is in
  Settings. Windows/macOS artifacts are unsigned until certificates are set.
- Back up `~/.openyt/android-release.jks`; losing it prevents installing updates over
  production-signed APKs.
- Desktop has no file import yet (Android-only SAF import).
- Android probing may omit audio codec/bitrate when the extractor does not expose
  them; desktop probing requires FFprobe, which release packages bundle.
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

1. SAF download destination selection and storage usage.
2. Code signing/notarization for Windows and macOS.
3. Signed auto-update/install flow and release channels.

Every claim above must be backed by a command or test recorded in the repository.
