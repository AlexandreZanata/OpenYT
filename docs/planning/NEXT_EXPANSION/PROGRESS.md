# Execution tracker

Updated: 2026-11-12. **NX0.1 VERIFIED on Linux x86_64; remaining tasks PENDING.**

Baseline: OpenYT 4.0.1; checkout `18fa90cd86c9072582c6fbc65329c7a0e6203856` (branch `codex/nx0-1-baseline`).
Actual baseline inspected and executed in NX0.1.

Current task: NX0.1 completed. First ready task: **NX0.2**.
Statuses and handoff rules: [EXECUTOR_PROMPT.md](EXECUTOR_PROMPT.md).
Check a box only after VERIFIED evidence; keep IMPLEMENTED/BLOCKED work unchecked.

The 52 mandatory tasks cover NX0–NX8, excluding NX7.7–NX7.9.
The 9 optional tasks are NX7.7–NX7.9 and NX9.1–NX9.6.
Capability NO_GO outcomes must be recorded explicitly; they do not mean the corresponding optional feature was delivered.

Milestone gates: M1 = NX1.6 + NX2.6; M2 = NX3.7 + NX4.6;
M3 = NX5.6 + NX6.6; M4 = NX7.6 + NX8.6. Each gate includes its prerequisites.

## NX0 — execution foundation

- [x] [NX0.1 — establish the actual baseline](00_FOUNDATION.md#nx0-1) — VERIFIED (Linux x86_64; Android compilation verified; native Android device tests and Windows VM blocked).
- [ ] [NX0.2 — establish small, redistributable fixtures](00_FOUNDATION.md#nx0-2) — PENDING.
- [ ] [NX0.3 — wire the verification and handoff process](00_FOUNDATION.md#nx0-3) — PENDING.

## NX1 — complete download profiles

- [ ] [NX1.1 — model profiles and deterministic resolution](01_DOWNLOAD_PROFILES.md#nx1-1) — PENDING.
- [ ] [NX1.2 — persist custom profiles and local bindings](01_DOWNLOAD_PROFILES.md#nx1-2) — PENDING.
- [ ] [NX1.3 — snapshot profiles into queued requests](01_DOWNLOAD_PROFILES.md#nx1-3) — PENDING.
- [ ] [NX1.4 — connect the host/engine adapters](01_DOWNLOAD_PROFILES.md#nx1-4) — PENDING.
- [ ] [NX1.5 — add profile selection, preview and editing UI](01_DOWNLOAD_PROFILES.md#nx1-5) — PENDING.
- [ ] [NX1.6 — milestone evidence and documentation](01_DOWNLOAD_PROFILES.md#nx1-6) — PENDING.

## NX2 — durable offline library

- [ ] [NX2.1 — model assets, revisions and readiness](02_OFFLINE_LIBRARY.md#nx2-1) — PENDING.
- [ ] [NX2.2 — confirm engine-produced companion artifacts](02_OFFLINE_LIBRARY.md#nx2-2) — PENDING.
- [ ] [NX2.3 — implement owned storage and durable local operations](02_OFFLINE_LIBRARY.md#nx2-3) — PENDING.
- [ ] [NX2.4 — fetch artwork and import subtitle assets safely](02_OFFLINE_LIBRARY.md#nx2-4) — PENDING.
- [ ] [NX2.5 — show offline readiness and storage actions](02_OFFLINE_LIBRARY.md#nx2-5) — PENDING.
- [ ] [NX2.6 — prove offline behavior and close milestone 1](02_OFFLINE_LIBRARY.md#nx2-6) — PENDING.

## NX3 — search within saved media

- [ ] [NX3.1 — prove indexing capability on the shipped databases](03_TIMED_SEARCH.md#nx3-1) — PENDING.
- [ ] [NX3.2 — parse and normalize timed subtitle tracks](03_TIMED_SEARCH.md#nx3-2) — PENDING.
- [ ] [NX3.3 — persist cues and atomically rebuild an index revision](03_TIMED_SEARCH.md#nx3-3) — PENDING.
- [ ] [NX3.4 — expose bounded text search](03_TIMED_SEARCH.md#nx3-4) — PENDING.
- [ ] [NX3.5 — add entry-aware timed playback without losing history](03_TIMED_SEARCH.md#nx3-5) — PENDING.
- [ ] [NX3.6 — deliver result navigation and empty/error states](03_TIMED_SEARCH.md#nx3-6) — PENDING.
- [ ] [NX3.7 — prove search-to-playback end to end](03_TIMED_SEARCH.md#nx3-7) — PENDING.

## NX4 — study mode

- [ ] [NX4.1 — persist notes, bookmarks, saved ranges and study state](04_STUDY_MODE.md#nx4-1) — PENDING.
- [ ] [NX4.2 — implement timestamp capture and note navigation](04_STUDY_MODE.md#nx4-2) — PENDING.
- [ ] [NX4.3 — implement A/B repetition through platform capabilities](04_STUDY_MODE.md#nx4-3) — PENDING.
- [ ] [NX4.4 — add safe note export and local-entry links](04_STUDY_MODE.md#nx4-4) — PENDING.
- [ ] [NX4.5 — deliver a focused study UI](04_STUDY_MODE.md#nx4-5) — PENDING.
- [ ] [NX4.6 — prove the full study flow and close milestone 2](04_STUDY_MODE.md#nx4-6) — PENDING.

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

## Decisions and blockers

- Planning assumptions and proposed budgets are in each phase; none are measured
  implementation results. Native availability is established by NX0.1:
  - Linux x86_64 host: baseline fully operational for deterministic tests, local loopback engine, libvlc playback, deb packaging, and validation harness.
  - Android: compilation and AAR/APK packaging operational; device instrumentation blocked by OEM USB install policy on physical device (Xiaomi 2311DRK48G).
  - Windows & macOS: native checks blocked by host environment availability (no Windows VM or macOS host).
- Closed-app scheduling and transcription remain optional and gated.
- Publication/versioning is a separate explicitly authorized activity.
