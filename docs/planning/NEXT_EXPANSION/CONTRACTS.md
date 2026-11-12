# Verification contracts for the next expansion

Status: **specification, not an executed test report**. IDs below name behavior
that future tests must establish. The phase documents supply exact edge cases.
Do not mark a contract passed until an executable assertion and evidence exist.

## Test layers

1. **Deterministic:** pure Kotlin rules/parsers with injected clock/state;
   SQLDelight migration/reopen tests; shared Compose behavior using fake ports.
   No internet, external credentials, media player or user filesystem dependency.
2. **Local integration/native:** real SQLite drivers, temporary files, loopback
   fixtures, external bundled tools, actual player state, OS pickers/SAF and
   installer/file-action checks. Explicit opt-in where tools/displays are needed.
3. **Internet smoke:** separately enabled, permitted source URLs; source availability
   is recorded separately from deterministic regressions. Never make unit tests
   depend on a live provider or turn an HTTP failure into a fake pass.

Native execution proves platform integration; pure rules prove deterministic
decisions. A mocked seek is not real playback evidence. A screenshot alone is not
proof of a correct backup hash, migration or crash recovery.

## Common invariants: apply to every affected phase

- **NX-C01 — layering/dependencies:** core stays platform-free; hosts/adapters own
  process/filesystem/player APIs; UI uses ports. New dependencies have a recorded
  need, pinned version, license/security review and native distribution assessment.
- **NX-C02 — source preservation:** original media hashes remain unchanged through
  export, clip, cancel, retry, failed restore, relink and record removal. Cleanup
  is limited to explicitly owned staging/assets; no path-based ownership guessing.
- **NX-C03 — persistence:** upgrade from the preceding released schema and the
  supported legacy baseline fixtures, reopen and compare meaningful fields. Old
  request/queue/profile/subscription encodings decode with documented defaults.
  Include corrupt/unknown-version data and interrupted operations.
- **NX-C04 — file/DB boundary:** inject failure before/after publication and commit;
  reconciliation is idempotent, no success with absent bytes, no duplicate retry,
  no unrelated deletion. Partial work is shown as interrupted/failed, not success.
- **NX-C05 — error/privacy:** disk-full, permission loss, missing file, unsupported
  capability, malformed input and offline paths have structured localized recovery
  actions. Diagnostics/export omit cookies, tokens, raw tracebacks and unnecessary
  private paths/text. Cancellation releases processes/streams/native handles.
- **NX-C06 — usability/i18n:** pt-BR/en, narrow 320dp phone and desktop, enlarged
  text (200%), accessible labels/focus, keyboard/touch and empty/loading/error/
  retry states. OS-language detection and explicit override remain correct.
- **NX-C07 — truthful capabilities:** unavailable index/player/encoder/constraint
  is explicit, no no-op method advertised as working, no unindexed content treated
  as fully searched, no external-player seek or background guarantee invented.
- **NX-C08 — baseline regressions:** downloads/confirmed outputs, pause/retry,
  queue restart, JSON-record import/export, collection membership, SAF local-copy
  authority, record-only removal, PiP/mini-player and native open/reveal remain
  working. Rerun the relevant existing contracts when those paths change.

### Baseline test task mapping to common invariants

| Contract Family | Baseline Gradle Tasks & Test Classes | Status |
|---|---|---|
| **NX-C01 (Layering/Deps)** | `:core:desktopTest`, `:engine-ytdlp:desktopTest`, `:data:desktopTest`, `:media-ffmpeg:desktopTest`, `:desktopApp:test`, `:androidApp:assembleDebug` | **VERIFIED (Linux/Android)** |
| **NX-C02 (Preservation)** | `FfmpegProcessorTest` (trim args, real trim/convert), `DesktopMediaTaggerTest`, `FileBulkFileOperatorTest` | **VERIFIED (Linux)** |
| **NX-C03 (Persistence)** | `DatabaseMigrationTest` (schema v6->v13, backfill), `SqlDelightDownloadRepositoryTest`, `SettingsRepositoryTest`, `PlaybackStoreTest` | **VERIFIED (Linux)** |
| **NX-C04 (File/DB Boundary)** | `ConfirmedEngineOutputTest`, `FileFolderScannerTest`, `FileBulkFileOperatorTest` | **VERIFIED (Linux)** |
| **NX-C05 (Error/Privacy)** | `FfmpegProcessorTest` (dependency error), `EncryptedCookieRepositoryTest`, `UrlValidatorTest`, `DiagnosticsTest` | **VERIFIED (Linux)** |
| **NX-C06 (Usability/i18n)** | `SystemLanguageTest`, `StorageUiTest`, `FileActionsUiTest`, `ProviderIconsUiTest`, `DownloadsUiTest`, `HomePlaylistUiTest` | **VERIFIED (Linux)** |
| **NX-C07 (Truthful Caps)** | `ExecutableLocatorTest`, `BundledToolsTest`, `FfprobeJsonParserTest`, `DesktopMediaProbeTest`, `DesktopInstanceTest` | **VERIFIED (Linux)** |
| **NX-C08 (Regressions)** | `DownloadCoordinatorTest` (34 tests), `LibraryControllerTest`, `LibraryArchiveServiceTest`, `DesktopPlaybackLocalTest`, `DesktopMediaActionsTest`, `ExternalPlaybackTest`, `DesktopYtDlpLocalTest` | **VERIFIED (Linux)** |
| **NX-F01 (Synthetic Fixtures)** | `SyntheticTimelineFixturesTest` (7 tests: pt/en cues, accents, overlapping intervals, deduplication, distinct equal-size files, Unicode filenames, inert security fixtures) | **VERIFIED (Pure KMP)** |

