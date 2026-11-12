# Personal media library: execution plan

Status: **planning only; every implementation task is pending**. Prepared on
2026-11-12 against the 4.0.1 codebase. This package is the execution specification
for a new program, not a claim that its features already exist.

The goal is to help people find, study, preserve and reuse their saved media,
without an account or a remote service. Start with daily download/offline use,
then add search and study. Preserve the working download, playback, PiP,
mini-player, import and installer behavior throughout.

## How to use this package

1. Give the implementation agent [the executor prompt](EXECUTOR_PROMPT.md) and
   **one task ID**, starting with `NX0.1`. Do not ask it to execute this whole
   document in one unbroken change.
2. The agent reads this overview, the selected phase, [contracts](CONTRACTS.md),
   [progress](PROGRESS.md), repository instructions and the relevant ADRs.
3. It implements and verifies that task, records evidence and leaves a compact
   handoff. A phase gate requires the combined feature and native checks too.
4. Advance only after prerequisites pass. A blocked native check is recorded as
   blocked; it is never converted into a pass by running another platform.

Task IDs `NX0`–`NX9` belong to this program. Historical `A1`/`C4` and numbered
phases in the [previous roadmap](../ROADMAP_EXPANSION.md) are not new backlog.
The docs and proposed names below are specifications, not existing APIs or tests.
Resolve an anchor that moved by searching the named symbol; report a genuinely
different architecture before changing the design.

## Delivery order

- **Foundation:** [NX0 — baseline and fixtures](00_FOUNDATION.md).
- **Milestone 1 — easier daily use:** [NX1 — download profiles](01_DOWNLOAD_PROFILES.md),
  then [NX2 — durable offline library](02_OFFLINE_LIBRARY.md).
- **Milestone 2 — find and study:** [NX3 — timed subtitle search](03_TIMED_SEARCH.md),
  then [NX4 — study mode](04_STUDY_MODE.md).
- **Milestone 3 — preserve the library:** [NX5 — portable backup](05_PORTABLE_BACKUP.md),
  then [NX6 — assisted recovery](06_LIBRARY_RECOVERY.md).
- **Milestone 4 — advanced workflows:** [NX7 — subscription rules](07_SUBSCRIPTION_RULES.md),
  then [NX8 — batch clips](08_BATCH_CLIPS.md).
- **Optional track:** [NX9 — local transcription](09_LOCAL_TRANSCRIPTION.md), only
  after search/study work and its explicit feasibility gate passes.

Each milestone may be delivered independently after its gate. Closed-app
scheduling and transcription are gated options, not prerequisites for the other
eight expansions. No delivery dates are promised before the baseline/capability
tasks measure the target environments.

## Existing foundations to extend

- `DownloadOptions`, media inspection and `DownloadEngine` already support audio
  presets, subtitles, a segment, chapters, normalization and bounded live capture.
- The library already has stable entry IDs, metadata search, tags, collections,
  import/export of **JSON records**, file actions and storage management.
- Playback already persists queues/positions and provides Android PiP and a
  desktop mini-player. Current persisted positions use file paths.
- Artwork currently uses remote URLs and Coil cache. That cache is not a durable
  offline library or a backup.
- Subscriptions already track playlists/RSS while the application is running,
  with baseline-first discovery and persisted download deduplication.
- FFmpeg already performs original-preserving trimming and audio conversion.
  This does not establish availability of portrait rendering or subtitle burning.
- Portuguese/English strings, OS-language detection, provider icons, native
  installers and release verification already exist. Extend them consistently.

Do not rebuild these foundations, reintroduce a rejected extraction engine, add a
backend, or replace the shared Kotlin/Compose architecture.

## Decisions fixed for this program

1. **Identity:** new assets, cues, notes, provenance and study state reference
   `LibraryEntry.id`. A path is a mutable locator. Add a backward-compatible
   entry-ID bridge to playback in NX3 before recovery; preserve legacy positions
   and queues. File revisions detect changed content; names alone prove nothing.
2. **Layering:** pure models/ports/use cases in `core`; SQLDelight and repository
   implementations in `data`; platform filesystem/process/SAF/player adapters in
   hosts or existing shared JVM adapter locations; Compose consumes ports/state.
   Create focused repositories instead of adding every feature to
   `DownloadRepository`. No UI filesystem access or raw FFmpeg/yt-dlp arguments.
3. **Jobs:** add a small persisted local operation journal when NX2 first needs
   it. One disk-heavy operation at a time initially. Restart marks running work
   `INTERRUPTED`; do not silently restart it. Keep `DownloadCoordinator` ownership
   intact. Introduce no generic workflow engine or idle framework in NX0.
4. **Durability:** success follows verified files plus committed metadata. Publish
   new owned files through staging and a recoverable journal where filesystem and
   database updates meet. Cleanup removes only operation-owned temporary files.
5. **Safety:** never overwrite/delete original media automatically. Record removal,
   owned cache cleanup and user-file deletion are different actions. Show a
   preview before import conflicts, recovery changes or authored-note removal.
6. **Privacy:** no credentials, cookie jars, private paths or models in default
   portable exports. Local media and notes are sensitive. No cloud fallback.
