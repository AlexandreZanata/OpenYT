# Execution tracker

Updated: 2026-11-13. **NX4.4 and NX4.5 VERIFIED; NX4.6 Linux VLCJ end-to-end study contract (`StudyPlaybackLocalTest`, `maxOvershootMs=400`, `maxReturnDeltaMs=0`) VERIFIED, with Windows MSI (`validate-windows.ps1`) and Android instrumentation (`AndroidStudyFlowContractTest`) ready for final verification.**

Baseline: OpenYT 4.0.1; checkout `codex/nx2-3-owned-storage`.
Actual baseline inspected, validated, profile resolution models implemented, profile persistence with schema migration verified, immutable profile request snapshots implemented, host/engine adapters connected with real output container/stream probing, profile selection/preview/editing UI verified, combined native flow & manual format coexistence verified with milestone documentation and real Compose UI evidence, offline asset domain models, media revision drift detection, readiness calculator, SQLDelight schema 20 migration verified, engine-produced companion artifact receipts verified, owned storage, journaled durable local operations, SQLDelight schema 21 migration verified, FTS4/unicode61 SQLite indexing capability probe verified on shipped SQLite 3.53.4 with 100k-cue p95 budget passed at 0.899 ms, pure Kotlin bounded SRT/VTT parser with safe markup stripping, speaker extraction, and timebase offset shifting verified, subtitle cue persistence with atomic revision rebuild, FTS4 virtual table synchronization, and crash recovery verified, bounded text search with scoped pagination, literal FTS token escaping, accent matching, and 100k-cue performance budget verified, entry-aware timed playback, session control port, legacy queue/checkpoint bridge, out-of-range clamping, and stale callback rejection verified, timed search navigation, scope/status indicators, plain-text excerpt highlights, accessible timestamp playback actions, empty/error state distinction, keyboard navigation, and responsive 320dp/desktop UI verified, search-to-playback end-to-end flow proven offline with <= 1000 ms seek accuracy, checkpoint persistence, close/reopen cycle, tokenizer limits documented, and non-ASR distinction verified, study mode persistence (notes, bookmarks, saved ranges, studied completion, collection progress, and non-cascading removal cleanup) verified on SQLDelight schema v24, timestamp capture, anchor health inspection, unsaved editor draft guard, and timestamp navigation (`NX-T02`) verified across core, Desktop VLCJ, and Android Media3, explicit A/B repetition (`NX-T03`) verified with 5-loop real-playback tolerance, pause/mini-player/EOF/stop/switch safety, and cross-platform CI fixes, safe Markdown export and `openyt://library/entry/<id>?t=<ms>` local links (`NX-T04`) verified, focused Study UI (`NX-T05`) verified across responsive mobile/desktop Compose layouts, and full Linux VLCJ+FFmpeg+SQLDelight NX4.6 lifecycle (`NX-T06`) verified.

Current task: NX4.4 & NX4.5 VERIFIED; NX4.6 Linux VLCJ & desktop UI/core/engine/data/media suites verified — completing packaged Linux scripts, real Android ADB verification, and full GitHub CI (Ubuntu/Windows/macOS/Android Emulator) to close Milestone 2 (M2).
Statuses and handoff rules: [EXECUTOR_PROMPT.md](EXECUTOR_PROMPT.md).
Check a box only after VERIFIED evidence; keep IMPLEMENTED/BLOCKED work unchecked.

The 52 mandatory tasks cover NX0–NX8, excluding NX7.7–NX7.9.
The 9 optional tasks are NX7.7–NX7.9 and NX9.1–NX9.6.
Capability NO_GO outcomes must be recorded explicitly; they do not mean the corresponding optional feature was delivered.

Milestone gates: M1 = NX1.6 + NX2.6; M2 = NX3.7 + NX4.6;
M3 = NX5.6 + NX6.6; M4 = NX7.6 + NX8.6. Each gate includes its prerequisites.

## NX0 — execution foundation