Use the existing [video regressions](../../testing/VIDEO_REGRESSION_CONTRACTS.md),
[expansion contracts](../../testing/EXPANSION_CONTRACTS.md) and
[Windows contracts](../../testing/WINDOWS_DESKTOP_CONTRACTS.md) as regression
anchors, not as proof that the new features are already tested.

## Feature contract register

Future test names below are suggestions. Place pure tests in `core/src/commonTest`,
repository/migration tests in `data/src/desktopTest`, shared Compose tests in
`desktopApp/src/test`, native local tests in the appropriate host test roots, and
Android device tests in `androidApp/src/androidTest`. Update PROGRESS.md with the
actual test method/path implementing each contract; do not just quote an ID.

### Profiles — NX-P

Suggested groups: `DownloadProfileResolverTest`, `DownloadProfileRepositoryTest`,
`DownloadProfileUiTest`, host `DownloadProfileLocalTest`.

- **NX-P01:** built-in rules, compatible formats, explicit fallback and subtitles.
  - Implemented by `com.openyt.core.domain.DownloadProfileResolverTest` (13 tests in `:core:desktopTest` and `:core:testDebugUnitTest`).
  - Status: **VERIFIED**.
- **NX-P02:** custom profile persistence/migration and separate local bindings.
  - Implemented by `com.openyt.data.DownloadProfileRepositoryTest` (8 tests in `:data:desktopTest`).
  - Covers: schema v18->v19 upgrade and settings/task preservation, seeding built-in profiles and immutability, reopening database with custom profiles and bindings, duplicate names/IDs rejection, graceful handling of corrupted definitions, missing destination permission detection, independent task queue lifecycle upon profile deletion, and portable definition export excluding local bindings and secrets.
  - Status: **VERIFIED**.
- **NX-P03:** immutable request/SAF snapshot through editing, retry and restart.
  - Implemented by `com.openyt.core.application.DownloadProfileSnapshotTest` (11 tests in `:core:desktopTest`).
  - Covers: profile snapshotting into requests, editing/deleting profile or mutating global destination after enqueue leaves queued request immutable, per-task SAF export target isolation, restart preserves snapshot and profile provenance, legacy task compatibility, strict rejection of SAF URIs as filesystem paths, retry preserving saved choices, revoked SAF grant producing copy-export warning without failing local download, format expiration revalidation requiring user approval, and absence of credentials in snapshots or diagnostics.
  - Status: **VERIFIED**.
- **NX-P04:** real output matches resolved container/streams/track choices.
  - Implemented by `com.openyt.desktop.downloader.DownloadProfileLocalTest` (7 tests in `:desktopApp:test` with real bundled tools and loopback fixture), `com.openyt.engine.ytdlp.YtDlpDownloadOptionsTest` (7 unit tests in `:engine-ytdlp:desktopTest`), `com.openyt.data.ConfirmedEngineOutputTest` (2 tests in `:data:desktopTest`), and `com.openyt.desktop.downloader.DesktopYtDlpEngineTest` (output container matching in `:desktopApp:test`).
  - Covers: real local-tool downloads with each compatible profile (Study, Audio only, Save space, Watch on TV); actual stream and container probe confirming codecs and height ceilings; absent subtitle handling (video download remains uncorrupted, subtitle-only download fails with structured `OutputMissing` rather than inferring success from exit code 0); source format disappearance mapping to structured `ProcessingFailed("requested format is not available")` without raw tracebacks; safe arguments passing for Unicode, spaces and punctuation; output extension confirmation matching the approved plan; and preservation of existing manual format selection.
  - Status: **VERIFIED**.
- **NX-P05:** accessible preview/editor and explicit download action.
- **NX-P06:** native combined flow and existing manual format selection survive.

### Offline assets — NX-O

