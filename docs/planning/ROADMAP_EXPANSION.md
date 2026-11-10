# OpenYT Expansion Roadmap

Status: Phase 0 (validation foundation) implemented. Program A (pipeline) is complete: A1–A8 implemented. Program B1 (tags), B2 (smart collections), B3 (metadata search), B4 (metadata editing/embedded tags), B5 (library export/import) Program B (library/media) is complete: B1–B8 implemented. Program C1 (Android playback) implemented. C2–C6 and Programs D–G are planning only.

This roadmap covers the seven expansion areas requested after v1.4.1. It is
organised into programs (A–G), each split into microphases (A1, A2, …). Every
microphase lists implementation, automated tests, a **real Linux validation**
procedure, and exit criteria.

## Principles and constraints

- Kotlin only in product code; Android and desktop share logic through KMP.
- `core` stays free of Compose, Android SDK, process and filesystem APIs.
- The engine is always accessed through `DownloadEngine`; yt-dlp and FFmpeg are
  external and must never be reimplemented.
- No backend, cloud service, account system or remote download server.
- Local-first: everything runs and is stored on the user's device.
- No DRM bypass. Features must only operate on content the user is authorised to
  obtain, within the source's terms.
- Durable decisions are recorded as ADRs.

## Validation strategy

Three layers, all required before a microphase is marked done.

1. **Deterministic unit tests** (`core`, `engine-ytdlp`, `data`, `ui`).
   Pure logic, fake engines, in-memory SQLite, parser fixtures.
2. **Integration tests** (opt-in network tests, `OPENYT_RUN_NETWORK_TESTS=1`).
   Real yt-dlp/FFmpeg/Deno against public, authorised sources.
3. **Real Linux validation** against the packaged image, never only Gradle.
   - Build the real package: `./gradlew :desktopApp:packageDeb`.
   - Extract to an isolated prefix and run the real binary:
     `dpkg-deb -x ... && XDG_DATA_HOME=... XDG_CACHE_HOME=... ~/.local/opt/openyt/bin/OpenYT`.
   - Inspect real outputs: files on disk, folder layout, SQLite state, logs,
     window screenshot via ImageMagick `import`.
   - No global state leaks: always use isolated `XDG_*` dirs per scenario.

### Validation harness to build first (Phase 0)

- `desktopApp` gains a headless entry point that mirrors the UI use cases
  (`inspect`, `enqueue`, `playlist`, `library`) so features can be tested without
  the GUI. This is the primary real-validation driver.
- `scripts/validate-linux.sh`: builds the `.deb`, installs into an isolated
  prefix, runs scenario IDs, asserts artifacts, and exits non-zero on failure.
- UI golden tests with Compose UI test APIs (`captureToImage`) for the screens
  that change.
- A manual checklist for interactions that cannot be automated reliably on
  X11/Skiko (documented as manual, with screenshot evidence).

Note: direct X11 synthetic input (XTest) is not delivered to Skiko/XInput2 in
this environment, so GUI interaction is validated through the headless harness,
UI golden tests, seeded state, and screenshots, not synthetic clicks.

## Delivery waves

| Wave | Version | Programs |
|------|---------|----------|
| 0 | v1.5.0 | Phase 0 harness + Program A (pipeline core) |
| 1 | v1.6.0 | A (finish) + Program B (library core) |
| 2 | v1.7.0 | Program D (formats) + B (finish) |
| 3 | v1.8.0 | Program C (playback) + Program E (automation core) |
| 4 | v1.9.0 | C + E finish + D finish |
| 5 | v2.0.0 | Program F (platforms) + Program G (reliability) |

Each wave ends with a real Linux validation run of every scenario added in the
wave and a release candidate.

---

## Phase 0 — Validation foundation

### P0.1 — Headless desktop entry point
- **Implementation:** add argument routing in the desktop host (e.g.
  `OpenYT --cli inspect --url … --json`) that calls the same `core` use cases.
  No UI dependency for inspect, enqueue, playlist planning and library queries.
- **Tests:** unit tests for argument parsing; golden JSON envelope.
- **Real Linux validation:** run the installed binary with `--cli` in an
  isolated prefix; assert exit code and JSON on stdout.
- **Exit:** inspect and enqueue work headlessly from the packaged image.

### P0.2 — Linux scenario script
- **Implementation:** `scripts/validate-linux.sh` with scenario registry,
  isolated `XDG_*` dirs, artifact assertions, optional `xvfb-run`, screenshot
  capture, and pass/fail summary.