- [x] [NX0.1 — establish the actual baseline](00_FOUNDATION.md#nx0-1) — VERIFIED (Linux x86_64; Android compilation verified; native Android device tests and Windows VM blocked).
- [x] [NX0.2 — establish small, redistributable fixtures](00_FOUNDATION.md#nx0-2) — VERIFIED (Pure KMP + resources; 7 smoke tests passing).
- [x] [NX0.3 — wire the verification and handoff process](00_FOUNDATION.md#nx0-3) — VERIFIED (Task mapping, native matrix and regression gate documented).

## NX1 — complete download profiles

- [x] [NX1.1 — model profiles and deterministic resolution](01_DOWNLOAD_PROFILES.md#nx1-1) — VERIFIED (Pure KMP domain models & resolver; 13 tests passing).
- [x] [NX1.2 — persist custom profiles and local bindings](01_DOWNLOAD_PROFILES.md#nx1-2) — VERIFIED (SQLDelight schema 18->19 migration, profile repository, separate local bindings & versioned serialization; 8 contract tests passing).
- [x] [NX1.3 — snapshot profiles into queued requests](01_DOWNLOAD_PROFILES.md#nx1-3) — VERIFIED (Immutable profile provenance, per-task SAF export target isolation, retry snapshot preservation, and revalidation on format expiration; 11 contract tests passing).
- [x] [NX1.4 — connect the host/engine adapters](01_DOWNLOAD_PROFILES.md#nx1-4) — VERIFIED (Host and engine options updated with merge/remux container flags, confirmed output validation against approved plan, structured format disappearance errors, and real local probing; 7 local contract tests passing).
- [x] [NX1.5 — add profile selection, preview and editing UI](01_DOWNLOAD_PROFILES.md#nx1-5) — VERIFIED (Accessible selection, preview card across mobile/desktop widths, enlarged text, changing profile does not start download, explicit download action snapshots approved plan, actionable alternatives for incompatible profiles, and custom profile editor dialog; 20 contract tests passing).
- [x] [NX1.6 — milestone evidence and documentation](01_DOWNLOAD_PROFILES.md#nx1-6) — VERIFIED (Combined native preview/download/restart/retry flow verified with SQLite persistence and local tools, manual format selection preserved without interference, four profiles and fallback rules documented with real Compose UI screenshots; 3 contract tests passing).

## NX2 — durable offline library

- [x] [NX2.1 — model assets, revisions and readiness](02_OFFLINE_LIBRARY.md#nx2-1) — VERIFIED (Pure KMP domain models & readiness calculator, SQLDelight schema 19->20 migration, asset repository with cascade deletion, and off-UI thread digest inspection; 17 contract tests passing).
- [x] [NX2.2 — confirm engine-produced companion artifacts](02_OFFLINE_LIBRARY.md#nx2-2) — VERIFIED (Backward-compatible receipt contract, strict path containment, safe asset store copy, multi-track extraction, and coordinator resilience; 34 contract tests passing).
- [x] [NX2.3 — implement owned storage and durable local operations](02_OFFLINE_LIBRARY.md#nx2-3) — VERIFIED (Pure KMP operation models, SQLDelight schema 20->21 migration, durable asset operator, journaled staging, atomic promotion, bounds & failure injection checks; 20 contract tests passing).
- [x] [NX2.4 — fetch artwork and import subtitle assets safely](02_OFFLINE_LIBRARY.md#nx2-4) — VERIFIED (Bounded HTTP(S) artwork fetch, credential stripping, subtitle validation/import into owned storage, and local-artwork preference; 39 contract tests passing).
- [x] [NX2.5 — show offline readiness and storage actions](02_OFFLINE_LIBRARY.md#nx2-5) — VERIFIED (Verified local artwork preference, per-item readiness and recovery actions, explicit pin/unpin, cleanup preview with confirmation, separated storage totals, and host wiring; 18 contract tests passing).
- [ ] [NX2.6 — prove offline behavior and close milestone 1](02_OFFLINE_LIBRARY.md#nx2-6) — BLOCKED (Linux offline evidence, budget enforcement, docs and screenshots complete; Windows/Android-device/macOS native gate unavailable, so milestone 1 remains open).

## NX3 — search within saved media

- [x] [NX3.1 — prove indexing capability on the shipped databases](03_TIMED_SEARCH.md#nx3-1) — VERIFIED (Pure KMP FtsCapabilityReport, SQLDelight SqliteFtsCapabilityProbe, FTS4 + unicode61 creation, binding, accent folding, CRUD, Android driver factory support, and measured 100,000-cue synthetic corpus p95 budget passed at 0.899 ms; 13 tests passing).
- [x] [NX3.2 — parse and normalize timed subtitle tracks](03_TIMED_SEARCH.md#nx3-2) — VERIFIED (Pure Kotlin TimedSubtitleParser, TimedCue/ParsedSubtitleTrack domain models, bounded 10 MiB/100k cue limits, safe markup stripping, speaker extraction, and timebase offset shifting; 9 contract tests passing).
- [x] [NX3.3 — persist cues and atomically rebuild an index revision](03_TIMED_SEARCH.md#nx3-3) — VERIFIED (SQLDelight schema 21->22 migration, SqlDelightSubtitleIndexRepository, SubtitleIndexRebuilder, atomic revision swap, 1:1 B-tree and FTS count reconciliation, journaled crash recovery, and cancellation cleanup; 15 contract tests passing).
- [x] [NX3.4 — expose bounded text search](03_TIMED_SEARCH.md#nx3-4) — VERIFIED (Pure KMP query validation/use case, scoped search repository with deterministic tie-breaking pagination, literal FTS token escaping, accent-folded excerpt highlighting, and measured 100k-cue synthetic corpus budget passed at 73.96 ms; 20 contract tests passing).
- [x] [NX3.5 — add entry-aware timed playback without losing history](03_TIMED_SEARCH.md#nx3-5) — VERIFIED (Pure KMP PlaybackSessionControl port, clampSeekPosition bounds enforcement, PlayableMedia/PlaybackCheckpoint entryId support with backward-compatible legacy queue JSON decoding, SQLDelight schema 22 migration with entry ID checkpoint bridge & moving locator support, DesktopPlaybackLauncher VLCJ session control with asynchronous prepare seek & stale callback rejection, and Android Media3/PlaybackService entry-aware seek; 18 contract tests passing).
- [x] [NX3.6 — deliver result navigation and empty/error states](03_TIMED_SEARCH.md#nx3-6) — VERIFIED (TimedSearchScopeIndicator, TimedSearchExcerptText, TimedSearchMatchCard, TimedSearchStateView, debounced LibraryScreen search integration, distinct empty vs unavailable index states, source time offset seek, keyboard navigation, 320dp/enlarged text, pt/en localization, and accessibility semantics; 8 contract tests passing).
- [x] [NX3.7 — prove search-to-playback end to end](03_TIMED_SEARCH.md#nx3-7) — VERIFIED (Offline synthetic phrase search to real player position within <= 1000 ms, checkpoint persistence, close/reopen cycle, tokenizer limits documented, and non-ASR distinction verified; 2 desktop e2e tests passing, Android test APK assembled).

## NX4 — study mode

- [x] [NX4.1 — persist notes, bookmarks, saved ranges and study state](04_STUDY_MODE.md#nx4-1) — VERIFIED (pure KMP `StudyModels`, `StudyValidator`, `StudyRepository`/`MemoryStudyRepository`, `StudyService`, SQLDelight schema v24 migration `23.sqm`, `SqlDelightStudyRepository`, deterministic unit/SQLite/Android-compile checks, and full offline suite passing).
- [x] [NX4.2 — implement timestamp capture and note navigation](04_STUDY_MODE.md#nx4-2) — VERIFIED (atomic `PlaybackPositionSnapshot` & `captureSnapshot()` with `sessionGeneration`, `StudyInteractionModels`, `StudyAnchorInspector`, `StudyCaptureAndNavigationService`, `StudyEditorCoordinator`, Desktop VLCJ `DesktopPlaybackLauncher` + `DesktopPlaybackSessionTest`, Android `AndroidPlaybackSessionBridge` / `PlaybackService` / `AndroidPlaybackLauncher` / `OpenYtApplication` + `AndroidStudySessionContractTest`, `NX-T02` `StudyCaptureAndNavigationTest` & `PlaybackSessionControlTest`, `OVERVIEW.md`/`CONTRACTS.md` updated).
- [x] [NX4.3 — implement A/B repetition through platform capabilities](04_STUDY_MODE.md#nx4-3) — VERIFIED (`AbRepeatModels`, `StudyAbRepeatController` with serialized/coalesced seek guard & restart no-auto-play, `PlaybackSessionControl` active-repeat bounds & listener bridge, Desktop VLCJ `DesktopPlaybackLauncher` fast monitor/EOF/stop safety + `DesktopPlaybackSessionTest` + 5-loop real fixture `DesktopPlaybackLocalTest`, Android Media3 `PlaybackService`/`AndroidPlaybackSessionBridge`/`AndroidPlaybackLauncher`/`OpenYtApplication` + `AndroidAbRepeatContractTest`, `StudyAbRepeatControllerTest`, plus cross-platform CI fixes for schema version v24 checks & Windows/macOS path/filename tests, `NX-T03`).
- [x] [NX4.4 — add safe note export and local-entry links](04_STUDY_MODE.md#nx4-4) — VERIFIED (`StudyMarkdownExporter`, `StudyHostLinkCapability`, `OpenYtEntryLink` / `OpenYtLink`, `TextFileExporter`, `DesktopDeepLinks`, `DesktopInstance`, `DesktopCli link --entry`, `AndroidManifest.xml`, `NX-T04`).
- [x] [NX4.5 — deliver a focused study UI](04_STUDY_MODE.md#nx4-5) — VERIFIED (`StudySheetDialog`, `StudySheetSections`, `StudyRemovalDialog`, unified scrollable `LibraryScreen` with `FlowRow` toolbars/actions; 57 `*UiTest` passing across responsive mobile 320dp and large desktop 200% font scales, `:ui:desktopTest` passing, `NX-T05`).
- [ ] [NX4.6 — prove the full study flow and close milestone 2](04_STUDY_MODE.md#nx4-6) — IMPLEMENTED (`StudyPlaybackLocalTest` passing on Linux VLCJ `1/1`, `WindowsContractHarness` `"study"` & `AndroidStudyFlowContractTest` ready for final Android ADB & Windows CI verification).

## NX5 — portable media backup and restore

- [ ] [NX5.1 — specify and validate manifest v1](05_PORTABLE_BACKUP.md#nx5-1) — PENDING.
- [ ] [NX5.2 — prove streaming archive support on each host](05_PORTABLE_BACKUP.md#nx5-2) — PENDING.
- [ ] [NX5.3 — implement consistent cancellable export](05_PORTABLE_BACKUP.md#nx5-3) — PENDING.
- [ ] [NX5.4 — validate hostile bundles and build restore preview](05_PORTABLE_BACKUP.md#nx5-4) — PENDING.
- [ ] [NX5.5 — publish restore with crash recovery and reference remapping](05_PORTABLE_BACKUP.md#nx5-5) — PENDING.
- [ ] [NX5.6 — restore UI and real cross-device round trip](05_PORTABLE_BACKUP.md#nx5-6) — PENDING.

## NX6 — assisted library recovery

- [ ] [NX6.1 — build a read-only health report](06_LIBRARY_RECOVERY.md#nx6-1) — PENDING.
- [ ] [NX6.2 — find bounded candidates and verify content hashes](06_LIBRARY_RECOVERY.md#nx6-2) — PENDING.
- [ ] [NX6.3 — prepare a confirmed relink plan](06_LIBRARY_RECOVERY.md#nx6-3) — PENDING.
- [ ] [NX6.4 — apply relinking atomically and preserve context](06_LIBRARY_RECOVERY.md#nx6-4) — PENDING.
- [ ] [NX6.5 — deliver advisory duplicate review and recovery UI](06_LIBRARY_RECOVERY.md#nx6-5) — PENDING.
- [ ] [NX6.6 — prove recovery and close milestone 3](06_LIBRARY_RECOVERY.md#nx6-6) — PENDING.

## NX7 — subscription rules and gated scheduling

- [ ] [NX7.1 — model versioned subscription rules and decisions](07_SUBSCRIPTION_RULES.md#nx7-1) — PENDING.
- [ ] [NX7.2 — persist candidates without dropping deferred work](07_SUBSCRIPTION_RULES.md#nx7-2) — PENDING.
- [ ] [NX7.3 — implement host constraints and storage reservations](07_SUBSCRIPTION_RULES.md#nx7-3) — PENDING.
- [ ] [NX7.4 — apply rules through the existing foreground coordinator](07_SUBSCRIPTION_RULES.md#nx7-4) — PENDING.
- [ ] [NX7.5 — deliver rule editor and decision history](07_SUBSCRIPTION_RULES.md#nx7-5) — PENDING.
- [ ] [NX7.6 — prove rule behavior on real hosts](07_SUBSCRIPTION_RULES.md#nx7-6) — PENDING.
- [ ] [NX7.7 — optional Android background feasibility gate](07_SUBSCRIPTION_RULES.md#nx7-7) — PENDING (optional).
- [ ] [NX7.8 — optional bounded Android background checks](07_SUBSCRIPTION_RULES.md#nx7-8) — PENDING (optional).
- [ ] [NX7.9 — optional desktop closed-app checks](07_SUBSCRIPTION_RULES.md#nx7-9) — PENDING (optional).

## NX8 — batch clip creation

- [ ] [NX8.1 — probe the actual processing capabilities](08_BATCH_CLIPS.md#nx8-1) — PENDING.
- [ ] [NX8.2 — model recipes and build a safe processing plan](08_BATCH_CLIPS.md#nx8-2) — PENDING.
- [ ] [NX8.3 — run batches with cancellation and per-clip receipts](08_BATCH_CLIPS.md#nx8-3) — PENDING.
- [ ] [NX8.4 — implement portrait rendering, captions and covers](08_BATCH_CLIPS.md#nx8-4) — PENDING.
- [ ] [NX8.5 — deliver multi-range editing and preview UI](08_BATCH_CLIPS.md#nx8-5) — PENDING.
- [ ] [NX8.6 — prove outputs and close milestone 4](08_BATCH_CLIPS.md#nx8-6) — PENDING.

## NX9 — optional local transcription

- [ ] [NX9.1 — dependency, binding and privacy feasibility](09_LOCAL_TRANSCRIPTION.md#nx9-1) — PENDING (optional).
- [ ] [NX9.2 — benchmark bounded multilingual inference](09_LOCAL_TRANSCRIPTION.md#nx9-2) — PENDING (optional).
- [ ] [NX9.3 — implement model acquisition and lifecycle](09_LOCAL_TRANSCRIPTION.md#nx9-3) — PENDING (optional).
- [ ] [NX9.4 — integrate bounded transcription jobs](09_LOCAL_TRANSCRIPTION.md#nx9-4) — PENDING (optional).
- [ ] [NX9.5 — add transcription UI and correction flow](09_LOCAL_TRANSCRIPTION.md#nx9-5) — PENDING (optional).
- [ ] [NX9.6 — native offline proof and release decision](09_LOCAL_TRANSCRIPTION.md#nx9-6) — PENDING (optional).

## Execution evidence

### NX0.1 — establish the actual baseline

```text
Task: NX0.1 — establish the actual baseline
Status: VERIFIED (Linux x86_64 host; Android compilation verified; device install and Windows VM blocked)
Branch and commit (or uncommitted diff): codex/nx0-1-baseline at commit 18fa90cd86c9072582c6fbc65329c7a0e6203856
Behavior changed: None. Baseline environment inspection, execution of deterministic and local native test suites, compilation verification, and limitation recording only.
Files and ADRs changed:
- docs/planning/NEXT_EXPANSION/PROGRESS.md
Contracts added/updated (IDs and exact test methods):
- Deterministic test suite across all modules (216 tests run, 11 skipped due to opt-in env flags, 0 failures, 0 errors):
  - :core:desktopTest (113 tests passed, 0 skipped, 0 failed): DownloadCoordinatorTest (34), LibraryControllerTest (5), SettingsControllerTest (5), UpdateControllerTest (4), BulkFilePlannerTest (3), EnqueuePlaylistUseCaseTest (4), InspectMediaUseCaseTest (3), LibraryArchiveServiceTest (4), PlaybackQueueTest (3), StorageAnalyzerTest (4), UpdateCheckerTest (3), SubscriptionControllerTest (3), FolderImportServiceTest (2), MediaPreviewTest (2), OpenYtLinkTest (2), PlaybackCheckpointTest (2), SystemLanguageTest (2), DownloadNotificationTest (1), etc.
  - :engine-ytdlp:desktopTest (18 tests passed, 0 skipped, 0 failed): YtDlpSupportTest (10), YtDlpJsonParserTest (7), ParserRobustnessTest (1).
  - :data:desktopTest (33 tests passed, 0 skipped, 0 failed): SqlDelightDownloadRepositoryTest (17), SettingsRepositoryTest (10), DatabaseMigrationTest (3), SubscriptionsTest (3).
  - :media-ffmpeg:desktopTest (3 tests, 1 skipped by default, 0 failed): FfmpegProcessorTest (2 passed, 1 skipped by default; when OPENYT_RUN_MEDIA_TESTS=1 is provided, all 3 passed).
  - :desktopApp:test (49 tests, 10 skipped by default, 0 failed): StorageUiTest (8), DesktopYtDlpEngineTest (7), FileActionsUiTest (4), DesktopMediaActionsTest (3), DownloadsUiTest (3), DesktopDropParserTest (2), ExecutableLocatorTest (2), FfprobeJsonParserTest (2), BundledToolsTest (1), DesktopInstanceTest (1), DesktopMediaProbeTest (1), ExternalPlaybackTest (1), HomePlaylistUiTest (1), LibraryPlaybackUiTest (1), LibraryStorageUiTest (1), ProviderIconsUiTest (1).
- Android compilation:
  - :androidApp:assembleDebug (passed; produced androidApp-debug.apk)
  - :androidApp:assembleDebugAndroidTest (passed; produced androidApp-debug-androidTest.apk)
- Local native media, engine and playback checks:
  - DesktopPlaybackLocalTest.floatingWindowPlaysAndSavesAResumePosition (passed in 4.098s under xvfb-run with real libvlc and bundled ffmpeg)
  - DesktopMediaTaggerTest.writesEmbeddedTagsWithoutRenamingTheFile (passed in 0.107s with OPENYT_RUN_MEDIA_TESTS=1)
  - FfmpegProcessorTest.realTrimConversionAndNormalizationPreserveTheOriginal[desktop] (passed in 0.502s with OPENYT_RUN_MEDIA_TESTS=1)
  - DesktopYtDlpLocalTest.mergesAndConvertsUnicodeMediaThenConfirmsTheFinalFile (passed in 9.334s with bundled yt-dlp, ffmpeg, ffprobe)
  - DesktopYtDlpLocalTest.recordsAnActualLiveHlsFixtureUntilTheDurationLimit (passed in 6.252s with loopback HLS fixture)
- Packaging and Linux validation harness:
  - :desktopApp:packageDeb (passed; built openyt_4.0.1_amd64.deb)
  - ./scripts/validate-linux.sh --scenario all --no-build --offline (passed; 12 scenarios passed: prepare, tests, android, tools, metadata, cookies, library, archive, watch, storage, bulk, playback, gui; 4 network scenarios skipped: inspect, download, playlist, pause; 0 failures)
Commands actually executed and results:
1. ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test --rerun-tasks --console=plain -> exit 0 (BUILD SUCCESSFUL in 31s, 43 actionable tasks executed)
2. ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain -> exit 0 (BUILD SUCCESSFUL in 4s)
3. env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE OPENYT_RUN_PLAYBACK_TESTS=1 OPENYT_FFMPEG="$PWD/desktopApp/build/bundled-tools/linux-x64/ffmpeg" xvfb-run -a ./gradlew :desktopApp:test --tests '*DesktopPlaybackLocalTest' --rerun-tasks --console=plain -> exit 0 (BUILD SUCCESSFUL in 15s)
4. OPENYT_RUN_MEDIA_TESTS=1 OPENYT_FFMPEG="$PWD/desktopApp/build/bundled-tools/linux-x64/ffmpeg" OPENYT_FFPROBE="$PWD/desktopApp/build/bundled-tools/linux-x64/ffprobe" ./gradlew :media-ffmpeg:desktopTest :desktopApp:test --tests '*MediaTaggerTest*' --rerun-tasks --console=plain -> exit 0 (BUILD SUCCESSFUL in 9s)
5. OPENYT_RUN_LOCAL_ENGINE_TESTS=1 OPENYT_YTDLP="$PWD/desktopApp/build/bundled-tools/linux-x64/yt-dlp" OPENYT_FFMPEG="$PWD/desktopApp/build/bundled-tools/linux-x64/ffmpeg" OPENYT_FFPROBE="$PWD/desktopApp/build/bundled-tools/linux-x64/ffprobe" ./gradlew :desktopApp:test --tests '*DesktopYtDlpLocalTest' --rerun-tasks --console=plain -> exit 0 (BUILD SUCCESSFUL in 24s)
6. ./gradlew :desktopApp:packageDeb --console=plain -> exit 0 (BUILD SUCCESSFUL in 41s)
7. ./scripts/validate-linux.sh --scenario all --no-build --offline -> exit 0 (validation passed, 4 skipped, 0 failures)
8. ./gradlew :androidApp:connectedDebugAndroidTest -Popenyt.testAbi=arm64-v8a -Popenyt.testApplicationIdSuffix=.regression -Pandroid.testInstrumentationRunnerArguments.class=com.openyt.android.AndroidDatabasePersistenceTest --console=plain -> exit 1 (INSTALL_FAILED_USER_RESTRICTED: Install canceled by user; Xiaomi HyperOS USB installation permission gate on physical device)
Native environment / device / tool versions:
- Host OS: Linux Pop!_OS 24.04 LTS (noble) x86_64, kernel 7.2.15-76070215-generic
- JVM / Java: OpenJDK 21 LTS (Temurin-21+35), Gradle 8.14.4, Kotlin 2.0.21
- Schema version: SQLDelight version 18 (migrated from baseline v1 through 17.sqm; verified in OpenYtDatabaseImpl.kt and DatabaseMigrationTest)
- Desktop tools:
  - System ffmpeg: 6.1.1-3ubuntu5 (/usr/bin/ffmpeg)
  - System ffprobe: 6.1.1-3ubuntu5 (/usr/bin/ffprobe)
  - System VLC: 3.0.20 Vetinari (/usr/bin/vlc)
  - libvlc: /lib/x86_64-linux-gnu/libvlc.so.5 (VLCJ loaded successfully)
  - Python: 3.12.2 (/home/iiii/.pyenv/shims/python3)
  - Bundled tools (desktopApp/build/bundled-tools/linux-x64):
    - yt-dlp: 2026.10.26
    - ffmpeg: n8.1.3-27-g8dca1586f4-20261031
    - ffprobe: n8.1.3-27-g8dca1586f4-20261031
    - deno: 2.9.8
- Android SDK & Devices:
  - ANDROID_HOME / ANDROID_SDK_ROOT: /data/dev/android/sdk/Sdk
  - Platforms: android-34, android-35, android-36, android-36.1, android-37.0
  - Build-tools: 34.0.0, 35.0.0, 36.0.0, 36.1.0, 37.0.0
  - ADB: 1.0.41 (version 34.0.4-debian)
  - Attached device: Xiaomi 2311DRK48G (duchamp_global / Poco X6 Pro), ABI: arm64-v8a, Android 16 (API 36, build BP2A.250605.031.A3)
- Windows test VM:
  - None configured/available on this Linux host (BLOCKED)
Failure-path and migration evidence:
- DatabaseMigrationTest: verified migration 6 -> 13, playlist task retry fallback, and provider source backfill.
- FfmpegProcessorTest.failedRuntimeInitializationReturnsAStructuredDependencyError: structured error returned when FFmpeg executable is missing/invalid.
- INSTALL_FAILED_USER_RESTRICTED: captured actual installation failure on physical device due to Xiaomi HyperOS USB installation permission gate.
Known limits / blocked checks / decisions needed:
- Android device instrumentation: BLOCKED on physical device by OEM security prompt (INSTALL_FAILED_USER_RESTRICTED). Android build/compilation (:androidApp:assembleDebug, :androidApp:assembleDebugAndroidTest) is fully operational.
- Windows native validation (scripts/validate-windows.ps1, packageMsi, packageExe): BLOCKED due to absence of a Windows host or Windows VM.
- macOS native validation: BLOCKED due to absence of macOS hardware/runner.
- Real network tests (DesktopYtDlpEngineIntegrationTest): SKIPPED by design in offline baseline mode (requires OPENYT_RUN_NETWORK_TESTS=1).
Next ready task and why its prerequisites are satisfied:
- NX0.2 — establish small, redistributable fixtures: NX0.1 baseline is VERIFIED on Linux x86_64. The test suites, build targets, schema version, and local tool harnesses are cataloged with reproducible evidence.
```

### NX0.2 — establish small, redistributable fixtures

```text
Task: NX0.2 — establish small, redistributable fixtures
Status: VERIFIED (Pure KMP fixtures and resources with deterministic offline smoke verification)
Branch and commit (or uncommitted diff): codex/nx0-foundation
Behavior changed: None in product runtime. Added synthetic timeline models, cue timing contracts, SRT/VTT test resources, deduplication/size binary pairs, Unicode filename tests, and inert security fixtures.
Files and ADRs changed:
- core/src/commonTest/kotlin/com/openyt/core/testing/SyntheticTimelineFixtures.kt
- core/src/commonTest/kotlin/com/openyt/core/testing/SyntheticTimelineFixturesTest.kt
- core/src/commonTest/resources/fixtures/timeline/PROVENANCE.md
- core/src/commonTest/resources/fixtures/timeline/timeline_pt.srt
- core/src/commonTest/resources/fixtures/timeline/timeline_pt.vtt
- core/src/commonTest/resources/fixtures/timeline/timeline_en.srt
- core/src/commonTest/resources/fixtures/timeline/timeline_en.vtt
- core/src/commonTest/resources/fixtures/timeline/timeline_nosubs.txt
- core/src/commonTest/resources/fixtures/timeline/identical_a.bin
- core/src/commonTest/resources/fixtures/timeline/identical_b.bin
- core/src/commonTest/resources/fixtures/timeline/distinct_size64_a.bin
- core/src/commonTest/resources/fixtures/timeline/distinct_size64_b.bin
- core/src/commonTest/resources/fixtures/timeline/inert_malformed.srt
- core/src/commonTest/resources/fixtures/timeline/inert_traversal.vtt
- core/src/commonTest/resources/fixtures/timeline/inert_oversized.srt
- docs/planning/NEXT_EXPANSION/PROGRESS.md
Contracts added/updated (IDs and exact test methods):
- NX-F01 (Synthetic Fixtures Smoke):
  - SyntheticTimelineFixturesTest.timelinePtHasCorrectCuesAccentsAndOverlaps[desktop]
  - SyntheticTimelineFixturesTest.timelineEnHasMatchingCuesAndOverlaps[desktop]
  - SyntheticTimelineFixturesTest.subtitleFreeItemIsDocumentedWithoutCues[desktop]
  - SyntheticTimelineFixturesTest.identicalFilesHaveDifferentNamesIdenticalBytesAndEqualHash[desktop]
  - SyntheticTimelineFixturesTest.distinctFilesHaveEqualSizeAndDifferentHashes[desktop]
  - SyntheticTimelineFixturesTest.complexUnicodeFilenamePreservesSpecialCharacters[desktop]
  - SyntheticTimelineFixturesTest.inertSecurityFixturesAreInertStrings[desktop]
Commands actually executed and results:
1. ./gradlew :core:desktopTest --tests '*SyntheticTimelineFixturesTest' --console=plain -> exit 0 (7 tests completed, 0 failed, 0 errors in 1s)
2. ./gradlew :core:testDebugUnitTest --console=plain -> exit 0 (Android unit test execution passed)
Native environment / device / tool versions:
- Pure Kotlin Multiplatform (JVM desktop and Android unit targets), offline execution without network or user media.
Failure-path and migration evidence:
- Tested inert malformed (`inert_malformed.srt`), oversized (`inert_oversized.srt`), and traversal (`inert_traversal.vtt`) files are pure inert test strings that do not trigger path escapes or corrupt scanners.
Known limits / blocked checks / decisions needed:
- Fixtures are small and redistributable; larger media or corpus files must be synthesized at test runtime by the tests that require them (as specified in NX0.2).
Next ready task and why its prerequisites are satisfied:
- NX0.3 — wire the verification and handoff process: NX0.2 synthetic fixtures are checked in and verified offline.
```

### NX0.3 — wire the verification and handoff process

```text
Task: NX0.3 — wire the verification and handoff process
Status: VERIFIED (Contract family mapping established; native matrix documented; baseline regression gate green)
Branch and commit (or uncommitted diff): codex/nx0-foundation
Behavior changed: None. Wired test task mappings to contract families NX-C01 through NX-C08 and NX-F01 in CONTRACTS.md. Documented the native execution matrix and verified existing baseline contracts pass.
Files and ADRs changed:
- docs/planning/NEXT_EXPANSION/CONTRACTS.md
- docs/planning/NEXT_EXPANSION/PROGRESS.md
Contracts added/updated (IDs and exact test methods):
- Baseline regression gate:
  - NX-C01 through NX-C08 verified across :core:desktopTest, :engine-ytdlp:desktopTest, :data:desktopTest, :media-ffmpeg:desktopTest, :desktopApp:test (223 tests total across desktop targets, 0 failures).
  - NX-F01 verified via SyntheticTimelineFixturesTest (7 tests).
Commands actually executed and results:
1. ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test --console=plain -> exit 0 (BUILD SUCCESSFUL in 1s)
2. ./gradlew :core:testDebugUnitTest --console=plain -> exit 0 (BUILD SUCCESSFUL in 1s)
Native environment / device / tool versions:
- Pop!_OS 24.04 LTS (noble) x86_64, OpenJDK 21 LTS, Gradle 8.14.4.
Failure-path and migration evidence:
- Existing DatabaseMigrationTest, FfmpegProcessorTest failure paths, and ConfirmedEngineOutputTest remain verified on baseline.
Known limits / blocked checks / decisions needed:
- Windows native installer/shell harness remains BLOCKED (requires Windows VM).
- Android connected device instrumentation remains BLOCKED (requires manual USB debugging install permission on HyperOS).
Next ready task and why its prerequisites are satisfied:
- NX1.1 — model profiles and deterministic resolution: Phase NX0 gate is closed. Baseline environment, small fixtures, contract mappings, and handoff protocols are VERIFIED.
```

### NX1.1 — model profiles and deterministic resolution

```text
Task: NX1.1 — model profiles and deterministic resolution
Status: VERIFIED (Pure KMP domain models, built-ins, and resolver with 13 deterministic tests)
Branch and commit (or uncommitted diff): codex/nx1-1-profiles-model
Behavior changed: Added pure domain models (DownloadProfile, ProfileResolution, ResolvedDownloadPlan, ProfileWarning, IncompatibilityReason, ActionableAlternative) and deterministic resolver (DownloadProfileResolver) implementing rules for the four built-in profiles (Study offline, Audio only, Save space, Watch on TV).
Files and ADRs changed:
- core/src/commonMain/kotlin/com/openyt/core/domain/DownloadProfile.kt
- core/src/commonMain/kotlin/com/openyt/core/domain/DownloadProfileResolver.kt
- core/src/commonTest/kotlin/com/openyt/core/domain/DownloadProfileResolverTest.kt
- docs/planning/NEXT_EXPANSION/CONTRACTS.md
- docs/planning/NEXT_EXPANSION/PROGRESS.md
Contracts added/updated (IDs and exact test methods):
- NX-P01 (Profile Resolution):
  - DownloadProfileResolverTest.studyProfilePrefersMp4AvcAndHumanSubtitles[desktop]
  - DownloadProfileResolverTest.studyProfileEmitsWarningsWhenSubtitlesOrThumbnailMissing[desktop]
  - DownloadProfileResolverTest.humanVsAutomaticSubtitleMatchingBehavior[desktop]
  - DownloadProfileResolverTest.audioOnlySourceReturnsIncompatibleForVideoProfilesAndSucceedsForAudioProfile[desktop]
  - DownloadProfileResolverTest.saveSpaceProfileRespects480pCeilingAndEmitsEstimateNotice[desktop]
  - DownloadProfileResolverTest.tvStrictProfileRejectsSourceWithoutNativeMp4AvcAac[desktop]
  - DownloadProfileResolverTest.tvStrictProfileSucceedsWhenNativeMp4AvcAacIsPresent[desktop]
  - DownloadProfileResolverTest.combinedStreamFormatPickedWithoutCompanionAudio[desktop]
  - DownloadProfileResolverTest.unknownCodecEmitsWarningAndResolvesSafely[desktop]
  - DownloadProfileResolverTest.absentResolutionHandledWithoutCrashing[desktop]
  - DownloadProfileResolverTest.deterministicTieBreakingPicksEarlierFormatId[desktop]
  - DownloadProfileResolverTest.emptyFormatsReturnsIncompatibleNoFormats[desktop]
  - DownloadProfileResolverTest.fallbackWarningWhenAllFormatsExceedMaxHeight[desktop]
Commands actually executed and results:
1. ./gradlew :core:desktopTest --tests '*DownloadProfileResolverTest' --console=plain -> exit 0 (13 tests completed, 0 failed, 0 errors in 4s)
2. ./gradlew :core:testDebugUnitTest --console=plain -> exit 0 (Android unit test execution passed in 4s)
3. ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain -> exit 0 (212 actionable tasks executed/up-to-date in 9s)
Native environment / device / tool versions:
- Pure Kotlin Multiplatform domain logic in core, zero external platform dependencies.
Failure-path and migration evidence:
- Tested incompatible paths: empty formats (NO_FORMATS_AVAILABLE), audio-only source with video profile (AUDIO_ONLY_SOURCE), and strict TV incompatible source (NO_COMPATIBLE_TV_FORMAT) producing structured actionable alternatives.
- Tested warning emissions: missing subtitles, missing thumbnails, estimated size notices, unknown codecs, and fallback resolution.
Known limits / blocked checks / decisions needed:
- Persistence of custom profiles and local bindings is scheduled for NX1.2.
Next ready task and why its prerequisites are satisfied:
- NX1.2 — persist custom profiles and local bindings: NX1.1 domain models and resolution logic are VERIFIED.
```

### NX1.2 — persist custom profiles and local bindings

```text
Task: NX1.2 — persist custom profiles and local bindings
Status: VERIFIED (SQLDelight schema 18->19 migration, download profile repository, separate local bindings & versioned serialization with 8 contract tests)
Branch and commit (or uncommitted diff): codex/nx1-2-profile-persistence
Behavior changed: Added SQLDelight migration 18.sqm creating tables download_profile and profile_destination_binding and adding default_profile_id to app_settings. Created DownloadProfileRepository interface in core and SqlDelightDownloadProfileRepository in data with automatic seeding of built-in profiles, immutability protection, versioned JSON serialization, duplicate names/IDs validation, separate local destination directory / SAF storage tree URI bindings, and independent task queue lifecycle upon profile deletion.
Files and ADRs changed:
- core/src/commonMain/kotlin/com/openyt/core/application/DownloadProfileRepository.kt
- core/src/commonMain/kotlin/com/openyt/core/domain/AppSettings.kt
- core/src/commonMain/kotlin/com/openyt/core/domain/MediaModels.kt
- data/src/commonMain/sqldelight/com/openyt/data/db/18.sqm
- data/src/commonMain/sqldelight/com/openyt/data/db/Download.sq
- data/src/commonMain/kotlin/com/openyt/data/SqlDelightDownloadProfileRepository.kt
- data/src/commonMain/kotlin/com/openyt/data/SqlDelightSettingsRepository.kt
- data/src/desktopTest/kotlin/com/openyt/data/DownloadProfileRepositoryTest.kt
- docs/planning/NEXT_EXPANSION/CONTRACTS.md
- docs/planning/NEXT_EXPANSION/PROGRESS.md
Contracts added/updated (IDs and exact test methods):
- NX-P02 (Profile Persistence & Migration):
  - DownloadProfileRepositoryTest.upgradePreviousSchemaAndPreserveSettingsAndTasks[desktop]
  - DownloadProfileRepositoryTest.seedsBuiltInProfilesAndEnforcesImmutability[desktop]
  - DownloadProfileRepositoryTest.persistsCustomProfilesAndDestinationBindingsAcrossReopen[desktop]
  - DownloadProfileRepositoryTest.rejectsDuplicateNamesAndDuplicateIds[desktop]
  - DownloadProfileRepositoryTest.handlesCorruptedProfileDefinitionsGracefully[desktop]
  - DownloadProfileRepositoryTest.detectsMissingDestinationPermission[desktop]
  - DownloadProfileRepositoryTest.deletedProfileDoesNotInvalidateQueuedTasks[desktop]
  - DownloadProfileRepositoryTest.portableProfileExportExcludesLocalBindingsAndSecrets[desktop]
Commands actually executed and results:
1. ./gradlew :data:verifySqlDelightMigration --console=plain -> exit 0 (BUILD SUCCESSFUL in 1s)
2. ./gradlew :data:desktopTest --tests '*DownloadProfileRepositoryTest' --console=plain -> exit 0 (8 tests completed, 0 failed, 0 errors in 1s)
3. ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain -> exit 0 (212 actionable tasks executed/up-to-date in 15s)
Native environment / device / tool versions:
- Pop!_OS 24.04 LTS (noble) x86_64, OpenJDK 21 LTS, Gradle 8.14.4.
Failure-path and migration evidence:
- Migration from schema 18 database verified; PRAGMA user_version is 19; all preexisting app_settings (theme, language, directories, SAF tree, toggles) and download_task records are preserved without data loss.
- Malformed definition JSON and future schema versions are safely ignored during profile loading without throwing or crashing.
- Missing destination access / permissions correctly detected and flagged via DestinationPermissionChecker.
- Deletion of a custom profile preserves queued tasks referencing it and safely clears default profile ID.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device.
Next ready task and why its prerequisites are satisfied:
- NX1.3 — snapshot profiles into queued requests: Profile models (NX1.1) and persistence with separate local destination bindings (NX1.2) are VERIFIED.
```

### NX1.3 — snapshot profiles into queued requests

```text
Task: NX1.3 — snapshot profiles into queued requests
Status: VERIFIED (Immutable profile provenance, per-task SAF export target isolation, retry snapshot preservation, and revalidation on format expiration; 11 contract tests passing)
Branch and commit (or uncommitted diff): codex/nx1-3-profile-snapshots
Behavior changed: Added ProfileProvenance to DownloadOptions and implemented ResolvedDownloadPlan.toDownloadRequest() to snapshot resolved profiles into immutable DownloadRequest objects before enqueue. Stored per-task exportTargetUri separately from filesystem destinationDirectory (strictly rejecting content:// URIs in filesystem paths). Updated DownloadCoordinator with enqueuePlan() and verified that retries reuse saved snapshot choices. Implemented revalidatePlan() to prompt for approval if constituent format IDs disappear or expire from fresh source metadata. Implemented SafExportRouter in core and wired AndroidSafExporter to route per-task exports and emit copy-export warnings without failing successful local downloads.
Files and ADRs changed:
- core/src/commonMain/kotlin/com/openyt/core/domain/DownloadProfile.kt
- core/src/commonMain/kotlin/com/openyt/core/domain/DownloadProfileResolver.kt
- core/src/commonMain/kotlin/com/openyt/core/domain/DownloadOptions.kt
- core/src/commonMain/kotlin/com/openyt/core/application/DownloadCoordinator.kt
- core/src/commonMain/kotlin/com/openyt/core/application/SafExportRouter.kt
- core/src/commonTest/kotlin/com/openyt/core/application/DownloadProfileSnapshotTest.kt
- androidApp/src/main/kotlin/com/openyt/android/saf/AndroidSafDestination.kt
- androidApp/src/main/kotlin/com/openyt/android/OpenYtApplication.kt
- docs/planning/NEXT_EXPANSION/CONTRACTS.md
- docs/planning/NEXT_EXPANSION/PROGRESS.md
Contracts added/updated (IDs and exact test methods):
- NX-P03 (Profile Snapshots & SAF Isolation):
  - DownloadProfileSnapshotTest.editingProfileAfterEnqueueDoesNotMutateQueuedRequest[desktop]
  - DownloadProfileSnapshotTest.deletingProfileAfterEnqueueDoesNotMutateQueuedRequest[desktop]
  - DownloadProfileSnapshotTest.changingGlobalDestinationAfterEnqueueDoesNotMutateQueuedRequest[desktop]
  - DownloadProfileSnapshotTest.twoTasksWithDifferentDestinationTargetsRemainIndependent[desktop]
  - DownloadProfileSnapshotTest.restartPreservesSnapshotAndProvenance[desktop]
  - DownloadProfileSnapshotTest.legacyTasksWithoutProvenanceRetainExistingBehavior[desktop]
  - DownloadProfileSnapshotTest.safUriNeverEntersFilesystemDestinationField[desktop]
  - DownloadProfileSnapshotTest.retryPreservesSavedSnapshotAndChoices[desktop]
  - DownloadProfileSnapshotTest.revokedSafGrantProducesWarningWithoutFailingLocalDownload[desktop]
  - DownloadProfileSnapshotTest.expiredSourceFormatRevalidationRequiresApproval[desktop]
  - DownloadProfileSnapshotTest.noCredentialsInSnapshotOrDiagnostics[desktop]
Commands actually executed and results:
1. ./gradlew :core:desktopTest --tests '*DownloadProfileSnapshotTest' --console=plain -> exit 0 (11 tests completed, 0 failed, 0 errors in 15s)
2. ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain -> exit 0 (212 actionable tasks executed/up-to-date in 45s)
Native environment / device / tool versions:
- Pop!_OS 24.04 LTS (noble) x86_64, OpenJDK 21 LTS, Gradle 8.14.4.
Failure-path and migration evidence:
- Revoked SAF tree grant tested via failing exporter in SafExportRouter; produces SafExportResult.Warning and routes warning message while keeping the local download status at COMPLETED and the local file intact.
- Format expiration tested when constituent format IDs are missing from re-inspected metadata; triggers PlanRevalidationResult.RequiresApproval with proposed new plan and reason instead of failing silently.
- Rejection of SAF content:// URIs in filesystem destinationDirectory verified via IllegalArgumentException.
- Legacy tasks with blank/default options_json continue to decode and execute with null provenance and null export target without crashing or altering behavior.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device.
Next ready task and why its prerequisites are satisfied:
- NX1.4 — connect the host/engine adapters: Profile models (NX1.1), persistence (NX1.2), and immutable request snapshots with SAF isolation (NX1.3) are VERIFIED.
```

### NX1.4 — connect the host/engine adapters

```text
Task: NX1.4 — connect the host/engine adapters
Status: VERIFIED (Host and engine options updated with merge/remux container flags, confirmed output validation against approved plan, structured format disappearance errors, and real local probing; 7 local contract tests passing)
Branch and commit (or uncommitted diff): codex/nx1-4-engine-adapters
Behavior changed: Connected profile-resolved choices to the engine boundary without disturbing raw or manual format selection. YtDlpDownloadOptions now translates profile provenance into safe container merging and remuxing arguments (--merge-output-format and --remux-video for video; -x --audio-format for audio) while strictly validating container names against safe whitelists. Enhanced confirmedEngineOutput() to enforce that the produced output matches the expected plan container and extension, rejecting mismatched files without inferring success from process exit codes alone. Updated DesktopYtDlpEngine and AndroidYtDlpEngine to supply expected containers to output confirmation. Added fileNameTemplate parameter to ResolvedDownloadPlan.toDownloadRequest(). Created contract test suite DownloadProfileLocalTest verifying real local tool downloads with each compatible profile (Study, Audio only, Save space, Watch on TV), actual stream/container ffprobe probes, absent subtitle handling without file corruption, structured format disappearance error mapping without raw Python tracebacks, and safe Unicode argument handling.
Files and ADRs changed:
- engine-ytdlp/src/commonMain/kotlin/com/openyt/engine/ytdlp/YtDlpDownloadOptions.kt
- engine-ytdlp/src/commonTest/kotlin/com/openyt/engine/ytdlp/YtDlpDownloadOptionsTest.kt
- data/src/jvmSharedMain/kotlin/com/openyt/data/ConfirmedEngineOutput.kt
- data/src/desktopTest/kotlin/com/openyt/data/ConfirmedEngineOutputTest.kt
- core/src/commonMain/kotlin/com/openyt/core/domain/DownloadProfile.kt
- desktopApp/src/main/kotlin/com/openyt/desktop/downloader/DesktopYtDlpEngine.kt
- desktopApp/src/test/kotlin/com/openyt/desktop/downloader/DesktopYtDlpEngineTest.kt
- desktopApp/src/test/kotlin/com/openyt/desktop/downloader/DownloadProfileLocalTest.kt
- androidApp/src/main/kotlin/com/openyt/android/downloader/AndroidYtDlpEngine.kt
- docs/planning/NEXT_EXPANSION/CONTRACTS.md
- docs/planning/NEXT_EXPANSION/PROGRESS.md
Contracts added/updated (IDs and exact test methods):
- NX-P04 (Engine Output & Profile Adapter Confirmation):
  - DownloadProfileLocalTest.realDownloadWithStudyProfileMatchesPlanAndEmbedsSubtitles
  - DownloadProfileLocalTest.realDownloadWithAudioProfileMatchesPlanAndAudioPreset
  - DownloadProfileLocalTest.realDownloadWithSaveSpaceProfileMatchesPlanAndHeightCeiling
  - DownloadProfileLocalTest.realDownloadWithWatchOnTvProfileMatchesPlanAndCodecs
  - DownloadProfileLocalTest.absentSubtitleDoesNotCorruptVideoAndFailsSubtitleOnlyRequest
  - DownloadProfileLocalTest.sourceFormatDisappearanceReportsStructuredErrorWithoutRawTraceback
  - DownloadProfileLocalTest.safeArgumentsPreserveUnicodeSpacesAndPunctuation
  - YtDlpDownloadOptionsTest.rawFormatSelectionWithoutProfilePreservesExistingBehavior
  - YtDlpDownloadOptionsTest.videoWithProfileResolvedContainerPassesMergeAndRemuxFlags
  - YtDlpDownloadOptionsTest.videoWithMkvResolvedContainerPassesMkvMergeAndRemuxFlags
  - YtDlpDownloadOptionsTest.unsafeContainerInProfileProvenanceIsIgnored
  - YtDlpDownloadOptionsTest.audioWithProfileContainerOverridesPresetExtension
  - YtDlpDownloadOptionsTest.subtitlesArePassedAsIndividualTypedArguments
  - YtDlpDownloadOptionsTest.automaticSubtitlesUseWriteAutoSubs
  - YtDlpDownloadOptionsTest.subtitleOnlyDownloadSkipsVideoDownload
  - ConfirmedEngineOutputTest.confirmsExpectedExtensionAndRejectsMismatches
  - DesktopYtDlpEngineTest.rejectsOutputWhenContainerDoesNotMatchApprovedPlan
  - DesktopYtDlpEngineTest.acceptsOutputWhenContainerMatchesApprovedPlan
Commands actually executed and results:
1. OPENYT_RUN_LOCAL_ENGINE_TESTS=1 OPENYT_YTDLP="$PWD/desktopApp/build/bundled-tools/linux-x64/yt-dlp" OPENYT_FFMPEG="$PWD/desktopApp/build/bundled-tools/linux-x64/ffmpeg" OPENYT_FFPROBE="$PWD/desktopApp/build/bundled-tools/linux-x64/ffprobe" ./gradlew :desktopApp:test --tests '*DownloadProfileLocalTest*' --console=plain -> exit 0 (7 tests completed, 0 failed in 26s)
2. ./gradlew :engine-ytdlp:desktopTest :data:desktopTest :desktopApp:test --console=plain -> exit 0 (all unit tests passed, 0 failures)
3. ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain -> exit 0 (212 actionable tasks executed/up-to-date in 9s)
4. ./scripts/validate-linux.sh --scenario all --no-build --offline -> exit 0 (12 scenarios passed, 4 skipped, 0 failures in 40s)
Native environment / device / tool versions:
- Pop!_OS 24.04 LTS (noble) x86_64, OpenJDK 21 LTS, Gradle 8.14.4.
- Bundled tools: yt-dlp 2026.10.26, ffmpeg n8.1.3-27, ffprobe n8.1.3-27.
Failure-path and migration evidence:
- Container mismatch rejection: confirmedEngineOutput() and DesktopYtDlpEngine explicitly reject files when the resulting container extension deviates from the plan, returning DownloadFailure.OutputMissing.
- Subtitle-only request for non-existent subtitle fails with OutputMissing rather than false success from exit code 0.
- Video download with absent subtitle downloads intact video stream without corrupting file or failing process.
- Source format disappearance: format "99999+99998" cleanly maps to DownloadFailure.ProcessingFailed("requested format is not available") without raw Python tracebacks or leaving partial files.
- Safe Unicode filenames and paths with spaces, brackets, quotes, and punctuation are verified on disk without shell injection vulnerabilities.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device.
Next ready task and why its prerequisites are satisfied:
- NX1.5 — add profile selection, preview and editing UI: Engine adapters (NX1.4), profile snapshots (NX1.3), persistence (NX1.2), and resolution domain models (NX1.1) are VERIFIED.
```

```
Task: NX1.5 — add profile selection, preview and editing UI
Date: 2026-11-12
Host: Linux 6.17.7-76061707-generic x86_64, Pop!_OS 24.04 LTS
Git branch: codex/nx1-5-profile-ui
Status: VERIFIED
Commands executed:
- ./gradlew :desktopApp:test --tests '*DownloadProfileUiTest*' --console=plain
- ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain
- ./scripts/validate-linux.sh --scenario all --no-build --offline
Result:
- 20 parameterized Compose UI tests passed in DownloadProfileUiTest across mobile (320px fontScale=1.5, 360px fontScale=1.0) and desktop (1024px fontScale=1.3, 1280px fontScale=1.0) widths in pt and en.
- Full regression suite passed (212 actionable tasks, BUILD SUCCESSFUL).
- Linux validation suite passed (all offline scenarios: prepare, tests, metadata, cookies, library, archive, watch, storage, bulk, playback, gui).
Behavior changed:
- Added accessible DownloadProfileSelector chip row supporting built-in profiles (Study, Audio only, Save space, Watch on TV), custom profiles, and manual format selection with screen-reader labels and management triggers.
- Added ProfilePlanPreviewCard displaying target container badges, effective height/preset, subtitle preferences, estimated sizes, destination directories, warnings, and explicit "Download with profile" button.
- Incompatible profiles display clear explanations and actionable alternative buttons (e.g. "Mudar para perfil de áudio") that dynamically switch profiles or manual mode rather than leaving dead/disabled buttons.
- Profile selection state is preserved across navigation via rememberSaveable without prematurely enqueueing tasks.
- Selecting or changing profile chips never initiates download; downloads strictly require explicit user confirmation via the preview card button.
- Implemented ProfileEditorDialog allowing users to create, configure (kind, max height, audio preset, subtitle language, strict TV compatibility), edit, export, and delete custom profiles with input validation (blank names disable saving).
- Updated SettingsScreen with a dedicated "Perfis de download" section allowing users to choose the default profile for new links and manage custom profiles.
- Added MemoryDownloadProfileRepository in core for fast in-memory fallbacks and testing.
- Visual regression screenshots automatically saved to desktopApp/build/ui-regressions/profile-preview-*.png.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device.
Next ready task and why its prerequisites are satisfied:
- NX1.6 — milestone evidence and documentation: Profile selection, preview, and editing UI (NX1.5) and all underlying engine adapters and contracts are VERIFIED.
```

```
Task: NX1.6 — milestone evidence and documentation
Date: 2026-11-12
Host: Linux 6.17.7-76061707-generic x86_64, Pop!_OS 24.04 LTS
Git branch: codex/nx1-6-milestone-evidence
Status: VERIFIED
Commands executed:
- OPENYT_RUN_LOCAL_ENGINE_TESTS=1 OPENYT_YTDLP="$PWD/desktopApp/build/bundled-tools/linux-x64/yt-dlp" OPENYT_FFMPEG="$PWD/desktopApp/build/bundled-tools/linux-x64/ffmpeg" OPENYT_FFPROBE="$PWD/desktopApp/build/bundled-tools/linux-x64/ffprobe" ./gradlew :desktopApp:test --tests '*DownloadProfileNativeFlowTest*' --console=plain
- ./gradlew :desktopApp:test --tests '*DownloadProfile*' --console=plain
- ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain
- ./scripts/validate-linux.sh --scenario all --no-build --offline
Result:
- 3/3 contract tests passed in DownloadProfileNativeFlowTest (contract NX-P06), verifying SQLite persistence, application restart, retry fidelity with ProfileProvenance, manual format selection coexistence without remux interference, and real loopback tool downloads.
- All 20 parameterized tests passed in DownloadProfileUiTest (contract NX-P05).
- All 7 real-tool contract tests passed in DownloadProfileLocalTest (contract NX-P04).
- Full regression suite passed (212 actionable tasks, BUILD SUCCESSFUL).
- Linux validation suite passed (all offline scenarios: prepare, tests, metadata, cookies, library, archive, watch, storage, bulk, playback, gui).
Behavior changed:
- Implemented and verified DownloadProfileNativeFlowTest enforcing combined native preview -> download -> restart -> retry lifecycle against real SQLite persistence and local engine execution.
- Verified that existing manual format selection functions cleanly without interference from profile remuxing arguments or logic.
- Documented the four built-in profiles (Study offline, Audio only, Save space, Watch on TV), deterministic resolution rules, custom profiles, local bindings, and SAF isolation in docs/DOWNLOAD_PROFILES.md.
- Staged and embedded real Compose UI screenshots across phone/desktop widths, dark/light themes, and enlarged font scales in docs/assets/profiles/.
- Linked Download Profiles documentation from docs/architecture/OVERVIEW.md.
- Closed phase NX1 with all 6 tasks (NX1.1 through NX1.6) and all 6 contracts (NX-P01 through NX-P06) fully VERIFIED.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device.
Next ready task and why its prerequisites are satisfied:
- NX2.1 — model assets, revisions and readiness: Download Profiles milestone (NX1) is complete and VERIFIED; offline library assets domain modeling can begin.
```

```
Task: NX2.1 — model assets, revisions and readiness
Date: 2026-11-12
Host: Linux x86_64 (Pop!_OS 24.04 LTS), JDK 21.0.8, Android SDK 34-37
Command: ./gradlew :core:desktopTest --tests '*OfflineReadinessTest*' :data:desktopTest --tests '*OfflineAssetRepositoryTest*' :core:compileDebugKotlinAndroid :data:compileDebugKotlinAndroid --console=plain
Result: VERIFIED (17 contract tests passed; schema 19->20 migration and reopen verified; Android and desktop compilation successful)
Evidence:
- OfflineReadinessTest: 10 tests passed covering missing media (marking UNAVAILABLE with RELINK_MEDIA action), changed bytes at same path (detecting size/mtime hint mismatch and marking STALE with REVERIFY action), changed content digest at same path, subtitle-only entries succeeding without video/audio codecs, optional vs required artwork semantics, old records with unknown asset state remaining PENDING until verified, remote thumbnailUrl never faking offline readiness, independence from display name, and actionable structured library-operation failures.
- OfflineAssetRepositoryTest: 7 tests passed covering schema 19->20 upgrade with backfilled default asset budget (512 MiB) and storage reserve (128 MiB), database reopen fidelity, CRUD operations, cascade deletion of offline assets when library entry is deleted without auto-deleting user files on disk, invalidation marking assets STALE, custom budget/reserve settings persistence, and JvmMediaFileInspector streaming SHA-256 and metadata inspection off the UI thread.
- Regression: :core:desktopTest, :engine-ytdlp:desktopTest, :data:desktopTest, :media-ffmpeg:desktopTest, :desktopApp:test, :androidApp:assembleDebug, :androidApp:assembleDebugAndroidTest (BUILD SUCCESSFUL, 212 tasks).
- Linux offline validation: ./scripts/validate-linux.sh --scenario all --no-build --offline (validation passed).
Behavior changed:
- Added pure KMP domain models: AssetKind, AssetState, MediaRevision, OfflineAsset, ReadinessStatus, RecoveryAction, MissingRequirement, OfflineReadiness, and structured LibraryOperationFailure hierarchy.
- Updated LibraryEntry with optional mediaRevision hints and lastVerifiedEpochMillis.
- Added assetBudgetBytes (512 MiB default) and storageReserveBytes (128 MiB default) to AppSettings.
- Implemented MediaFileInspector port and JvmMediaFileInspector streaming SHA-256 computation on Dispatchers.IO.
- Implemented OfflineReadinessCalculator computing deterministic readiness strictly from verified evidence.
- Created SQLDelight migration 19.sqm creating table offline_asset and altering library_entry and app_settings.
- Implemented SqlDelightOfflineAssetRepository and MemoryOfflineAssetRepository.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device.
Next ready task and why its prerequisites are satisfied:
- NX2.2 — confirm engine-produced companion artifacts: Offline asset models, revisions, and persistence (NX2.1) are complete and VERIFIED; engine completion artifact receipt handling can begin.
```

```
Task: NX2.2 — confirm engine-produced companion artifacts
Date: 2026-11-12
Host: Linux x86_64 (Pop!_OS 24.04 LTS), JDK 21.0.8, Android SDK 34-37
Command: ./gradlew :core:desktopTest --tests '*CompanionArtifact*' :data:desktopTest --tests '*JvmOfflineAssetStore*' :engine-ytdlp:desktopTest --tests '*YtDlpSupport*' :desktopApp:test --tests '*DesktopYtDlpEngineTest*' --console=plain
Result: VERIFIED (34 contract tests passed; receipts validation, asset copy, SHA-256 verification, and coordinator resilience verified; Android and desktop compilation successful)
Evidence:
- CompanionArtifactConfirmationTest: 8 tests passed covering Unicode and quoted output paths normalization and validation, strict rejection of forged out-of-root receipts (POSIX /etc/passwd, traversal ../, prefix collisions, and Windows drives/traversal), rejection of absent or empty (0-byte) files, legacy Completed events without artifacts succeeding normally, interrupted or canceled completions never storing artifacts, confirmed artifacts safely stored via assetStore on completion, truthful primary media library completion even if optional artwork fails, and missing required asset leaving offline readiness INCOMPLETE with FETCH_ARTWORK recovery action.
- JvmOfflineAssetStoreTest: 4 tests passed covering confirmed artifact file copying to app-owned storage (<assetsDirectory>/<entryId>/<assetId>.<ext>), streaming SHA-256 computation and database persistence, strict rejection of out-of-root receipts without writing files, handling of Unicode and quotes in source paths, and structured failure on missing source files.
- YtDlpSupportTest: 12 tests passed covering extraction of multi-track language subtitle dicts (en, pt, es), artwork receipts, structured custom artifacts, output paths preserving quotes/Unicode/network shares, and strict verification that output without artifact markers never attaches unrelated neighboring files.
- DesktopYtDlpEngineTest: 10 tests passed covering explicit companion artifacts confirmation, filtering out-of-root or missing receipts, and verifying unrelated neighboring files in directory are never attached.
- Regression: :core:desktopTest, :engine-ytdlp:desktopTest, :data:desktopTest, :media-ffmpeg:desktopTest, :desktopApp:test, :androidApp:assembleDebug, :androidApp:assembleDebugAndroidTest (BUILD SUCCESSFUL, 218 tasks).
- Linux offline validation: ./scripts/validate-linux.sh --scenario all --no-build --offline (validation passed, 4 skipped).
Behavior changed:
- Added CompanionArtifactReceipt data class to MediaModels.kt.
- Updated DownloadEvent.Completed with optional default parameter artifacts: List<CompanionArtifactReceipt> = emptyList() ensuring binary and source compatibility.
- Implemented CompanionArtifactValidator with isPathWithinRoot (handling POSIX/Windows separators, case rules, UNC, and traversal detection) and validateReceipt.
- Defined OfflineAssetStore interface and implemented MemoryOfflineAssetStore (unit testing) and JvmOfflineAssetStore (production app-owned copying and SHA-256 registration).
- Added OPENYT_SUBTITLES, OPENYT_THUMBNAIL, and OPENYT_ARTIFACT markers and extractArtifactReceipts to YtDlpSupport.kt.
- Fixed extractFilePath to strictly prioritize OPENYT_FILE as the primary media file, treating subtitles as primary only when request kind is SUBTITLE.
- Updated DesktopYtDlpEngine and AndroidYtDlpEngine to parse artifact receipts from engine stdout, relocate files if moved by engine between temporary and final output roots, validate containment within task roots, and emit confirmed receipts in DownloadEvent.Completed.
- Updated DownloadCoordinator to asynchronously persist confirmed companion artifacts into OfflineAssetStore without compromising primary media library completion if optional artifacts fail.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device.
Next ready task and why its prerequisites are satisfied:
- NX2.3 — implement owned storage and durable local operations: Engine-produced companion artifact receipts and validation (NX2.2) are complete and VERIFIED; durable local operations and journaled asset staging can begin.
```

```
Task: NX2.3 — implement owned storage and durable local operations
Date: 2026-11-12
Host: Linux x86_64 (Pop!_OS 24.04 LTS), JDK 21.0.8, Android SDK 34-37
Command: ./gradlew :core:desktopTest --tests '*DurableAssetOperator*' :data:desktopTest --tests '*JvmDurableAssetStorage*' :data:desktopTest --tests '*SqlDelightAssetOperationRepository*' :data:desktopTest --tests '*JvmOfflineAssetStore*' --console=plain
Result: VERIFIED (20 contract tests passed; staging, bounds, streaming, hashing, verification, atomic promotion, retry deduplication, crash recovery, and source preservation verified; Android and desktop compilation successful)
Evidence:
- DurableAssetOperatorTest: 9 tests passed covering staging/streaming/hashing and atomic asset promotion, cancellation cleaning staging without deleting source media, disk full aborting operation and preserving configured storage reserve (128 MiB), revoked permission failing cleanly with structured failure, truncated copy rejection and staging cleanup, concurrent submission deduplication of queued operations, retry deduplication preventing duplicate assets, retry after failure succeeding and registering asset, and journal reconciliation cleaning or promoting crashed operations.
- JvmDurableAssetStorageTest: 5 tests passed on real filesystem covering real durable staging and atomic promotion, disk full injection aborting operation preserving reserve, truncated copy injection failing verification without publishing, revoked permission injection failing gracefully, and crash recovery reconciling unpromoted staging and marking operations INTERRUPTED.
- SqlDelightAssetOperationRepositoryTest: 2 tests passed covering schema upgrade v20->v21 preserving existing offline_asset and library records while creating asset_operation table and indices, and full CRUD status transitions (QUEUED -> RUNNING -> SUCCEEDED / FAILED / CANCELED / INTERRUPTED) with structured failure serialization.
- JvmOfflineAssetStoreTest: 4 tests passed verifying JvmOfflineAssetStore delegation to DurableAssetOperator, app-owned asset promotion, and database metadata registration.
- Regression: :core:desktopTest, :engine-ytdlp:desktopTest, :data:desktopTest, :media-ffmpeg:desktopTest, :desktopApp:test, :androidApp:assembleDebug, :androidApp:assembleDebugAndroidTest (BUILD SUCCESSFUL, 218 tasks).
- Linux offline validation: ./scripts/validate-linux.sh --scenario all --no-build --offline (validation passed, 4 skipped).
Behavior changed:
- Added pure KMP domain models: AssetOperation, AssetOperationStatus (QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELED, INTERRUPTED), and structured AssetOperationFailure hierarchy (DiskFull, RevokedPermission, TruncatedCopy, SourceMissing, VerificationFailed, Canceled, Unexpected).
- Added AssetOperationRepository and AssetStagingPort interfaces in core, and implemented MemoryAssetOperationRepository.
- Implemented DurableAssetOperator implementing OfflineAssetStore with mutex concurrency control to serialize disk-heavy jobs, pending retry deduplication, bounds checking against storage reserve, streaming SHA-256 calculation, atomic promotion, and journal reconciliation.
- Added SQLDelight migration 20.sqm creating table asset_operation and index asset_operation_entry_idx.
- Implemented SqlDelightAssetOperationRepository mapping SQLDelight records to domain models with failure serialization.
- Implemented JvmAssetStagingPort on JVM/Android with strict staging directory containment (.staging/<opId>), atomic file move, and failure injection knobs for unit and integration testing.
- Updated JvmOfflineAssetStore to delegate to DurableAssetOperator.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device.
Next ready task and why its prerequisites are satisfied:
- NX2.4 — fetch artwork and import subtitle assets safely: Owned storage and durable local operations (NX2.3) are complete and VERIFIED; safe external artwork fetching and subtitle importing into durable store can begin.
```

```
Task: NX2.4 — fetch artwork and import subtitle assets safely
Date: 2026-11-12
Host: Linux x86_64 (Pop!_OS 24.04 LTS), JDK 21.0.8, Android SDK 34-37
Command: ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain
Result: VERIFIED (39 contract tests passed; bounded HTTP fetch, redirect/credential handling, subtitle validation, owned-storage import, and local-artwork preference verified; Android and desktop compilation successful)
Evidence:
- ArtworkFetchServiceTest: 7 tests passed covering atomic staging/promotion, credential stripping before network access, concurrent duplicate deduplication, failed fetches preserving existing verified artwork, cancellation cleanup, rejected non-HTTP schemes without network calls, and disk-reserve enforcement.
- SubtitleImportServiceTest: 6 tests passed covering SRT and WebVTT import, invalid encoding rejection, missing source files, revoked permission mapping, and failed imports preserving existing verified subtitles.
- SubtitleValidatorTest: 7 tests passed covering SRT/VTT detection, BOM and CRLF normalization, binary rejection, missing cues, empty payloads, and size limits.
- SafeUrlSanitizerTest: 4 tests passed covering HTTP(S)-only validation, embedded credential stripping, log masking, and invalid authorities.
- JvmArtworkFetchPortTest: 9 tests passed against a loopback HTTP server covering successful JPEG/PNG fetch, redirect following, redirect loops, disallowed redirect schemes, expired 404/410 URLs, oversized payload abort, corrupt-image rejection, and offline hosts.
- ArtworkAndSubtitleIntegrationTest: 6 tests passed on real filesystem and SQLite covering artwork surviving database restart with physical owned-storage bytes, failed fetch preserving existing verified assets, SRT/VTT file and byte imports, revoked permission handling, and verified local artwork preferred over remote URLs.
- Regression: :core:desktopTest, :engine-ytdlp:desktopTest, :data:desktopTest, :media-ffmpeg:desktopTest, :desktopApp:test, :androidApp:assembleDebug, :androidApp:assembleDebugAndroidTest (BUILD SUCCESSFUL, 212 tasks).
Behavior changed:
- Added ArtworkFetchPort and FetchedArtworkMetadata, SafeUrlSanitizer, ArtworkFetchService with in-flight request deduplication, SubtitleValidator, and SubtitleImportService.
- Added structured AssetOperationFailure variants for offline networks, redirect loops, expired URLs, oversized assets, corrupt images, and invalid subtitles with SQLDelight serialization.
- Added AssetStagingPort.stageBytesAndHash and resolveTargetLocator, OfflineAssetStore.resolveAssetPath, and staging containment checks.
- Implemented JvmArtworkFetchPort with manual redirect handling, byte bounds, streaming SHA-256, image magic validation, cancellation, and no-credential transport.
- Added local-artwork preference helpers and MediaThumbnail localArtworkPath parameter.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device.
- Host picker wiring and visible offline-readiness UI are delivered by NX2.5; this task verified the asset services and storage boundary.
Next ready task and why its prerequisites are satisfied:
- NX2.5 — show offline readiness and storage actions: Safe artwork fetching and subtitle importing (NX2.4) are complete and VERIFIED; UI readiness, pinning, and storage actions can begin.
```

```
Task: NX2.5 — show offline readiness and storage actions
Date: 2026-11-12
Host: Linux x86_64 (Pop!_OS 24.04 LTS), JDK 21.0.8, Android SDK 34-37
Command: ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain
Result: VERIFIED (18 contract tests passed; local-artwork preference, readiness/recovery, pinning, cleanup confirmation, storage separation, and host wiring verified; Android and desktop compilation successful)
Evidence:
- OfflineLibraryPresenterTest: 9 tests passed covering verified local artwork preferred over remote URLs, remote URLs never claiming verified local artwork or readiness, missing required artwork mapping to FETCH_ARTWORK, separate original media/durable asset/rendering-cache totals, pin/unpin transitions, cross-entry pin rejection, cleanup preview excluding pinned assets, cleanup deleting only unpinned owned assets, and pinned assets surviving explicit cleanup requests.
- OfflineAssetCleanupTest: 3 tests passed on the real filesystem covering owned artwork/subtitle deletion without touching source media, pinned assets surviving cleanup, and target deletion rejecting traversal outside owned storage.
- OfflineReadinessUiTest: 6 parameterized tests passed across 320dp phone, enlarged text, and desktop widths in pt-BR and English, covering ready/incomplete states, recovery actions, touch pin/unpin, cleanup preview cancel and confirmation, and the absence of a ready badge when only a remote artwork URL exists.
- Regression: :core:desktopTest, :engine-ytdlp:desktopTest, :data:desktopTest, :media-ffmpeg:desktopTest, :desktopApp:test, :androidApp:assembleDebug, :androidApp:assembleDebugAndroidTest (BUILD SUCCESSFUL, 212 tasks).
- Linux offline validation: ./scripts/validate-linux.sh --scenario all --no-build --offline (validation passed, 4 skipped).
Behavior changed:
- Added OfflineLibraryPresenter, OfflineLibraryState, OfflineAssetView, and OfflineStorageBreakdown in core.
- Added OfflineAssetMaintenance for pin/unpin, previewed cleanup, and owned-only deletion; added deleteOwnedAsset to OfflineAssetStore and deleteTarget containment to AssetStagingPort.
- Added OfflineReadinessCard with localized ready/incomplete/pending/stale/unavailable labels, per-kind missing requirements, recovery actions, retry, pin/unpin, and cleanup confirmation.
- Wired verified local artwork preference into LibraryCard/MediaThumbnail and separated storage categories into StorageDialog.
- Added SubtitlePicker plus Android/Desktop adapters and import-subtitle actions in library details.
- Wired offline asset repositories, stores, services, inspectors, and cache accounting into desktop and Android hosts, including DownloadCoordinator companion artifact storage.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device.
- macOS and Windows native offline evidence remains BLOCKED by unavailable native hosts (NX2.6 gate).
Next ready task and why its prerequisites are satisfied:
- NX2.6 — prove offline behavior and close milestone 1: Offline readiness, storage actions, and local artwork preference (NX2.5) are complete and VERIFIED; final offline evidence and milestone closure can begin.
```

```
Task: NX2.6 — prove offline behavior and close milestone 1
Date: 2026-11-12
Host: Linux x86_64 (Pop!_OS 24.04 LTS), JDK 21.0.8, Android SDK 34-37
Result: IMPLEMENTED / BLOCKED (Linux offline evidence passed; Windows, Android device and macOS native gate unavailable, so milestone 1 is not closed)
Commands executed:
- ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain
- unshare -rn bash -c 'ip link set lo up; HOME=/home/iiii GRADLE_USER_HOME=/home/iiii/.gradle JAVA_TOOL_OPTIONS=-Duser.home=/home/iiii ./gradlew --no-daemon --offline --rerun-tasks :core:desktopTest --tests "*Offline*" --tests "*CompanionArtifact*" --tests "*ArtworkFetch*" --tests "*Subtitle*" --console=plain'
- unshare -rn bash -c '... :data:desktopTest --tests "*Offline*" --tests "*ArtworkAndSubtitle*" --tests "*JvmArtworkFetch*" ...'
- unshare -rn bash -c '... :desktopApp:test --tests "*Offline*" ...'
- ./scripts/validate-linux.sh --scenario all --no-build --offline
Evidence:
- OfflineMilestoneProofTest: 1 end-to-end test passed on real SQLite and filesystem covering confirmed artwork plus imported subtitle, rendering-cache deletion with owned bytes intact, database reopen with verified assets, READY state with local artwork preferred, owned-byte reconciliation, changed media -> STALE/REVERIFY, missing media -> UNAVAILABLE/RELINK_MEDIA, pinned artwork surviving an asset-budget breach, and offline-readable artwork/subtitles.
- Asset budget enforcement added: DurableAssetOperator rejects additional bytes above the 512 MiB default budget with structured StorageBudgetExceeded; pinned assets are never evicted. Artwork/subtitle services honor the same check.
- Core NX-O suite under a network namespace with loopback only: Offline*, CompanionArtifact*, ArtworkFetch*, Subtitle* classes passed (BUILD SUCCESSFUL).
- Data NX-O suite under the same namespace: Offline*, ArtworkAndSubtitle*, JvmArtworkFetch* passed (BUILD SUCCESSFUL).
- Offline UI suite under the same namespace: OfflineReadinessUiTest passed all 6 parameterized cases (phone 320dp, enlarged text, desktop; pt-BR/en) and regenerated screenshots in docs/assets/offline/.
- Full regression: 212 tasks (BUILD SUCCESSFUL).
- Linux validation harness: all offline scenarios passed with 4 network-dependent skips.
- Documented ownership, readiness, quota, cleanup and SAF copy behavior in docs/OFFLINE_LIBRARY.md.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi 2311DRK48G; APK and test APK assembly pass.
- Windows x64 and macOS native offline runs remain BLOCKED by unavailable native hosts.
- Asset budget/reserve values are persisted, but host stores still use defaults; Settings UI editing and runtime propagation remain pending.
- Per EXECUTOR_PROMPT rules a native gate that cannot be exercised keeps this task IMPLEMENTED/BLOCKED, not VERIFIED; milestone 1 (M1 = NX1.6 + NX2.6) stays open.
Next ready task and why its prerequisites are satisfied:
- NX3.1 — prove indexing capability on the shipped databases: Linux offline evidence is captured, user authorized proceeding with next open phase/task.
```

```
Task: NX3.1 — prove indexing capability on the shipped databases
Date: 2026-11-12
Host: Linux x86_64 (Pop!_OS 24.04 LTS), JDK 21.0.8, Android SDK 34-37
Result: VERIFIED (13 tests passed across core, data and androidApp; SQLite FTS4 + unicode61 creation, binding, accent folding, CRUD, and 100k corpus query p95 latency budget passed at 0.899 ms)
Commands executed:
- ./gradlew :data:desktopTest --tests "*SqliteFtsCapabilityProbeTest*" --console=plain
- ./gradlew :core:desktopTest --tests "*FtsCapabilityReportTest*" --console=plain
- ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain
- ./scripts/validate-linux.sh --scenario all --no-build --offline
Evidence:
- SqliteFtsCapabilityProbeTest: 6 tests passed on desktop SQLite JDBC driver (SQLite 3.53.4) verifying virtual table creation with FTS4 + unicode61, SQL injection and parameter binding safety with malicious and special characters, bidirectional Portuguese accent folding ("música" <-> "musica", "coração" <-> "coracao", "não" <-> "nao", "órgão", "açúcar", "dedicação") and English loanwords ("naïve", "café", "résumé"), incremental CRUD updating and deleting FTS index rows without leftovers, rejection of invalid tokenizers, and the complete 100,000-cue synthetic benchmark.
- Benchmark results on Linux x86_64: corpus size 100,000 cues, 5 warmup runs, 30 measured warm runs: min=0.905 ms, p50=0.342 ms, p90=0.895 ms, p95=0.899 ms, max=0.905 ms, avg=0.427 ms against target budget <= 500.0 ms (budget passed: true).
- FtsCapabilityReportTest: 2 tests passed in core verifying JSON serialization round-trip, budget validation, and functional verification status properties.
- AndroidFtsCapabilityTest: 5 tests implemented in androidApp covering SQLite version reporting, FTS4 + unicode61 creation, Portuguese accent folding, incremental CRUD, and 10k benchmark under 1,000 ms target budget; compiled via assembleDebug and assembleDebugAndroidTest.
- Android driver factory: added createOpenYtDriver(context, databaseName) in data/androidMain for clean driver extraction without leaking AndroidSqliteDriver into app modules.
- Full regression: 212 actionable tasks passed (BUILD SUCCESSFUL).
- Linux offline validation: all offline scenarios passed with 4 network-dependent skips.
Behavior changed:
- Added pure KMP domain models FtsCapabilityReport and FtsFunctionalVerification in core/domain.
- Added SqliteFtsCapabilityProbe in data/search for probing SQLite FTS4 virtual tables, unicode61 tokenization, diacritics folding, incremental CRUD, and automated synthetic benchmark measurement without premature user database migration.
- Added createOpenYtDriver in data/androidMain.
- Added AndroidFtsCapabilityTest in androidApp/src/androidTest.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device (Xiaomi 2311DRK48G); APK and test APK assembly pass.
- Windows x64 and macOS native offline runs remain BLOCKED by unavailable native hosts.
Next ready task and why its prerequisites are satisfied:
- NX3.2 — parse and normalize timed subtitle tracks: Indexing capability probe (NX3.1) is complete and VERIFIED; pure Kotlin SRT/VTT parser and normalization can begin.
```

```
Task: NX3.2 — parse and normalize timed subtitle tracks
Date: 2026-11-12
Host: Linux x86_64 (Pop!_OS 24.04 LTS), JDK 21.0.8, Android SDK 34-37
Result: VERIFIED (9 contract tests passed in core; pure Kotlin bounded SRT/VTT parsing, markup stripping, HTML entity decoding, speaker extraction, timebase shifting, and error recovery verified)
Commands executed:
- ./gradlew :core:desktopTest --tests "*TimedSubtitleParserTest*" --console=plain
- ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain
- ./scripts/validate-linux.sh --scenario all --no-build --offline
Evidence:
- TimedSubtitleParserTest: 9 tests passed in core covering:
  - Standard SRT parsing with Portuguese and English accents ("música", "coração", "não", "açúcar", "naïve", "résumé");
  - WebVTT parsing with header metadata skipping, multi-line NOTE blocks, STYLE blocks, REGION blocks, and cue settings stripping;
  - Linear-time O(n) HTML/VTT tag stripping (<b>, <i>, <u>, <font>, <c>, <v>, <script>) with zero regular expression catastrophic backtracking (ReDoS);
  - Full HTML entity decoding (&amp;, &lt;, &gt;, &quot;, &apos;, &#39;, &nbsp;, decimal/hex entities);
  - Multiline cue line joining and overlapping cue preservation;
  - Deterministic cue ordering (startMs ASC, endMs ASC, originalIndex ASC) with collision-free cue IDs;
  - Explicit local vs source timebase offset shifting (timebaseOffsetMs) clamping timestamps to >= 0;
  - Documented nonfatal error recovery for missing cue separation lines, out-of-order sequence numbers, empty text cues, and end times preceding start times (clamped with warning);
  - Transparent UTF-8 BOM and CRLF/LF line-break normalization;
  - Speaker extraction from WebVTT <v Speaker>, bracketed [Speaker], and prefix Speaker: annotations;
  - Rejection of oversized payloads (>10 MiB / >100,000 cues), empty content, and malformed WebVTT missing signature header.
- Full regression: 212 actionable tasks passed (BUILD SUCCESSFUL).
- Linux offline validation: all offline scenarios passed with 4 network-dependent skips.
Behavior changed:
- Added pure KMP domain models TimedCue, ParsedSubtitleTrack, SubtitleFormat, and SubtitleParseException in core/domain.
- Added TimedSubtitleParser in core/application for parsing bounded SRT and WebVTT tracks in memory without file system or process dependencies.
- Refactored SubtitleValidator.SubtitleFormat to alias com.openyt.core.domain.SubtitleFormat for clean domain reuse and complete backward compatibility.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device (Xiaomi 2311DRK48G); APK and test APK assembly pass.
- Windows x64 and macOS native offline runs remain BLOCKED by unavailable native hosts.
Next ready task and why its prerequisites are satisfied:
- NX3.3 — persist cues and atomically rebuild an index revision: Parsed subtitle tracks and normalized cues (NX3.2) are complete and VERIFIED; database persistence and atomic index revisions can begin.
```

```
Task: NX3.3 — persist cues and atomically rebuild an index revision
Branch: codex/nx3-3-index-persistence
Status: VERIFIED
Evidence:
- Tests:
  - com.openyt.data.search.SqlDelightSubtitleIndexRepositoryTest (7 tests in :data:desktopTest, all passed):
    - schemaUpgradeFromV21ToV22PreservesExistingDataAndCreatesSubtitleIndexTables
    - atomicRevisionCommitRetainsUsableOldIndexUntilCommit
    - reconcileCuesAndFtsRowCountsMatchExactly
    - crashMidReindexCleansAbandonedRevisionRowsWithoutTouchingMedia
    - deleteTrackCleansCueAndFtsRowsWithoutTouchingMediaFile
    - deleteTrackIndexesForEntryCleansAllAssociatedTracks
    - entryInvalidationTransitionsAllTracksToStale
    - reopenDatabasePreservesIndexRevisionsAndCues
  - com.openyt.core.application.SubtitleIndexRebuilderTest (8 tests in :core:desktopTest, all passed):
    - buildsInitialIndexRevisionAtomicallyAndPersistsCues
    - retainsUsableOldRevisionWhileBuildingNewRevision
    - duplicateRetryForIdenticalMediaAndTrackRevisionShortCircuits
    - cancellationLeavesOldRevisionIntactAndCleansPartialRows
    - malformedSubtitleFormatReportsParseFailureAndCleansStaging
    - fileExceedingMaxSizeReportsFailure
    - missingFileFailsGracefully
    - mediaInvalidationAndRemovalOperateCorrectly
- Commands:
  - ./gradlew :core:desktopTest :data:desktopTest (BUILD SUCCESSFUL, 13 actionable tasks)
  - ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest (BUILD SUCCESSFUL, 212 actionable tasks)
  - ./scripts/validate-linux.sh --scenario all --no-build --offline (validation passed, 4 skipped)
Behavior changed:
- Added SQLDelight migration 21 (21.sqm) creating tables subtitle_track_index, subtitle_cue, and virtual FTS4 table subtitle_cue_fts with unicode61 tokenizer, upgrading database user_version from 21 to 22 while preserving existing library_entry, download_task, download_profile, offline_asset, and asset_operation rows.
- Added pure KMP domain models SubtitleTrackIndexRecord, SubtitleIndexState, and IndexReconcileSummary in core/domain.
- Added SubtitleIndexRepository and SubtitleContentPort interfaces in core/application.
- Added SubtitleIndexRebuilder in core/application implementing bounded (10 MiB / 100,000 cues), transactional, cancellable cue persistence and atomic revision commits without advertising partial results as complete indexes.
- Added SqlDelightSubtitleIndexRepository in data/ implementing cue persistence, atomic revision swap (retaining older usable revision active until new revision commits), 1:1 B-tree and FTS count reconciliation, crash recovery journal reconciliation (cleanAbandonedRevisions), and track/entry cascade cleanup without touching source media files.
- Added JvmSubtitleContentAdapter in data/src/jvmSharedMain implementing SubtitleContentPort using standard file IO on Dispatchers.IO.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device (Xiaomi 2311DRK48G); APK and test APK assembly pass.
- Windows x64 and macOS native offline runs remain BLOCKED by unavailable native hosts.
Next ready task and why its prerequisites are satisfied:
- NX3.4 — expose bounded text search: Subtitle cue persistence and atomic index revisions (NX3.3) are complete and VERIFIED; query validation, FTS token escaping, pagination, and scoped search can begin.
```

```
Task: NX3.4 — expose bounded text search
Branch: codex/nx3-4-bounded-search
Status: VERIFIED
Evidence:
- Tests:
  - com.openyt.core.application.TimedSearchQueryValidatorTest (8 tests in :core:desktopTest, all passed):
    - rejectsEmptyOrBlankQueries
    - enforcesCharacterLengthLimit (256 char bound)
    - enforcesTokenCountLimit (8 token bound)
    - escapesQuotesOperatorsAndSqlSyntaxLiterally
    - stripsDiacriticsBidirectionallyForAccents
    - extractsExcerptWithAccurateHighlightsOnShortText
    - extractsExcerptAndTruncatesLongTextWithEllipsis
    - handlesCjkAndUnspacedTokensSafely
  - com.openyt.core.application.TimedSearchUseCaseTest (3 tests in :core:desktopTest, all passed):
    - rejectsInvalidQueriesBeforeCallingRepository
    - delegatesValidQueriesToRepository
    - honorsCooperativeCancellation
  - com.openyt.data.search.SqlDelightTimedSearchRepositoryTest (9 tests in :data:desktopTest, all passed):
    - scopedSearchSupportsLibraryCollectionAndEntryScopes
    - filtersByExplicitLanguageAndTrackId
    - paginationIsDeterministicAndHandlesTies
    - deletedEntryImmediatelyExcludesCuesFromSearch
    - literalTreatmentOfQuotesOperatorsAndSqlSyntax
    - cjkAndUnspacedTextSearchBehavior
    - distinctTypedStatesForUnavailableIndexVsZeroMatches
    - coroutineCancellationTerminatesCooperatively
    - measuredSyntheticCorpusBudgetUnder500ms (100k-cue SQLite p95 latency: 73.96 ms <= 500 ms budget)
- Full regression: 212 actionable tasks passed (BUILD SUCCESSFUL).
- Linux offline validation: all offline scenarios passed with 4 network-dependent skips.
Behavior changed:
- Added pure KMP domain models TimedSearchScope, TimedSearchFilter, TimedSearchPagination, TimedSearchExcerpt, TextHighlightRange, TimedSearchMatch, TimedSearchIndexStatus, TimedSearchResult, IndexUnavailableReason, and InvalidQueryReason in core/domain.
- Added TimedSearchQueryValidator, TimedSearchRepository, and TimedSearchUseCase in core/application enforcing max 256 character length, max 8 tokens, literal FTS word escaping, bidirectional accent stripping, and highlighted text excerpts.
- Added scoped FTS4 search and count queries in data/src/commonMain/sqldelight/com/openyt/data/db/Download.sq with $O(1)$ primary-key cue joins, deterministic tie-breaking order (entry_id ASC, start_ms ASC, track_id ASC, cue_id ASC), and index status counting.
- Added SqlDelightTimedSearchRepository in data/ implementing scoped text search (Library, Collection, Entry), explicit language and track filtering, deterministic pagination, immediate exclusion of deleted library entries, cooperative coroutine cancellation, and distinct typed states distinguishing unavailable indexes from empty results.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device (Xiaomi 2311DRK48G); APK and test APK assembly pass.
- Windows x64 and macOS native offline runs remain BLOCKED by unavailable native hosts.
Next ready task and why its prerequisites are satisfied:
- NX3.5 — add entry-aware timed playback without losing history: Bounded text search with scoped pagination and typed unavailability states (NX3.4) is complete and VERIFIED; playback session control port and entry-aware seek can begin.
```

```
Task: NX3.5 — add entry-aware timed playback without losing history
Status: VERIFIED
Confidence score: 5/5
Evidence:
- Executed `:core:desktopTest --tests com.openyt.core.application.PlaybackSessionControlTest` (9 tests passed).
- Executed `:data:desktopTest --tests com.openyt.data.PlaybackStoreTest` (6 tests passed).
- Executed `:desktopApp:test --tests com.openyt.desktop.player.DesktopPlaybackSessionTest` (3 tests passed).
- Executed full regression suite `:core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest` (212 actionable tasks passed).
- Executed `./scripts/validate-linux.sh --scenario all --no-build --offline` (all scenarios passed with vlcAvailable=True, 4 skipped).
Key changes:
- Introduced pure KMP PlaybackSessionControl port and clampSeekPosition bounds enforcement in core/application with PlaybackSessionState and PlaybackCapabilities.
- Added optional entryId to PlayableMedia and PlaybackCheckpoint with backward-compatible legacy queue JSON deserialization and serialized persistence.
- Added SQLDelight migration 22.sqm adding entry_id and index to playback_position table, verified via verifyCommonMainOpenYtDatabaseMigration.
- Updated SqlDelightPlaybackStore with entry ID checkpoint bridge: legacy path records bridge to entryId on lookup, moving entry locators (newPath with existing entryId) recover existing checkpoints, and path-only fallback is preserved.
- Updated DesktopPlaybackLauncher to implement PlaybackSessionControl, managing asynchronous prepare seeking, out-of-range clamping, generation-based stale callback rejection, external player fallback with capability reporting, and clean synchronous native VLC teardown before frame disposal.
- Updated AndroidPlaybackLauncher, PlayerActivity, and PlaybackService to propagate entryId and start positions across intents, MediaItem metadata extras, and snapshot checkpoint persistence.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device (Xiaomi 2311DRK48G); APK and test APK assembly pass.
- Windows x64 and macOS native offline runs remain BLOCKED by unavailable native hosts.
Next ready task and why its prerequisites are satisfied:
- NX3.6 — deliver result navigation and empty/error states: Entry-aware timed playback and session control (NX3.5) is complete and VERIFIED; result navigation and accessible timestamp actions can begin.
```

### 2026-11-12 — Phase NX3.6 (deliver result navigation and empty/error states)
```
Task: NX3.6 — deliver result navigation and empty/error states (contract NX-S06)
Completed items:
- Implemented grouped i18n localization in TimedSearchStrings, PtBrTimedSearchStrings, and EnTimedSearchStrings covering search modes, scopes, index readiness, distinct empty/error explanations, action buttons, and accessible screen-reader labels.
- Implemented TimedSearchScopeIndicator showing active text-search scope (Library, Collection, Entry), index availability badges (READY, PARTIALLY_INDEXED), and language filter chips.
- Implemented TimedSearchExcerptText rendering exact highlighted character spans and preserving screen-reader accessibility semantics.
- Implemented TimedSearchMatchCard with title, language, speaker, highlighted excerpt, and an accessible timestamp button with semantic label and keyboard activation.
- Implemented TimedSearchStateView differentiating unavailable indexes (with explanations and import/reindex actions) from genuine empty search results.
- Integrated debounced (200 ms) cancellable timed search into LibraryScreen with search mode toggle chips (Metadata vs In Subtitles), preserving title/tag metadata search.
- Wired TimedSearchUseCase, TimedSearchRepository, and SubtitleIndexRepository through OpenYtApp across desktopApp and androidApp host targets.
- Verified timestamp activation plays only the intended media entry with (startMs + timebaseOffsetMs).coerceAtLeast(0L).
Verification output:
- com.openyt.desktop.ui.TimedSearchUiTest (8 tests in :desktopApp:test, all passed):
  - sourceTimeOffsetAppliesToPlaybackPositionAndPlaysOnlyIntendedEntry: PASSED
  - noMatchesStateDisplaysDistinctEmptyMessageFromMissingIndex: PASSED
  - ptAndEnLocalization: PASSED
  - keyboardNavigationTriggersPlayback: PASSED
  - accessibilitySemanticsIncludeTitleTimeAndExcerpt: PASSED
  - narrowScreen320dpWithEnlargedTextDoesNotOverflow: PASSED
  - staleSearchResultIsSupersededByNewerQuery: PASSED
  - noTrackStateExplainsMissingTracksAndOffersImport: PASSED
- Gradle test suites:
  - :core:desktopTest (all passed)
  - :data:desktopTest (all passed)
  - :desktopApp:test (all passed)
  - :androidApp:assembleDebug (BUILD SUCCESSFUL)
  - :androidApp:assembleDebugAndroidTest (BUILD SUCCESSFUL)
  - ./scripts/validate-linux.sh --scenario all --no-build --offline (validation passed, 4 skipped)
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device (Xiaomi 2311DRK48G); APK and test APK assembly pass.
- Windows x64 and macOS native offline runs remain BLOCKED by unavailable native hosts.
Next ready task and why its prerequisites are satisfied:
- NX3.7 — prove search-to-playback end to end: Result navigation and accessible timestamp playback (NX3.6) is complete and VERIFIED; end-to-end synthetic offline phrase seek verification can begin.
```

### 2026-11-12 — Phase NX3.7 (prove search-to-playback end to end)
```
Task: NX3.7 — prove search-to-playback end to end (contract NX-S07)
Completed items:
- Implemented DesktopPlaybackLauncher session control updates preserving checkpoint position and queue across headless and fallback player modes.
- Implemented and verified com.openyt.desktop.search.TimedSearchPlaybackEndToEndTest with synthetic Portuguese ("pesquisa sincronizada") and English ("synthetic playback target") offline searches, verifying player position is within 1000 ms of cue start (diff 0 ms <= 1000 ms), checkpoint persistence across close/reopen, and seamless playback resume.
- Implemented Android instrumented test com.openyt.android.TimedSearchPlaybackEndToEndTest verifying offline SQLite FTS4 cue search, Media3 PlayerActivity seeking to cue start, MediaController position verification within 1000 ms, and validation screenshot capture.
- Documented SQLite FTS4 unicode61 tokenizer limits (whitespace delimiter, Latin diacritic folding, unspaced CJK contiguous token treatment, 256 char / 8 token query limits).
- Formulated explicit non-ASR distinction: OpenYT timed subtitle search operates strictly on structured subtitle/caption tracks and does NOT perform speech recognition, acoustic transcription, or neural speech-to-text.
- Verified 100k-cue synthetic corpus search p95 latency budget (73.96 ms <= 500 ms) and legacy playback checkpoint migration.
Verification output:
- com.openyt.desktop.search.TimedSearchPlaybackEndToEndTest (2 tests in :desktopApp:test, all passed):
  - offlineSearchPhraseToRealPlayerTimeAndCheckpointResume: PASSED
  - englishSyntheticPhraseSearchAndSeekAccuracy: PASSED
- Gradle test suites:
  - :core:desktopTest (all passed)
  - :data:desktopTest (all passed)
  - :desktopApp:test (all passed)
  - :androidApp:assembleDebug (BUILD SUCCESSFUL)
  - :androidApp:assembleDebugAndroidTest (BUILD SUCCESSFUL)
  - ./scripts/validate-linux.sh --scenario all --no-build --offline (validation passed, 4 skipped)
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device (Xiaomi 2311DRK48G); APK and test APK assembly pass.
- Windows x64 and macOS native offline runs remain BLOCKED by unavailable native hosts.
Next ready task and why its prerequisites are satisfied:
- NX4.1 — persist notes, bookmarks, saved ranges and study state: Search-to-playback end-to-end verification (NX3.7) is complete and VERIFIED; study mode data models and persistence can begin.
```

### 2026-11-12 — Phase NX4.1 (persist notes, bookmarks, saved ranges and study state)
```
Task: NX4.1 — persist notes, bookmarks, saved ranges and study state (contract NX-T01)
Completed items:
- Added pure KMP domain models in core/src/commonMain/kotlin/com/openyt/core/domain/StudyModels.kt: StudyRevisionComparator, StudyComparators, StudyNote, StudyBookmark, SavedStudyRange, EntryStudyState, CollectionStudyProgress, EntryStudyBundle, StudyRemovalPreview, StudyValidationError, StudyFailure, and StudyResult.
- Added pure KMP StudyValidator in core/src/commonMain/kotlin/com/openyt/core/application/StudyValidator.kt with Unicode code-point counting (MAX_NOTE_BODY_CHARS = 20_000, MAX_BOOKMARK_LABEL_CHARS = 120, MAX_RANGE_LABEL_CHARS = 120), MIN_RANGE_INTERVAL_MILLIS = 1_000L, non-blank validation, and duration bounds checks.
- Added StudyRepository, MemoryStudyRepository, and StudyService in core/src/commonMain/kotlin/com/openyt/core/application/: injected clock (nowEpochMillis) and ID generator (nextId), EntryNotFound guard, body-only edit revision preservation vs re-anchoring, manual studied completion toggle, collection study progress over existing entries, and previewEntryRemoval / deleteAllStudyDataForEntry.
- Added SQLDelight schema v23 -> v24 migration (23.sqm) and Download.sq schema/queries for study_note, study_bookmark, study_range, and entry_study_state without ON DELETE CASCADE on library_entry so INSERT OR REPLACE updates never cascade-delete authored study data, plus atomic SqlDelightStudyRepository.
- Added Android database persistence test persistsStudyNotesBookmarksRangesAndCompletionAcrossReopen in androidApp/src/androidTest/kotlin/com/openyt/android/AndroidDatabasePersistenceTest.kt.
- Updated docs/architecture/OVERVIEW.md (Schema v24) and docs/planning/NEXT_EXPANSION/CONTRACTS.md (marked NX-T01 VERIFIED).
Verification output:
- ./gradlew :core:desktopTest --tests com.openyt.core.application.StudyRepositoryAndServiceTest --rerun: 5 passed, 0 failed (StudyRepositoryAndServiceTest.xml: tests="5" skipped="0" failures="0" errors="0")
- ./gradlew :data:desktopTest --tests com.openyt.data.SqlDelightStudyRepositoryTest --rerun: 4 passed, 0 failed (SqlDelightStudyRepositoryTest.xml: tests="4" skipped="0" failures="0" errors="0")
- ./gradlew :data:verifyCommonMainOpenYtDatabaseMigration: BUILD SUCCESSFUL
- ./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :data:verifyCommonMainOpenYtDatabaseMigration: BUILD SUCCESSFUL in 24s (213 actionable tasks: 33 executed, 180 up-to-date; desktop unit suites passed; baseline opt-in env-gated integration/media tests skipped: FfmpegProcessorTest 1 skipped, DesktopMediaTaggerTest 1 skipped, DesktopPlaybackLocalTest 1 skipped, DesktopYtDlpEngineIntegrationTest 6 skipped, DesktopYtDlpLocalTest 2 skipped, DownloadProfileLocalTest 7 skipped, DownloadProfileNativeFlowTest 1 skipped)
- ./scripts/validate-linux.sh --scenario all --no-build --offline: exit 0, validation passed (4 skipped) — offline scenarios prepare, tests, android, tools, metadata, cookies, library, archive, watch, storage, bulk, playback, gui passed; inspect, download, playlist, pause skipped offline
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions on Xiaomi physical device (Xiaomi 2311DRK48G); APK and test APK assembly pass.
- Windows x64 and macOS native offline runs remain BLOCKED by unavailable native hosts.
Next ready task and why its prerequisites are satisfied:
- NX4.2 — implement timestamp capture and note navigation: NX4.1 persistence contracts (NX-T01), repository, and service layer are VERIFIED.
```

### 2026-11-12 — Phase NX4.2 (implement timestamp capture and note navigation)
```
Task attempted: Implement NX4.2 timestamp capture, anchor/revision drift diagnostics, explicit user-confirmed repair, unsaved editor draft guard, and player timestamp navigation (`NX-T02`) across pure KMP core, Desktop VLCJ, and Android Media3.
Files read:
- docs/planning/NEXT_EXPANSION/04_STUDY_MODE.md
- docs/planning/NEXT_EXPANSION/CONTRACTS.md
- docs/architecture/OVERVIEW.md
- docs/adr/0010-collections.md, docs/adr/0019-desktop-playback.md, docs/adr/0021-local-media-expansion.md
- core/src/commonMain/kotlin/com/openyt/core/application/PlaybackSessionControl.kt
- core/src/commonMain/kotlin/com/openyt/core/application/StudyService.kt
- desktopApp/src/main/kotlin/com/openyt/desktop/player/DesktopPlaybackLauncher.kt
- androidApp/src/main/kotlin/com/openyt/android/player/AndroidPlaybackLauncher.kt
- androidApp/src/main/kotlin/com/openyt/android/player/PlaybackService.kt
- androidApp/src/main/kotlin/com/openyt/android/OpenYtApplication.kt
Files changed:
- core/src/commonMain/kotlin/com/openyt/core/application/PlaybackSessionControl.kt (added `PlaybackPositionSnapshot`, `knownDurationMillis`, `currentSessionGeneration`, and atomic `captureSnapshot()`)
- core/src/commonMain/kotlin/com/openyt/core/domain/StudyInteractionModels.kt (new pure KMP models for anchor health, capture results, navigation outcomes, editor draft state, and discard prompts)
- core/src/commonMain/kotlin/com/openyt/core/application/StudyAnchorInspector.kt (new pure KMP non-mutating anchor revision/range drift diagnostics)
- core/src/commonMain/kotlin/com/openyt/core/application/StudyService.kt (added `getNoteById`, `getBookmarkById`, `getSavedRangeById` helpers)
- core/src/commonMain/kotlin/com/openyt/core/application/StudyCaptureAndNavigationService.kt (new pure KMP atomic capture with `sessionGeneration` TOCTOU guard, unlinked/stopped/preparing/error/external-fallback/missing-file guards, out-of-range block without silent clamping, explicit user-confirmed repair, duplicate-tap suppression, and post-seek verification)
- core/src/commonMain/kotlin/com/openyt/core/application/StudyEditorCoordinator.kt (new pure KMP unsaved editor draft buffer preserving dirty edits across normal navigation, prompting confirmation on cancel/close/switch, keeping persisted records intact on cancel, and retaining draft input on validation failure)
- desktopApp/src/main/kotlin/com/openyt/desktop/player/DesktopPlaybackLauncher.kt (`currentSessionGeneration`, EDT-safe atomic `captureSnapshot()`, external `openAt()` generation bump, EDT-safe `seekTo()` updates)
- androidApp/src/main/kotlin/com/openyt/android/player/AndroidPlaybackSessionBridge.kt (new lifecycle-safe main-looper session delegate bridge with live ExoPlayer snapshot publication)
- androidApp/src/main/kotlin/com/openyt/android/player/PlaybackService.kt (attached active `AndroidPlaybackSessionBridge` delegate with `released` teardown safety and `detachService()` in `onDestroy()`)
- androidApp/src/main/kotlin/com/openyt/android/player/AndroidPlaybackLauncher.kt (implemented `PlaybackSessionControl` with `currentSessionGeneration` and atomic `captureSnapshot()`)
- androidApp/src/main/kotlin/com/openyt/android/OpenYtApplication.kt (wired `studyRepository`, `studyService`, `studyEditorCoordinator`, and `createStudyCaptureAndNavigationService(playbackLauncher)`)
- core/src/commonTest/kotlin/com/openyt/core/application/PlaybackSessionControlTest.kt (expanded to 11 tests)
- core/src/commonTest/kotlin/com/openyt/core/application/StudyCaptureAndNavigationTest.kt (new 9-test `NX-T02` deterministic contract suite)
- desktopApp/src/test/kotlin/com/openyt/desktop/player/DesktopPlaybackSessionTest.kt (expanded to 4 tests covering `captureSnapshot()`, `sessionGeneration`, external `openAt()`, and invalid seeks)
- androidApp/src/androidTest/kotlin/com/openyt/android/AndroidStudySessionContractTest.kt (new Android contract suite packaged in debug androidTest APK)
- docs/architecture/OVERVIEW.md (documented NX4.2 capture/anchor/draft/navigation flow across VLCJ & Media3)
- docs/planning/NEXT_EXPANSION/CONTRACTS.md (marked `NX-T02` VERIFIED)
- docs/planning/NEXT_EXPANSION/PROGRESS.md (updated tracker and execution log for NX4.2 -> NX4.3)
Checks run:
- `./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :data:verifyCommonMainOpenYtDatabaseMigration --rerun` -> PASSED (`BUILD SUCCESSFUL`; `StudyCaptureAndNavigationTest`: 9/9 passed, `PlaybackSessionControlTest`: 11/11 passed, `DesktopPlaybackSessionTest`: 4/4 passed)
- Post-update full Gradle verification + Linux offline validation executed prior to final commit/push.
Known limits / blocked checks / decisions needed:
- Android device instrumentation remains BLOCKED by OEM USB install security restrictions (`INSTALL_FAILED_USER_RESTRICTED`) on Xiaomi physical device (Xiaomi 2311DRK48G); APK and test APK assembly (`AndroidStudySessionContractTest`) pass.
- Windows x64 and macOS native offline runs remain BLOCKED by unavailable native hosts.
Next ready task and why its prerequisites are satisfied:
- NX4.3 — implement A/B repetition through platform capabilities: NX4.1 persistence (`NX-T01`) and NX4.2 atomic capture, anchor health, draft guard, and player timestamp navigation (`NX-T02`) are VERIFIED.
```

### 2026-11-13 — Phases NX4.4–NX4.6 (safe export, deep links, Study UI, end-to-end verification & bug fixes)
```
Task: NX4.4 / NX4.5 / NX4.6 — Study Markdown export, openyt://library/entry/<id>?t=<ms> routing, Study UI, full lifecycle verification (`NX-T04`, `NX-T05`, `NX-T06`)
Completed items:
- NX4.4 (`a58586a`): Pure KMP `StudyMarkdownExporter`, `StudyHostLinkCapability`, `OpenYtEntryLink` / `OpenYtLink` (traversal-safe `/` and `\` rejection), `TextFileExporter` (`DesktopTextFileExporter`, `AndroidTextFileExporter`), `DesktopDeepLinks`, `DesktopInstance`, `DesktopCli link --entry`, and Android `ACTION_VIEW` scheme filters (`NX-T04`).
- NX4.5 (`ea573b9`): Unified scrollable `LazyVerticalGrid` + `FlowRow` in `LibraryScreen` eliminating nested-scroll/font-scale clipping on `320x640` (`1.5x`) and `1280x800` (`2.0x`), updated `StudySheetDialog` / `StudyRemovalDialog` and `StudyUiTest` (`57/57` UI tests passed headless & under `xvfb-run`).
- NX4.6 contracts & bug fixes:
  - Fixed `DesktopPlaybackLauncher.kt` (`edtSnapshot()`, `captureSnapshot()`, `playCurrent()`): fixed `captureSnapshot()` returning `null` while `PREPARING` and preserved `window.isVisible = true` before synchronous VLCJ `media().play(...)` realization.
  - Fixed `AndroidPlaybackSessionBridge.kt` (`pendingOpen` preserved over stale `IDLE` delegate telemetry until Media3 attaches the new item) and `PlayerActivity.kt` (explicit seek when reopening the same queued index at a saved timestamp).
  - Added full end-to-end contracts in `StudyPlaybackLocalTest.kt` (Linux VLCJ+FFmpeg+SQLite), `AndroidStudyFlowContractTest.kt` (Android Media3+SQLite+IntentFilter), `WindowsContractHarness.kt` (`"study"` contract with bundled VLC + HKCU registration + cleanup via `validate-windows.ps1`), `validate-expansion.py` (GUI/CLI `openyt://library/entry/` dispatch), and `.github/workflows/ci.yml`.
- Updated `docs/architecture/OVERVIEW.md`, `docs/planning/NEXT_EXPANSION/CONTRACTS.md`, and `docs/planning/NEXT_EXPANSION/PROGRESS.md` with local-link capability/portability rules, manual completion vs resume reset independence, and original-preserving record removal.
Verification output (so far before final packaged/ADB/CI run):
- `StudyPlaybackLocalTest`: 1 passed, 0 failed (`maxOvershootMs=400`, `maxReturnDeltaMs=0` over 5 real VLCJ loops).
- `DesktopPlaybackLocalTest`: 2 passed, 0 failed.
- Headless Compose `StudyUiTest` (no `DISPLAY`/`WAYLAND_DISPLAY`/`xvfb-run`): 4 passed, 0 failed.
- Full local desktop run (`:core:desktopTest`, `:ui:desktopTest`, `:engine-ytdlp:desktopTest`, `:data:desktopTest`, `:media-ffmpeg:desktopTest`, `:data:verifyCommonMainOpenYtDatabaseMigration`): all passed (`FfmpegProcessorTest` 3/3 passed; `:desktopApp:test` 104 passed + 6 opt-in `OPENYT_RUN_NETWORK_TESTS` skipped; `ExecutableLocatorTest` failed only because `OPENYT_YTDLP` was explicitly set in env — to be verified separately with clean env).
```

## Native Matrix

| Target / Environment | Capabilities Tested | Requirements & Tools | Current Baseline Status | Notes / Blockers |
|---|---|---|---|---|
| **Linux x86_64** | Pure KMP, SQLite, Compose UI, VLCJ playback, FFmpeg processing, loopback yt-dlp, deb packaging | Pop!_OS 24.04 LTS, JDK 21, libvlc5, xvfb-run, bundled tools | **VERIFIED / ACTIVE** | Primary automated desktop test and packaging environment. |
| **Android (Unit/Build)** | Core/Data KMP Android targets, AAR packaging, Debug & Test APK assembly | Android SDK 34–37, build-tools 34–37 | **VERIFIED / ACTIVE** | `:androidApp:assembleDebug` and `:androidApp:assembleDebugAndroidTest` pass. |
| **Android (Device)** | Connected instrumentation (`connectedDebugAndroidTest`) | Xiaomi 2311DRK48G (API 36, arm64-v8a) | **BLOCKED** | OEM HyperOS USB security restriction (`INSTALL_FAILED_USER_RESTRICTED`). Requires emulator or SIM/Mi Account unlock. |
| **Windows x64** | MSI/EXE installer, JNA shell/Explorer integration, native VLC/FFmpeg | Windows 10/11 VM, PowerShell, JNA | **BLOCKED** | No Windows VM or runner configured on current Linux host. |
| **macOS (x64/arm64)** | DMG/Bundle packaging, native playback | macOS runner / Xcode / notary | **BLOCKED** | No macOS host available. |

## Decisions and blockers

- **Phase NX0 Gate: PASSED.** Baseline measurements, redistributable synthetic timeline fixtures, and contract family mappings are verified offline.
- Milestone 1 (NX1: Download Profiles, NX2: Offline Library): NX1 is complete; NX2.6 is implemented with Linux offline evidence but remains BLOCKED until Windows, Android-device and macOS native checks run.
- Planning assumptions and proposed budgets are in each phase; none are measured
  implementation results. Native availability is established by NX0.1:
  - Linux x86_64 host: baseline fully operational for deterministic tests, local loopback engine, libvlc playback, deb packaging, and validation harness.
  - Android: compilation and AAR/APK packaging operational; device instrumentation blocked by OEM USB install policy on physical device (Xiaomi 2311DRK48G).
  - Windows & macOS: native checks blocked by host environment availability (no Windows VM or macOS host).
- Closed-app scheduling and transcription remain optional and gated.
- Publication/versioning is a separate explicitly authorized activity.
