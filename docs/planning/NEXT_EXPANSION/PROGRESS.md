# Execution tracker

Updated: 2026-11-12. **NX1 VERIFIED (NX1.1–NX1.6 complete); NX2 ready to start.**

Baseline: OpenYT 4.0.1; checkout `codex/nx1-6-milestone-evidence`.
Actual baseline inspected, validated, profile resolution models implemented, profile persistence with schema migration verified, immutable profile request snapshots implemented, host/engine adapters connected with real output container/stream probing, profile selection/preview/editing UI verified, and combined native flow & manual format coexistence verified with milestone documentation and real Compose UI evidence.

Current task: NX1.6 completed. First ready task: **NX2.1**.
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
- Milestone 1 (NX1: Download Profiles, NX2: Offline Library) is ready to begin with task **NX1.1**.
- Planning assumptions and proposed budgets are in each phase; none are measured
  implementation results. Native availability is established by NX0.1:
  - Linux x86_64 host: baseline fully operational for deterministic tests, local loopback engine, libvlc playback, deb packaging, and validation harness.
  - Android: compilation and AAR/APK packaging operational; device instrumentation blocked by OEM USB install policy on physical device (Xiaomi 2311DRK48G).
  - Windows & macOS: native checks blocked by host environment availability (no Windows VM or macOS host).
- Closed-app scheduling and transcription remain optional and gated.
- Publication/versioning is a separate explicitly authorized activity.