- **Tests:** run the script in CI on `ubuntu-latest` with one trivial scenario.
- **Real Linux validation:** the script itself is the validation.
- **Exit:** a single command reproduces an end-to-end check on Linux.

### P0.3 — UI golden tests
- **Implementation:** Compose desktop UI tests that render screens with seeded
  state into images and compare with committed goldens.
- **Tests:** Downloads, Home (single and playlist), Library, Settings.
- **Real Linux validation:** goldens captured from the real desktop target.
- **Exit:** any visual regression fails CI.

### P0.4 — DB fixtures and migrations
- **Implementation:** helpers to build a SQLite file at a chosen schema version
  and assert migrations preserve data.
- **Tests:** every migration step from v1 to current.
- **Real Linux validation:** seed the real app DB, launch, assert migrated state.
- **Exit:** no migration can land without a test.

### P0.5 — Opt-in network CI job
- **Implementation:** a scheduled or label-triggered workflow that runs the
  network integration tests with bundled tools.
- **Tests:** existing integration suites.
- **Real Linux validation:** nightly run on `ubuntu-latest`.
- **Exit:** real-network regressions are visible before release.

---

## Program A — Download pipeline

### A1 — Queue priority and scheduling
- **Implementation:** priority levels (high/normal/low) in `DownloadTask`; the
  coordinator picks by priority then FIFO; drag-to-reorder in Downloads.
  `core` owns selection; `data` persists priority.
- **Tests:** coordinator ordering with fake engines; persistence round-trip.
- **Real Linux validation:** enqueue three tasks (seeded via CLI) and assert the
  completion order and file timestamps in an isolated prefix.
- **Exit:** priority is respected deterministically and after restart.

### A2 — Pause and resume
- **Implementation:** new `PAUSED` state; engines stop the process without
  deleting partial files; resume continues from the partial file. Desktop uses
  yt-dlp `--continue`; Android uses its partial-file semantics.
- **Tests:** coordinator pause/resume transitions; engine resume integration.
- **Real Linux validation:** start a large download, pause, kill the app,
  relaunch, resume, assert the final file is complete and no re-download.
- **Exit:** pause/resume survives app restart.

### A3 — Retry with backoff and crash recovery
- **Implementation:** automatic retry with exponential backoff for transient
  failures; tasks left `DOWNLOADING` at startup become `INTERRUPTED` and offer
  resume.
- **Tests:** backoff schedule; interruption recovery.
- **Real Linux validation:** seed an interrupted task, launch, assert it is
  resumed or offered for retry.
- **Exit:** no task is silently lost after a crash.

### A4 — Bandwidth and time limits
- **Implementation:** optional global speed limit and allowed download window
  (start/end time); coordinator defers tasks outside the window.
- **Tests:** scheduler defers and releases tasks around the window.
- **Real Linux validation:** set a window in the past/future and assert tasks
  stay queued/resume accordingly.
- **Exit:** limits are enforced without leaking engine processes.

### A5 — Per-item playlist format selection
- **Implementation:** playlist inspection optionally fetches formats per entry
  (lazy, on demand); the playlist UI allows per-entry override, defaulting to the
  global Video/Audio choice.
- **Tests:** parser for entry formats; planner uses per-entry format IDs.
- **Real Linux validation:** inspect a small playlist, override one entry's
  format, download, and assert container/resolution of that file.
- **Exit:** per-item selection works and falls back safely.

### A6 — Organisation presets and templates
- **Implementation:** editable output template and presets (playlist/channel/
  year/uploader); sanitised path segments; preview of the resulting layout.
- **Tests:** template expansion and sanitisation; path traversal rejection.
- **Real Linux validation:** download two entries under two presets and assert
  the folder/file layout on disk.
- **Exit:** templates are safe and reproducible.

### A7 — Browser cookies for authorised content
- **Implementation:** optional local import of a cookies file (no cloud). Stored
  encrypted at rest; passed to the engine by file path; never logged. Clear
  in-app warning about authorisation and terms.
- **Tests:** cookie file handling, encryption round-trip, log redaction.
- **Real Linux validation:** use a fixture cookies file with an authorised
  source; assert the engine receives it and diagnostics redact it.
- **Exit:** cookies are opt-in, encrypted, documented and redacted.