Suggested groups: `OfflineReadinessTest`, `AssetRepositoryTest`,
`AssetPublicationTest`, `OfflineLibraryUiTest`, host `OfflineLibraryLocalTest`.

- **NX-O01:** revision/required-asset readiness semantics and schema upgrade.
- **NX-O02:** confirmed bounded artifact receipts, no unrelated file attachment.
- **NX-O03:** staging/journal/cancel/crash/disk-full, source preservation.
- **NX-O04:** bounded artwork fetch/subtitle import and verified local preference.
- **NX-O05:** accurate storage categories, pinning and explicit cleanup UI.
- **NX-O06:** actual offline reopen after networking/cache removal on native hosts.

### Timed search — NX-S

Suggested groups: `SubtitleParserTest`, `TimedSearchRepositoryTest`,
`EntryPlaybackIdentityTest`, `TimedSearchUiTest`, host `TimedSeekLocalTest`.

- **NX-S01:** shipped FTS4/unicode61 viability and measured 100k-cue query budget.
- **NX-S02:** bounded SRT/VTT parse and explicit local/source timebase mapping.
- **NX-S03:** atomic index revisions, invalidation and crash/restart behavior.
- **NX-S04:** literal bounded query/pagination/cancellation and scoped results.
- **NX-S05:** actual prepared-player seek, entry identity and legacy queue bridge.
- **NX-S06:** missing-index vs no-match UI and correct accessible timestamp action.
- **NX-S07:** offline search phrase to real player time and native close/reopen.

### Study — NX-T

Suggested groups: `StudyRepositoryTest`, `StudySessionTest`, `StudyLinkTest`,
`StudyUiTest`, host `StudyPlaybackLocalTest`.

- **NX-T01:** notes/ranges/manual completion survive restart/path changes.
- **NX-T02:** timestamp capture cannot race into the wrong entry; stale anchors.
- **NX-T03:** five real A/B loops within stated tolerance and clean stop/switch.
- **NX-T04:** escaped Markdown, typed local links, OS dispatch and privacy.
- **NX-T05:** accessible edit/export/progress UI and authored-data removal preview.
- **NX-T06:** full offline study flow, PiP/mini-player and actual exported links.

### Backup — NX-B

Suggested groups: `PortableManifestTest`, `PortableArchiveSecurityTest`,
`PortableRestoreJournalTest`, `PortableBackupUiTest`, host `PortableRoundTripTest`.

- **NX-B01:** independent manifest version, consistent metadata and safe fields.
- **NX-B02:** measured streaming/ZIP64 support including >4 GiB native fixture.
- **NX-B03:** complete export receipt, cancellation and source-change detection.
- **NX-B04:** hostile archive bounds, traversal/collision/symlink/digest rejection.
- **NX-B05:** journaled restore, complete ID remapping and no existing changes.
- **NX-B06:** desktop–Android–desktop round-trip with media hashes and context.

### Recovery — NX-R

Suggested groups: `LibraryHealthTest`, `RelinkPlannerTest`, `RelinkJournalTest`,
`RecoveryUiTest`, host `RelinkLocalTest`.

- **NX-R01:** read-only accurate health/permission/content states.
- **NX-R02:** bounded root scan/hash, verified identity vs heuristics.
- **NX-R03:** exact preview/confirmation, active playback and stale-plan rejection.
- **NX-R04:** same entry/context/checkpoint after relink and interrupted retry.
- **NX-R05:** advisory duplicates and record-only removal with authored context.
- **NX-R06:** native moved-file recovery, reopen and Windows shell/player behavior.

### Subscription rules — NX-A

Suggested groups: `SubscriptionRuleTest`, `SubscriptionCandidateStoreTest`,
`SubscriptionConstraintTest`, `SubscriptionRulesUiTest`, host scheduling tests.

- **NX-A01:** versioned filters/profiles/caps and explicit unknown decisions.
- **NX-A02:** persisted candidate backlog and no surprise legacy catch-up.
- **NX-A03:** real network/power/space capability semantics; no disallowed request.
- **NX-A04:** enqueue-before-seen and exactly one task per stable originKey.
- **NX-A05:** preview/explanations and foreground scheduling scope are clear.
- **NX-A06:** real app-alive constraints/restart/backlog behavior on native hosts.
- **NX-A07 (optional):** background feasibility/ownership/quota prototype evidence.
- **NX-A08 (optional):** opted-in bounded Android work and actual device behavior.
- **NX-A09 (optional):** opted-in OS scheduling and single-owner CLI per desktop OS.

### Batch clips — NX-CL

Suggested groups: `ClipRecipeTest`, `ClipBatchJournalTest`, `ClipEditorUiTest`,
host `ClipRenderingLocalTest`. `NX-CL` is distinct from the common invariants
`NX-C01`–`NX-C08`.