7. **Errors:** use a structured `LibraryOperationFailure` family for new library
   work (distinct from download errors), with localized next steps: retry, choose
   another folder, free space, restore permission or unsupported capability.
8. **Compatibility:** definitions may be portable, destination bindings are local.
   Android keeps app-local media authoritative and SAF as an exported copy, per
   ADR-0016. A `content://` URI is not a filesystem path.
9. **Scope:** no account, cloud synchronization, remote downloading, DRM bypass,
   arbitrary scripting, automatic deletion/retention or new gallery engine.
   No version bump, tag, main-branch merge or release follows from this plan.

These are design instructions to implement, not evidence of current behavior.
Update the relevant ADR when the corresponding task actually makes a durable
decision. Never mark a speculative design as an implemented architecture.

## Source map

Paths below are relative to the repository root. Locate symbols with `rg` before
editing; filenames may evolve between phases.

- `core/src/commonMain/kotlin/com/openyt/core/domain/`: `MediaModels.kt`,
  `DownloadOptions.kt`, `LibraryEntry.kt`, `DownloadTask.kt`.
- `core/src/commonMain/kotlin/com/openyt/core/application/`: `PlaybackLauncher.kt`,
  `PlaybackStore.kt`, `MediaProcessor.kt`, `LibraryArchive.kt`, `OpenYtLink.kt`.
- `core/src/commonMain/kotlin/com/openyt/core/automation/Subscriptions.kt`.
- `data/src/commonMain/sqldelight/com/openyt/data/db/Download.sq` and `.sqm`
  migrations; `SqlDelightDownloadRepository`, `SqlDelightPlaybackStore`,
  `SqlDelightSubscriptions`. Current baseline is schema 18. Allocate the next
  migration from the actual checkout, not a number reserved by this document.
- `data/src/jvmSharedMain/`: existing file scanner, confirmed engine output and
  file-operation implementations. Keep platform I/O out of `core`.
- `ui/src/commonMain/kotlin/com/openyt/ui/`: screens, components including
  `MediaThumbnail`, and `i18n/Strings.kt` / `FeatureStrings.kt`. Add grouped feature
  strings; do not grow a giant constructor beyond JVM limits.
- `desktopApp/src/main/kotlin/com/openyt/desktop/`: `Main.kt`, `cli/DesktopCli.kt`,
  `player/DesktopPlaybackLauncher.kt`, `downloader/DesktopYtDlpEngine.kt`.
- `androidApp/src/main/kotlin/com/openyt/android/`: `MainActivity.kt`, player launcher,
  `PlaybackService.kt`, `PlayerActivity.kt`, engine and `saf/AndroidSafDestination.kt`.
- `media-ffmpeg/src/commonMain/`: `FfmpegPlan.kt`, external-tool plan generation.
- Test roots: `core/src/commonTest`, `data/src/desktopTest`,
  `desktopApp/src/test`, `androidApp/src/androidTest`; actual Gradle targets are
  listed in [contracts](CONTRACTS.md).

## Reading order and architecture records

Always read [architecture](../../architecture/OVERVIEW.md) and repository
`AGENTS.md`. Then read only the ADRs relevant to the selected phase:

- Persistence and metadata: [0005](../../adr/0005-sqldelight-persistence.md),
  [0010](../../adr/0010-collections.md).
- Artwork: [0009](../../adr/0009-thumbnails-coil.md).
- Android files: [0014](../../adr/0014-saf-import.md),
  [0016](../../adr/0016-saf-destination.md).
- Playback/output: [0019](../../adr/0019-desktop-playback.md),
  [0020](../../adr/0020-download-completion.md),
  [0024](../../adr/0024-windows-media-and-shortcuts.md).
- Processing/automation: [0021](../../adr/0021-local-media-expansion.md),
  [0023](../../adr/0023-engine-and-image-viability.md).
- Release evidence: [0022](../../adr/0022-verified-release-manifests.md).

## Definition of a completed phase

All its mandatory tasks and named contracts pass; migration/backward
compatibility and failure paths are tested; shared UI works in Portuguese and
English with keyboard/touch and enlarged text; native checks cover the hosts
affected by the change; executed evidence identifies the commit, tool versions,
commands, fixtures and results. Usage docs describe actual behavior and limits.

Tests reduce known regression risk. This plan does not promise to cover every
possible error, certify every source, or prove all platforms through Linux alone.
Use [PROGRESS.md](PROGRESS.md) to distinguish pending, blocked, implemented and
verified work. Record unavailable hardware explicitly.

## Primary references for gated capabilities

Check current official documentation again when implementing these tasks:

- [SQLite FTS3/FTS4](https://www.sqlite.org/fts3.html): probe the SQLite shipped on
  each host before committing to FTS4/unicode61; do not assume FTS5 everywhere.
- [Android persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
  and [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running):
  periodic work is inexact; Android 16 job quotas also affect long-running workers.
- [FFmpeg filters](https://ffmpeg.org/ffmpeg-filters.html): published filters are
  not evidence that a particular bundled binary includes them.
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp): candidate external engine,
  not an approved new dependency. Binding/model licenses, memory, quality and
  speed must pass NX9 before exposing transcription to users.