### A8 — Integrity checks and temp cleanup
- **Implementation:** verify final size and, when available, checksum; expose
  failures as structured errors; keep and re-run stale-temp cleanup.
- **Tests:** integrity mismatch handling; temp cleanup.
- **Real Linux validation:** corrupt a completed file, run verification, assert
  the failure is reported; assert temp dirs are removed after failures.
- **Exit:** corrupt downloads are detected, never presented as complete.

---

## Program B — Library and media

### B1 — Tags
- **Implementation:** tag model and many-to-many table; add/remove/filter tags in
  Library; tags included in search.
- **Tests:** repository round-trip; query by tag.
- **Real Linux validation:** tag entries in a seeded DB, relaunch, assert tags
  survive and filter correctly.
- **Exit:** tags persist and filter.

### B2 — Smart collections (saved filters)
- **Implementation:** named filter definitions (source, kind, tag, date range,
  size) evaluated at read time; editable from Library.
- **Tests:** filter evaluation; persistence.
- **Real Linux validation:** create a smart collection, add matching entries,
  relaunch, assert membership.
- **Exit:** smart collections reflect current data without manual updates.

### B3 — Metadata search
- **Implementation:** search across title, author, tags, source and file name;
  indexed where cheap.
- **Tests:** search ranking and empty results.
- **Real Linux validation:** seeded DB with mixed entries; assert query results.
- **Exit:** search is fast and predictable on a large seeded library.

### B4 — Metadata editing and ID3/cover embedding
- **Implementation:** edit title/author/tags; embed cover art and basic tags via
  FFmpeg for audio containers. Editing metadata never renames files silently.
- **Tests:** tag write/verify on a fixture audio file.
- **Real Linux validation:** edit a real downloaded audio file, re-probe, assert
  the embedded metadata and cover.
- **Exit:** metadata edits are verifiable on disk.

### B5 — Library export/import
- **Implementation:** JSON export/import of library records and collections
  (paths optional), with schema version and conflict handling.
- **Tests:** round-trip; duplicate and missing-file handling.
- **Real Linux validation:** export, wipe the DB, import, assert equality.
- **Exit:** a library can be moved between machines without losing records.

### B6 — Watch folder import
- **Implementation:** scan a chosen folder for existing media and import records
  without moving or deleting files.
- **Tests:** scanner with fixtures; idempotent imports.
- **Real Linux validation:** point the app at a fixture folder, import, assert
  records and no file mutation.
- **Exit:** existing media can be brought into the Library safely.

### B7 — Storage panel
- **Implementation:** totals, largest files, duplicates, missing files and
  orphans; read-only by default, with explicit destructive actions.
- **Tests:** aggregation; duplicate detection.
- **Real Linux validation:** seeded library with duplicates and missing files;
  assert the panel counts.
- **Exit:** storage insights are accurate and never delete files implicitly.

### B8 — Bulk rename/move
- **Implementation:** explicit, confirmed bulk rename or move with preview;
  updates stored paths; never deletes.
- **Tests:** plan generation; failure rollback behavior.
- **Real Linux validation:** rename a batch in a fixture folder, assert disk and
  DB stay consistent.
- **Exit:** bulk operations are previewed, confirmed and consistent.

---

## Program C — Playback and preview

### C1 — Android embedded player
- **Implementation:** Media3/ExoPlayer screen for completed items, background
  audio with a media session.
- **Tests:** player state unit tests where possible; instrumentation smoke test.
- **Real Linux validation:** N/A for Android; validated with emulator/device and
  recorded evidence in the phase report.
- **Exit:** playback works from Library on Android.

### C2 — Desktop player (viability spike first)
- **Implementation:** spike options: VLCJ (needs libvlc), or a bundled FFmpeg
  `ffplay` path, or Media3 on JVM if viable. Choose and record an ADR.
- **Tests:** chosen backend smoke test.
- **Real Linux validation:** play a real downloaded file in the packaged app and
  capture a screenshot.
- **Exit:** desktop playback works with a documented dependency choice.

### C3 — Play queue and background audio
- **Implementation:** queue from Library/Downloads, next/previous, background
  audio on Android.
- **Tests:** queue transitions.
- **Real Linux validation:** enqueue three files, assert order and completion.
- **Exit:** queue is stable across navigation and restart.

### C4 — PIP and floating mini-player
- **Implementation:** Android PIP and desktop always-on-top mini window.
- **Tests:** window/state unit tests; manual checklist.
- **Real Linux validation:** capture screenshots of the mini window.
- **Exit:** mini-player is usable and dismissible.