- **NX-CL01:** actual packaged encoder/filter/renderer capability proof.
- **NX-CL02:** validated typed recipes, safe args, modes and unique output paths.
- **NX-CL03:** per-clip receipts, partial success, cancel/crash and original hashes.
- **NX-CL04:** actual dimensions/codecs/rotation/captions/cover/timebase rendering.
- **NX-CL05:** matching preview/export recipe and understandable batch results.
- **NX-CL06:** real three-range output playback/probes and partial failure evidence.

### Transcription — NX-TX (optional)

Suggested groups: `TranscriptionModelTest`, `TranscriptionJobTest`,
`TranscriptionUiTest`, host `TranscriptionBenchmarkTest`.

- **NX-TX01:** engine/binding/model legal/security/platform feasibility record.
- **NX-TX02:** measured runtime/RAM/quality/cancel budgets on fixed references.
- **NX-TX03:** consent, hash-verified model lifecycle and no premature network use.
- **NX-TX04:** bounded cancellable real inference feeding existing cue revisions.
- **NX-TX05:** machine-generated/corrected provenance and accessible controls.
- **NX-TX06:** native offline speech-to-search-to-playback plus repeated budgets.

## Commands verified as existing entry points at planning time

Run commands from the repository root on the matching host. These commands are
instructions for future execution; **they were not run to implement this plan**.

Small deterministic checks (filter further to the task's actual tests first):

```bash
./gradlew :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test --console=plain
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --console=plain
```

`DatabaseMigrationTest` belongs to `:data:desktopTest`. Shared Compose tests
currently belong to `:desktopApp:test`; do not invent a `:ui:test` target.
Read JUnit reports: UP-TO-DATE or skipped environment-guarded tests are not new
native execution evidence. For guarded tests, provide required environment and
rerun when needed (`--rerun-tasks`) because environment changes may not be inputs.

Prepared Linux local playback (requires real libvlc, FFmpeg, Xvfb and JDK):

```bash
env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE OPENYT_RUN_PLAYBACK_TESTS=1 OPENYT_FFMPEG="$PWD/desktopApp/build/bundled-tools/linux-x64/ffmpeg" xvfb-run -a ./gradlew :desktopApp:test --tests '*DesktopPlaybackLocalTest' --rerun-tasks --console=plain
```

This current test proves the current player baseline, not NX3/NX4 seek/repeat
behavior. Add those actual assertions in the appropriate future local tests.

Packaged Linux baseline (prepare tools/dependencies first):

```bash
./gradlew :desktopApp:packageDeb --console=plain
./scripts/validate-linux.sh --scenario all --no-build --offline
```

The harness reports network scenarios SKIP under `--offline`; record those skips.
Extend the existing harness with focused future scenarios after the feature exists
and document their flags. Don't call a nonexistent scenario from this plan.

Android instrumentation on a connected configured emulator/device:

```bash
./gradlew :androidApp:connectedDebugAndroidTest -Popenyt.testAbi=x86_64 -Popenyt.testApplicationIdSuffix=.regression --console=plain
```

The ABI above is for the x86_64 emulator. Use the actual physical-device ABI
instead and inspect the configured test-app variant before installing. API 26
and API 36 cover baseline/target behavior; physical-device checks are required
for memory/battery/thermal and optional background claims.

Windows native interactive VM (PowerShell in a disposable test checkout):

```powershell
.\gradlew.bat :desktopApp:packageMsi :desktopApp:packageExe :desktopApp:windowsContractHarnessJar --console=plain
.\scripts\validate-windows.ps1
```

This script installs/tests native packages and uses Explorer/associations. Review
its current parameters and pass explicit `-Msi`, `-Exe` and `-Harness` paths when
multiple builds exist. Use the prepared test VM, not the user's primary install.
Supplement with real app actions; do not substitute Wine for Windows evidence.

For macOS, use the current native CI/package harness and actual VLC integration;
first confirm Gradle packaging targets with `./gradlew :desktopApp:tasks --all`.
Don't claim notarization or extra signatures unavailable to the current project.

## Phase gates and final publication

- Every implementation task runs its focused tests and affected common invariants.
- Every phase gate runs its whole contract family and affected baseline regressions,
  then records native OS/device/tool evidence. Internet failures stay separate.
- Every milestone runs one combined user journey spanning its phases, including
  restart, offline use, cancel/error recovery and preservation of existing media.
- Before any separately authorized release, run existing CI/native package checks,
  migration/upgrade and Windows installer/player contracts plus the new affected
  contracts. Use the existing signed-manifest/release verification process.
  Do not edit tests to pass an unsupported feature, publish stubs or claim all
  possible bugs are prevented. Record residual limits and unavailable targets.