### C5 — Shortcuts and resume position
- **Implementation:** keyboard map, jump ±10s, resume from last position per
  item.
- **Tests:** position persistence.
- **Real Linux validation:** play, stop, relaunch, assert resume position.
- **Exit:** playback resumes predictably.

### C6 — Preview and trim
- **Implementation:** fast preview of a URL segment via FFmpeg, and lossless
  trim of completed files with confirmation.
- **Tests:** trim command planning; duration assertions.
- **Real Linux validation:** trim a fixture file and assert duration/format.
- **Exit:** trim is explicit and reversible via the original file.

---

## Program D — Formats and post-processing

### D1 — Subtitles
- **Implementation:** discover subtitle languages, download sidecar files, and
  optionally embed or extract. `MediaKind.SUBTITLE` becomes usable.
- **Tests:** parser for subtitle formats; planner choices.
- **Real Linux validation:** download a video with subtitles, assert sidecar and
  embedded variants.
- **Exit:** subtitles are selectable and verifiable.

### D2 — Audio conversion presets and normalisation
- **Implementation:** presets (e.g. M4A/MP3/Opus) and loudness normalisation via
  FFmpeg, applied as a post-processing step.
- **Tests:** command planning; output probe.
- **Real Linux validation:** convert a fixture and probe the result.
- **Exit:** conversions are deterministic and documented.

### D3 — Chapters and SponsorBlock
- **Implementation:** keep chapters; optional SponsorBlock marking/skipping as a
  clearly labelled post-processing choice.
- **Tests:** marker parsing.
- **Real Linux validation:** process a suitable fixture and assert chapters.
- **Exit:** behaviour is transparent and opt-in.

### D4 — Images from authorised sources
- **Implementation:** add `MediaKind.IMAGE` only if yt-dlp exposes it reliably;
  otherwise document the limitation and skip.
- **Tests:** parser and planner for image entries.
- **Real Linux validation:** attempt an authorised source and assert the file.
- **Exit:** either supported and tested, or explicitly documented as not
  supported.

### D5 — Podcasts/RSS
- **Implementation:** local feed subscriptions using a manual URL list; new
  episodes become queued downloads. No accounts.
- **Tests:** feed parsing with fixtures.
- **Real Linux validation:** feed a local fixture feed and assert episodes queue.
- **Exit:** feeds are local-only and reliable.

### D6 — Live capture
- **Implementation:** record live streams with a stop condition (duration or
  manual), using the bundled FFmpeg.
- **Tests:** stop conditions.
- **Real Linux validation:** record a short authorised live fixture and assert
  the file.
- **Exit:** live capture can be stopped cleanly.

---

## Program E — Local automation

### E1 — Playlist watchers
- **Implementation:** per-URL rules that detect new entries and enqueue them,
  storing only what is needed locally.
- **Tests:** diff logic with fixtures.
- **Real Linux validation:** seed a watcher, mutate a fixture feed, assert the
  new item queues.
- **Exit:** watchers are deterministic and reviewable.

### E2 — Scheduling
- **Implementation:** periodic checks with a local scheduler; app must be running
  (documented limitation, no background service promises).
- **Tests:** schedule calculation.
- **Real Linux validation:** set a near-future schedule and observe the run.
- **Exit:** schedules fire without duplicates.

### E3 — Notifications
- **Implementation:** Android notifications; desktop tray/toast where available.
- **Tests:** notification content mapping.
- **Real Linux validation:** capture a desktop notification screenshot.
- **Exit:** notifications are informative and dismissible.

### E4 — `openyt://` deep links
- **Implementation:** URL scheme on both platforms that opens the app pre-filled.
- **Tests:** link parsing.
- **Real Linux validation:** open a link with `xdg-open` and assert the field
  content/screenshot.
- **Exit:** deep links work on Linux.

### E5 — Browser extension handoff
- **Implementation:** a minimal extension that only sends the current URL to the
  app via deep link; no scraping or bypass.
- **Tests:** message contract.
- **Real Linux validation:** trigger the deep link and assert the app receives it.
- **Exit:** handoff works and is documented.

### E6 — Desktop CLI and local IPC
- **Implementation:** extend the Phase 0 CLI with `download`, `library`,
  `watch`; optional local socket for scripts with explicit opt-in.
- **Tests:** command matrix.
- **Real Linux validation:** run the packaged binary CLI end-to-end.
- **Exit:** scripting is stable and documented.

### E7 — Clipboard monitor
- **Implementation:** optional detection of supported URLs in the clipboard,
  off by default.
- **Tests:** URL detection.
- **Real Linux validation:** set the clipboard and assert the prompt appears.
- **Exit:** opt-in and privacy-respecting.

---

## Program F — Platforms and distribution

### F1 — Windows validation and signing
- **Implementation:** install on a real Windows machine, fix packaging issues,
  sign the MSI, document the flow.
- **Tests:** installer smoke on a VM.
- **Real Linux validation:** N/A (cross-platform); evidence recorded.
- **Exit:** MSI installs and runs on a clean Windows.

### F2 — macOS validation and notarisation
- **Implementation:** sign and notarise the DMG; handle Gatekeeper and bundled
  tool quarantine.
- **Tests:** notarisation in CI; manual launch on macOS.
- **Real Linux validation:** N/A; evidence recorded.
- **Exit:** DMG opens without workarounds.

### F3 — Additional Linux formats
- **Implementation:** RPM, AppImage, Flatpak and AUR recipes.
- **Tests:** build each artifact in CI.
- **Real Linux validation:** install each on a clean container/VM.
- **Exit:** users have multiple supported install paths.

### F4 — `.deb` upgrade polish
- **Implementation:** verify in-place upgrade from previous versions, including
  DB migration and desktop entry dedup.
- **Tests:** upgrade scenario.
- **Real Linux validation:** install old, upgrade, assert state and launcher.
- **Exit:** upgrades are clean and duplicate-free.

### F5 — Android TV, tablet and foldable
- **Implementation:** adaptive layouts, D-pad focus, large-screen Library.
- **Tests:** Compose UI tests for breakpoints; device screenshots.
- **Real Linux validation:** N/A; emulator evidence recorded.
- **Exit:** large-screen layouts are usable.

---

## Program G — Reliability, security and quality

### G1 — Second engine behind `DownloadEngine`
- **Implementation:** spike an alternative external engine (never a
  reimplementation) behind the same port; selectable in Settings.
- **Tests:** contract tests shared by both engines.
- **Real Linux validation:** run the same scenario set against both engines.
- **Exit:** either a second engine is viable, or the spike is documented as
  rejected.

### G2 — Bundled tool update policy
- **Implementation:** pin versions, document the cadence, and offer an opt-in
  runtime update of yt-dlp into the writable cache with checksum verification.
- **Tests:** version selection and checksum rejection.
- **Real Linux validation:** force an update and assert the engine version
  changes and binaries stay executable.
- **Exit:** tool freshness is predictable and secure.

### G3 — SBOM, signed releases and in-app verification
- **Implementation:** generate an SBOM per release, sign artifacts, and verify
  installer checksums inside the app.
- **Tests:** signature and checksum verification paths.
- **Real Linux validation:** tamper with a downloaded installer and assert the
  app rejects it.
- **Exit:** tampered artifacts are rejected with a clear message.

### G4 — Expanded test coverage in CI
- **Implementation:** DB migration tests, UI goldens, parser fuzzing, and Android
  instrumented tests on an emulator job.
- **Tests:** the new suites.
- **Real Linux validation:** CI runs the Linux scenario script and network job.
- **Exit:** regressions are caught before merge.

### G5 — Privacy hardening
- **Implementation:** confirm no telemetry, audit logs and diagnostics redaction,
  document data locations and deletion.
- **Tests:** redaction tests; diagnostics snapshot review.
- **Real Linux validation:** inspect real logs and diagnostics output.
- **Exit:** no sensitive data leaves the device in logs or diagnostics.

---

## Sequencing rules

- Phase 0 is a hard prerequisite for real validation of every later phase.
- Program A comes first because scheduling, pause/resume and integrity are
  foundations for automation and library integrity.
- Program B and D are largely independent and can be parallelised after A.
- Program C depends on B records and on the player spike decision.
- Program E depends on A (robust queue) and E1 depends on A5/A6.
- Program F and G are release-level and close each wave.

## Definition of done for any microphase

1. Implementation merged with unit tests.
2. Integration test where a real engine behaviour is involved.
3. Real Linux validation scenario executed against the packaged image, with
   captured artifacts.
4. Docs updated (STATUS, ADR when durable, README when user-facing).
5. No regression in the full test suite and in the Linux scenario script.
