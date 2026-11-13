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
  - Implemented by `com.openyt.desktop.ui.DownloadProfileUiTest` (20 parameterized tests in `:desktopApp:test` across 4 screen/density/locale scenarios: `mobile-light-pt`, `mobile-large-dark-pt`, `desktop-dark-pt`, `desktop-light-en`).
  - Covers:
    - Profile selection and plan preview card rendered across narrow (320px/360px) and wide (1024px/1280px) widths with enlarged font scales (1.0f, 1.3f, 1.5f) under Portuguese and English;
    - Selection chips for Study, Audio only, Save space, Watch on TV, custom profiles, and manual selection;
    - Changing selected profile updates preview state and warnings without starting download (coordinator tasks count strictly remains 0);
    - Explicit "Download with profile" button click initiates download, snapshotting approved plan and profile provenance into immutable request;
    - Incompatible profiles render structured reason and actionable alternatives (e.g. switch to audio profile), ensuring no dead/disabled buttons;
    - Custom profile editor dialog validates profile name (disabling save when blank) and captures kind, maxHeight, audio preset, subtitle preference, and strict TV compatibility;
    - Visual regression screenshots captured to `desktopApp/build/ui-regressions/profile-preview-*.png`.
  - Status: **VERIFIED**.
- **NX-P06:** native combined flow and existing manual format selection survive.
  - Implemented by `com.openyt.desktop.downloader.DownloadProfileNativeFlowTest` (3 tests in `:desktopApp:test` covering SQLite persistence, application restart, retry snapshot fidelity, manual format regression, and real local loopback tools).
  - Covers:
    - Combined flow: inspection -> profile resolution -> preview -> explicit download action enqueues plan snapshot into SQLite;
    - Application restart: SQLite restores task state as `INTERRUPTED`, preserving exact `ProfileProvenance`, container, and `fileNameTemplate`;
    - Task retry: creates a fresh queued task preserving original profile snapshot choices without re-resolving or falling back to wrong containers;
    - Existing manual format selection: raw format IDs and custom templates execute without profile remuxing interference and survive restart/retry;
    - Local loopback integration: verifies output streams/codecs via `DesktopMediaProbe` for both profile and manual downloads.
  - Status: **VERIFIED**.

### Offline assets — NX-O

Suggested groups: `OfflineReadinessTest`, `AssetRepositoryTest`,
`AssetPublicationTest`, `OfflineLibraryUiTest`, host `OfflineLibraryLocalTest`.

- **NX-O01:** revision/required-asset readiness semantics and schema upgrade.
  - Implemented by `com.openyt.core.domain.OfflineReadinessTest` (10 tests in `:core:desktopTest` and `:core:testDebugUnitTest`) and `com.openyt.data.OfflineAssetRepositoryTest` (7 tests in `:data:desktopTest`).
  - Covers:
    - Schema v19->v20 migration, backfilling `asset_budget_bytes` (512 MiB) and `storage_reserve_bytes` (128 MiB) defaults, preserving existing settings, tasks, and library entries;
    - Missing media detection marking readiness as `UNAVAILABLE` with `RELINK_MEDIA` recovery action;
    - Changed bytes at same path detecting size/mtime hint mismatch and marking readiness as `STALE` with `REVERIFY` recovery action;
    - Subtitle-only entries successfully achieving `READY` readiness without video/audio stream requirements;
    - Optional artwork missing does not fail nonexistent requirements (imported media without requested artwork remains `READY`);
    - Required artwork missing blocks readiness with `INCOMPLETE` state and suggests `FETCH_ARTWORK`;
    - Required artwork verified yields `READY` status;
    - Old records with unknown asset state remain `PENDING` (never green) until verified;
    - Remote `thumbnailUrl` presence never fakes offline readiness;
    - Display name is never used as a content identifier;
    - Deleting a library entry cascades to delete asset metadata records in SQLite without auto-deleting user files on disk;
    - Invalidating assets for an entry transitions asset states to `STALE`;
    - Asynchronous file inspection and streaming SHA-256 digest computation off the UI thread via `JvmMediaFileInspector`.
  - Status: **VERIFIED**.
- **NX-O02:** confirmed bounded artifact receipts, no unrelated file attachment.
  - Implemented by `com.openyt.core.application.CompanionArtifactConfirmationTest` (8 tests in `:core:desktopTest`), `com.openyt.data.JvmOfflineAssetStoreTest` (4 tests in `:data:desktopTest`), `com.openyt.engine.ytdlp.YtDlpSupportTest` (12 tests in `:engine-ytdlp:desktopTest`), and `com.openyt.desktop.downloader.DesktopYtDlpEngineTest` (10 tests in `:desktopApp:test`).
  - Covers:
    - Backward-compatible optional artifact receipts on `DownloadEvent.Completed(filePath, sizeBytes, artifacts = emptyList())`;
    - Extraction and parsing of engine output receipts for subtitles (`OPENYT_SUBTITLES:`), artwork (`OPENYT_THUMBNAIL:`), and structured artifacts (`OPENYT_ARTIFACT:`), capturing language, format, and timeline basis (`SOURCE`);
    - Output paths containing quotes, Unicode characters, and spaces are preserved and sanitized across POSIX and Windows formats;
    - Strict path containment via `CompanionArtifactValidator.isPathWithinRoot`, rejecting directory traversal (`..`), prefix collisions, and forged out-of-root files (e.g. `/etc/passwd`);
    - Rejection of missing or empty (0-byte) artifact receipts via `CompanionArtifactValidator.validateReceipt`;
    - Multi-track language subtitle dict extraction (`en`, `pt`, `es`) and explicit artwork tagging;
    - Unrelated neighboring subtitles or images in the output folder are never attached by directory guessing;
    - Canceled or interrupted downloads never store unconfirmed artifacts;
    - Primary media completion remains truthful in library and persistence even if optional companion artifact storage fails;
    - Missing required companion artifacts leave offline readiness as `INCOMPLETE` with actionable recovery (`FETCH_ARTWORK`) and retry available;
    - Safe copying into application-owned asset store via `JvmOfflineAssetStore`, streaming SHA-256 digest computation, and SQLite metadata registration in `offline_asset`.
  - Status: **VERIFIED**.
- **NX-O03:** staging/journal/cancel/crash/disk-full, source preservation.
  - Implemented by `com.openyt.core.application.DurableAssetOperatorTest` (9 tests in `:core:desktopTest`), `com.openyt.data.JvmDurableAssetStorageTest` (5 tests in `:data:desktopTest`), `com.openyt.data.SqlDelightAssetOperationRepositoryTest` (2 tests in `:data:desktopTest`), and `com.openyt.data.JvmOfflineAssetStoreTest` (4 tests in `:data:desktopTest`).
  - Covers:
    - Pure KMP domain model `AssetOperation` persisting operation lifecycle states `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELED`, and `INTERRUPTED`;
    - SQLDelight migration 20 (`20.sqm`) creating table `asset_operation` and index `asset_operation_entry_idx`, upgrading from v20 to v21 and preserving existing `offline_asset` and library records;
    - Staging, bounding, streaming, hashing, verifying, and publishing assets into app-owned storage (`.staging/<opId>` -> `<assetsDirectory>/<entryId>/<assetId>.<ext>`) with atomic promotion (`ATOMIC_MOVE` or copy+delete);
    - Structured failure serialization and mapping for `DiskFull`, `RevokedPermission`, `TruncatedCopy`, `SourceMissing`, `VerificationFailed`, `Canceled`, and `Unexpected`;
    - Serialization of disk-heavy asset operations via mutex concurrency control and deduplication of pending submissions for the same asset;
    - Explicit retry deduplication preventing duplicate offline asset entries or orphaned files;
    - Aborting operations when available disk space is insufficient to preserve the configured storage reserve (`storage_reserve_bytes`), with structured `DiskFull` failure;
    - Rejection and staging cleanup of truncated copies (byte length mismatch) without promoting or creating database records;
    - Staging directory boundary enforcement strictly preventing path traversal or escaping `.staging/<opId>`;
    - Cancellation cleaning up the operation's isolated staging directory without touching or deleting original source media;
    - Journal reconciliation (`reconcileJournal()`) restoring active operations interrupted by application crashes, cleaning unpromoted staging directories, and marking unpromoted jobs as `INTERRUPTED`.
  - Status: **VERIFIED**.
- **NX-O04:** bounded artwork fetch/subtitle import and verified local preference.
- **NX-O05:** accurate storage categories, pinning and explicit cleanup UI.
- **NX-O06:** actual offline reopen after networking/cache removal on native hosts.

### Timed search — NX-S

Suggested groups: `SubtitleParserTest`, `TimedSearchRepositoryTest`,
`EntryPlaybackIdentityTest`, `TimedSearchUiTest`, host `TimedSeekLocalTest`.

- **NX-S01:** shipped FTS4/unicode61 viability and measured 100k-cue query budget.
  - Implemented by `com.openyt.data.search.SqliteFtsCapabilityProbeTest` (6 tests in `:data:desktopTest`), `com.openyt.core.domain.FtsCapabilityReportTest` (2 tests in `:core:desktopTest`), and `com.openyt.android.AndroidFtsCapabilityTest` (5 tests in `:androidApp:assembleDebugAndroidTest`).
  - Covers:
    - Pure KMP probe models `FtsCapabilityReport` and `FtsFunctionalVerification` with JSON serialization round-trip and budget verification;
    - Virtual table creation `CREATE VIRTUAL TABLE USING fts4(entry_id, track_id, start_ms, end_ms, plain_text, tokenize=unicode61)` verified across SQLDelight JDBC driver (SQLite 3.53.4) and Android framework driver (`createOpenYtDriver`);
    - Parameterized binding and SQL injection prevention across special symbols, single/double quotes, code blocks, emojis, and comment delimiters;
    - Bidirectional Unicode diacritics folding and tokenization with Portuguese accents ("música" matches "musica", "coração" matches "coracao", "não" matches "nao", "órgão", "dedicação", "açúcar") and English accented loanwords ("naïve", "café", "résumé");
    - Case-insensitive tokenization and multi-word query matching;
    - Incremental updates (`UPDATE ... SET plain_text = ? WHERE rowid = ?`) and deletions (`DELETE FROM ... WHERE rowid = ?`) verifying instant index updates without orphaned entries;
    - Graceful error handling and rejection on invalid or unsupported tokenizers;
    - Synthetic 100,000-cue benchmark fixture with transactional batch insertion (batch size 5,000), 5 warmup queries, and 30 measured warm queries across high, medium, and low-frequency terms;
    - Query p95 latency budget measurement: 0.899 ms on desktop Linux x86_64 (far below the <= 500 ms target budget);
    - Android instrumented probe test suite verifying SQLite FTS4 + unicode61 with target budget <= 1,000 ms.
  - Status: **VERIFIED** on Linux x86_64; Android device blocked by OEM USB install security restrictions.
- **NX-S02:** bounded SRT/VTT parse and explicit local/source timebase mapping.
  - Implemented by `com.openyt.core.application.TimedSubtitleParserTest` (9 tests in `:core:desktopTest`).
  - Covers:
    - Pure KMP domain models `TimedCue`, `ParsedSubtitleTrack`, `SubtitleFormat`, and structured `SubtitleParseException`;
    - SRT and WebVTT parsing without filesystem or process I/O;
    - Strict boundary enforcement rejecting tracks exceeding 10 MiB (10,485,760 chars) or 100,000 cues;
    - UTF-8 BOM (`\uFEFF`) and CRLF/LF line-break normalization;
    - Portuguese ("música", "coração", "não", "órgão", "açúcar", "aprendizado") and English ("café", "résumé", "naïve", "coöperation") accent preservation;
    - Safe linear $O(n)$ HTML/VTT tag stripping (`<b>`, `<i>`, `<u>`, `<font>`, `<c>`, `<v>`, `<script>`) with zero regular expression catastrophic backtracking (ReDoS);
    - Full HTML entity decoding (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, `&#39;`, `&nbsp;`, numeric decimal/hex entities);
    - Speaker extraction from WebVTT `<v Speaker>`, bracketed `[Speaker]`, and prefix `Speaker:` annotations;
    - Multiline cue joining (`\n` in `displayText`, normalized space in `plainText`);
    - Overlapping cue preservation and deterministic sorting by `startMs ASC, endMs ASC, originalIndex ASC` with stable collision-free cue IDs;
    - Explicit local vs source timebase offset shifting (`timebaseOffsetMs`) clamping timestamps to $\ge 0$ without drift;
    - WebVTT metadata handling safely skipping header lines, `NOTE` comment blocks, `STYLE` blocks, `REGION` blocks, and cue settings (`line:`, `position:`, `align:`);
    - Documented nonfatal error recovery: out-of-order sequence numbers, missing blank line separators between cues, empty text cues, and end times preceding start times (clamped with warning).
  - Status: **VERIFIED**.
- **NX-S03:** atomic index revisions, invalidation and crash/restart behavior.
  - Implemented by `com.openyt.data.search.SqlDelightSubtitleIndexRepositoryTest` (7 tests in `:data:desktopTest`) and `com.openyt.core.application.SubtitleIndexRebuilderTest` (8 tests in `:core:desktopTest`).
  - Covers:
    - Pure KMP domain models `SubtitleTrackIndexRecord`, `SubtitleIndexState` (`PENDING`, `INDEXING`, `READY`, `STALE`, `FAILED`), and `IndexReconcileSummary`;
    - Core persistence port `SubtitleIndexRepository`, file check port `SubtitleContentPort`, and `JvmSubtitleContentAdapter` file reader;
    - Cancellable, bounded reindex engine `SubtitleIndexRebuilder` enforcing 10 MiB / 100,000 cues limits, batch transactional cue persistence, and cooperative cancellation;
    - SQLDelight migration 21 (`21.sqm`) creating tables `subtitle_track_index`, `subtitle_cue`, and virtual table `subtitle_cue_fts USING fts4(..., tokenize=unicode61)`, upgrading schema version from 21 to 22;
    - Schema upgrade from v21 to v22 preserves existing `library_entry`, `download_task`, `download_profile`, `offline_asset`, and `asset_operation` rows;
    - Atomic revision commit retaining usable old index while new revision stages: `loadActiveCues` strictly returns committed active revision cues, preventing partial results from being advertised as a complete current index;
    - 1:1 count reconciliation between B-tree `subtitle_cue` and virtual table `subtitle_cue_fts` verified via `countCuesAndFts`;
    - Cancellation mid-reindex cleans all staging cues and FTS rows via `abortRevision`, restoring previous usable revision intact;
    - Crash recovery and journal reconciliation (`cleanAbandonedRevisions()`): recovers interrupted `INDEXING` tracks to `STALE` (if prior usable revision exists) or `FAILED`, purging all abandoned revision rows and orphaned cues without touching source media;
    - Duplicate retry short-circuits without re-parsing when media revision token and track digest match existing `READY` record;
    - Primary media invalidation (`invalidateForMediaChange` / `invalidateEntry`) transitions track indexes to `STALE`, retaining queryability of existing cues until a new revision commits;
    - Track deletion (`deleteTrackIndex`) and entry deletion (`deleteTrackIndexesForEntry`) cleanly remove track records, cues, and FTS rows without touching source media files;
    - Database reopen across restarts verifies persistence fidelity of track index records, cues, and FTS virtual tables.
  - Status: **VERIFIED**.
- **NX-S04:** literal bounded query/pagination/cancellation and scoped results.
  - Contract & Invariants:
    - Scoped search: `TimedSearchScope.Library`, `Collection(collectionId)`, and `Entry(entryId)` return strictly scoped matches without cross-entry contamination;
    - Explicit filters: language filter (e.g. `language = "pt"`) and `trackId` filter isolate target tracks;
    - Deterministic pagination: deterministic tie-breaking sort `ORDER BY entry_id ASC, start_ms ASC, track_id ASC, cue_id ASC` guarantees gapless, duplicate-free pagination across pages;
    - Query validation: pure Kotlin bounds enforcement rejects blank/whitespace queries (`InvalidQueryReason.EMPTY`), queries exceeding 256 characters (`EXCEEDS_LENGTH_LIMIT`), and queries exceeding 8 tokens (`EXCEEDS_TOKEN_LIMIT`);
    - Literal FTS token escaping: double quotes, boolean operators (`AND`, `OR`, `NOT`, `NEAR`), wildcards (`*`), colons, and SQL-like injection strings (`'; DROP TABLE; --`) are treated literally without syntax errors or unescaped execution;
    - Accent matching & excerpt highlighting: bidirectional diacritic folding ("música" $\leftrightarrow$ "musica", "café" $\leftrightarrow$ "cafe", "açúcar" $\leftrightarrow$ "acucar") with character-exact `TextHighlightRange` offsets and length-bounded excerpt centering with ellipsis (`...`);
    - CJK & unspaced text behavior: continuous unspaced CJK characters are tokenized by SQLite `unicode61` as contiguous tokens; exact tokens match reliably;
    - Deleted entry isolation: deleting an entry from `library_entry` immediately excludes all its cues from Library and Collection searches, and returns `IndexUnavailable(ENTRY_NOT_FOUND)` on Entry search;
    - Distinct typed states: `TimedSearchResult.IndexUnavailable` (typed reasons: `NO_SUBTITLE_TRACKS`, `INDEX_PENDING_OR_STALE`, `INDEX_FAILED`, `ENTRY_NOT_FOUND`, `COLLECTION_EMPTY`) vs `TimedSearchResult.Success(items = emptyList(), totalMatches = 0)` are distinct typed states;
    - Cancellation: coroutine cooperative cancellation (`ensureActive()`) aborts superseded searches without leaking database or thread pool resources;
    - Performance budget: measured 100,000-cue synthetic corpus p95 search latency through `SqlDelightTimedSearchRepository` passed at 73.96 ms (budget $\le 500$ ms).
  - Status: **VERIFIED**.
- **NX-S05:** actual prepared-player seek, entry identity and legacy queue bridge.
  - Contract & Invariants:
    - Core playback session port: `PlaybackSessionControl` exposes `loadedMedia`, `state` (`IDLE`, `PREPARING`, `PLAYING`, `PAUSED`, `COMPLETED`, `ERROR`), `capabilities` (`canSeek`, `canMiniPlayer`, `canPause`, `isInternalPlayer`, `limitationDescription`), `currentPositionMillis`, `durationMillis`, `openAt(items, startIndex, startPositionMillis)`, `seekTo(positionMillis)`, `pause()`, `play()`, `stop()`, and `close()`;
    - Entry identity & queue compatibility: `PlayableMedia` carries optional `entryId: String? = null` with backward-compatible JSON serialization/deserialization of legacy queue JSON;
    - Entry ID checkpoint bridge: `PlaybackStore` and `SqlDelightPlaybackStore` bridge legacy path records without `entry_id` to their corresponding entry ID upon lookup;
    - Moving entry locator: when media files are relocated or relinked to a new path with the same `entryId`, `position(newPath, entryId)` recovers the saved checkpoint, and `savePosition(newPath, checkpoint, entryId)` updates the path while cleaning up obsolete path records;
    - Missing ID fallback: media files without an entry ID save and load purely by `path` without disruption;
    - Asynchronous prepare seek: `openAt(items, startIndex, startPositionMillis)` records pending seek positions during asynchronous player engine preparation and applies the clamped timestamp immediately upon media playback ready;
    - Out-of-range clamping: `clampSeekPosition` clamps negative seek targets to 0 ms and targets exceeding media duration to the duration limit;
    - Stale callback rejection: switching active media entries or issuing a new playback command increments generation counters, discarding obsolete pending seeks and ignoring out-of-order player callbacks;
    - External fallback & limitation reporting: when internal player engines (VLCJ / Media3) are unavailable, launcher falls back to external system players and reports `canSeek = false` with descriptive limitation text;
    - Clean lifecycle & native teardown: closing the desktop player flushes playback checkpoint and queue, resets session state to `IDLE`, and releases the native VLC component synchronously before disposing native frame surfaces.
  - Status: **VERIFIED**.
- **NX-S06:** missing-index vs no-match UI and correct accessible timestamp action.
  - Invariants:
    - Scope and index status badges: `TimedSearchScopeIndicator` renders scoped navigation (Library, Collection, Entry), index availability badges (`READY`, `PARTIALLY_INDEXED`), and explicit language filter chips;
    - Plain-text excerpt highlighting: `TimedSearchExcerptText` applies styled highlighted spans with character offsets and preserves screen-reader accessibility semantics;
    - Distinct missing-index vs empty result: `TimedSearchStateView` differentiates `IndexUnavailable` (explains missing subtitle tracks, pending/stale index, indexing failures, and surfaces actionable import/reindex buttons) from genuine empty search results `Success(items = emptyList(), totalMatches = 0)` which clearly states no matches were found without false error banners;
    - Source time offset in playback: Activating a result timestamp seeks to `(startMs + timebaseOffsetMs).coerceAtLeast(0L)` and plays strictly the intended entry ID in isolation;
    - Stale query cancellation: Superseded queries are debounced and cancelled, discarding obsolete pending searches so only fresh results are presented;
    - Keyboard navigation: Timestamp action button and cards are focusable and respond to Enter and Spacebar activations;
    - Accessibility semantics: Content description on the timestamp button formats title, time, and excerpt text (`$title aos $time: $excerpt` / `$title at $time: $excerpt`);
    - Responsive layout & multi-language localization: Rendered cleanly under 320dp width and 1.5x font scale across pt-BR and en strings without clipping or truncation.
  - Status: **VERIFIED**.
- **NX-S07:** offline search phrase to real player time and native close/reopen.
  - Contract & Invariants:
    - Search to real player time: with networking disabled, searching synthetic phrases in Portuguese (`"pesquisa sincronizada"`) and English (`"synthetic playback target"`) retrieves mapped cue results and initiates playback seeking to the cue timestamp within one second ($\le 1000$ ms) of cue start;
    - Cross-platform verification: proven on Desktop (`TimedSearchPlaybackEndToEndTest`: Portuguese and English synthetic phrases with precision seek $\le 1000$ ms, verified checkpoint persistence) and Android (`TimedSearchPlaybackEndToEndTest`: instrumented offline query, cue match at 3000 ms, launch `PlayerActivity`, `MediaController` position match within 1000 ms, validation screenshot);
    - Offline isolation: all index retrieval, FTS queries, and playback launch operate strictly against local offline SQLite database without network requests;
    - Checkpoint persistence and close/reopen cycle: closing the player persists the position checkpoint via `store.savePosition(...)` and reopening via `openAt(...)` restores position seamlessly without loss;
    - Tokenizer boundaries & Language limits: FTS4 `unicode61` tokenizer uses whitespace boundaries with Latin diacritic folding. Languages without whitespace delimiters (such as Chinese, Japanese, Thai) are treated as single tokens rather than morphologically segmenting into words. Query inputs are bounded to 256 characters and 8 tokens maximum;
    - Non-ASR / Non-Speech-Recognition distinction: Timed search operates strictly on structured subtitle tracks (SRT, VTT) embedded in or imported alongside the media. OpenYT does not perform acoustic transcription, neural speech-to-text, or local/cloud audio decoding into text.
  - Status: **VERIFIED**.

### Study — NX-T

Suggested groups: `StudyRepositoryTest`, `StudySessionTest`, `StudyLinkTest`,
`StudyUiTest`, host `StudyPlaybackLocalTest`.

- **NX-T01:** notes/ranges/manual completion survive restart/path changes.
  - Contract & Invariants:
    - Entry-anchored persistence (`StudyNote`, `StudyBookmark`, `SavedStudyRange`, `EntryStudyState`): all study records bind to stable `library_entry.id` and millisecond positions rather than mutable paths or source URLs;
    - Schema v24 (`23.sqm`) migration & reopen: upgrading from schema v23 preserves legacy library, collection, playback, and subtitle data cleanly while persisting and reloading study tables across database restarts (`verifyCommonMainOpenYtDatabaseMigration`, `SqlDelightStudyRepositoryTest`);
    - Injected clock, stable IDs & Unicode limits (`StudyService`, `StudyValidator`): timestamps come from an injected clock with `createdAtEpochMillis` preserved across edits; code-point validation enforces non-blank text, max 20,000 Unicode chars on notes, max 120 Unicode chars on bookmark and range labels (treating supplementary emoji surrogate pairs as 1 character), `0 <= timestamp`, `start < end`, interval >= 1,000 ms, and known-duration bounds;
    - Overlapping intervals & deterministic ordering: overlapping note intervals, overlapping saved ranges, and same-timestamp entries are supported and deterministically ordered across memory and SQLite (`timestamp ASC, COALESCE(end, -1) ASC, created_at ASC, id ASC`);
    - Locator moves vs stale content revisions: updating media paths (`updateLibraryPath`) or replacing `library_entry` rows does not trigger SQLite cascade deletion and keeps revision anchors valid; modifying underlying content revisions (`updateLibraryRevision`) flags mismatched anchors via `StudyRevisionComparator` (`staleNoteIds`, `staleBookmarkIds`, `staleRangeIds`), while body-only edits preserve the stale warning until re-anchored;
    - Independent manual completion & collection progress: `entry_study_state` is separate from `playback_position`; playback end resets (`resumeMillis == 0`) do not erase manual completion; collection progress counts `studiedCount / totalMemberCount` over current members reflecting shared membership and additions/removals dynamically;
    - Missing entry & removal preview semantics: writes for missing entries return `StudyFailure.EntryNotFound` and updates/deletes on missing items return `StudyFailure.ItemNotFound`; `previewEntryRemoval` reports authored notes/bookmarks/ranges, confirmation prompts, and physical media preservation; `deleteLibraryEntry` never silently cascades authored study text, and `deleteAllStudyDataForEntry` cleans study records only on explicit user confirmation without touching user files.
  - Status: **VERIFIED**.
- **NX-T02:** timestamp capture cannot race into the wrong entry; stale anchors.
  - Contract & Invariants:
    - Atomic session capture & entry identity (`PlaybackSessionControl.captureSnapshot()`, `PlaybackPositionSnapshot`, `StudyCaptureAndNavigationService`): captures loaded media identity, playback state, capabilities, position, duration, and `sessionGeneration` together; re-verifies identity/generation after async lookups so concurrent queue switches or expected-entry mismatches reject with `StudyCaptureFailure.EntrySwitchRace` without writing a note into the wrong or previously playing entry;
    - Player state & capability gating: `IDLE`, `PREPARING`, `ERROR`, external/unseekable fallbacks (`!capabilities.isInternalPlayer || !capabilities.canSeek`), missing media files, and unlinked media (`entryId == null`) reject capture cleanly (`PlayerStopped`, `PlayerNotReady`, `PlayerError`, `CapabilityUnsupported`, `MissingMediaFile`, `UnlinkedMedia`) rather than fabricating timestamps;
    - Stale & out-of-range anchor diagnostics (`StudyAnchorInspector`): validates against known duration (`> 0L`) and flags shortened/edited media (`STALE_REVISION`, `OUT_OF_RANGE`, `STALE_REVISION_AND_OUT_OF_RANGE`) without silently rewriting stored annotations; unconfirmed navigation to out-of-range timestamps returns `StudyNavigationResult.OutOfRangeAnchor` until explicitly repaired or clamped (`clampOutOfRangeNoteToDuration`, `reanchorBookmarkToCurrentRevision`);
    - Unsaved edits & canceled drafts (`StudyEditorCoordinator`): dirty note/bookmark drafts survive normal app navigation (`onNormalNavigation`), emit `PromptConfirmDiscard` before closing/canceling/switching away, retain text across validation failures, and leave persisted DB state untouched on confirmed discard;
    - Duplicate taps & position agreement: duplicate capture/save/navigation taps are cleanly suppressed (`DuplicateTapIgnored`), while opening valid notes/bookmarks seeks active playback or launches the target entry at the exact saved time and verifies player position agreement (`Opened`); implemented across core KMP, `DesktopPlaybackLauncher`, and `AndroidPlaybackLauncher` / `PlaybackService`.
  - Status: **VERIFIED**.
- **NX-T03:** five real A/B loops within stated tolerance and clean stop/switch.
  - Contract & Invariants:
    - Explicit active-session control (`AbRepeatModels`, `StudyAbRepeatController`): sets A/B (`0 <= A < B <= known duration`, `B - A >= 1000 ms`) or activates persisted `SavedStudyRange` rows strictly on explicit user/session action; persisted ranges never auto-start playback or repeat after restart;
    - Capability & duration gating: external fallback (`!isInternalPlayer`), non-seekable players (`!canSeek`), and unknown duration (`durationMillis <= 0`) disable A/B repeat with structured explanations instead of simulating loops (`DesktopPlaybackSessionTest`, `StudyAbRepeatControllerTest`);
    - Serialized/coalesced seeks & end-of-media safety: concurrent position ticks, slow seek callbacks, and async post-seek lag coalesce behind single-flight `seekMutex`, epoch cancellation, and settling latch guards so slow/jammed backends cannot enter an infinite seek storm; EOF (`COMPLETED`) restarts cleanly at `A` without unintended queue auto-advance;
    - Pause, PiP/mini-player, lifecycle clearing & 5-loop real media tolerance: `PAUSED` states and audio focus / PiP / mini-player remain intact without fighting pause; explicit `stop()`, `close()`, item switch, and native `ERROR` clear active repeat with zero background playback after stop; proven over 5 consecutive real loops within `<= 1000 ms` return-to-`A` and `<= 1000 ms` `B`-overshoot budgets on real Desktop VLCJ + FFmpeg (`DesktopPlaybackLocalTest`) and wired on Android Media3 (`PlaybackService` / `AndroidPlaybackLauncher` / `AndroidAbRepeatContractTest`).
  - Status: **VERIFIED**.
- **NX-T04:** escaped Markdown, typed local links, OS dispatch and privacy.
  - Contract & Invariants:
    - Privacy-preserving safe Markdown (`StudyMarkdownExporter`, `StudyMarkdownExporterTest`): escapes unsafe HTML/script tags, markdown control characters, and link brackets/parens; excludes private filesystem paths (`filePath`), source tokens, and credentials from exported prose; generates safe suggested filenames via `StudyMarkdownExporter.suggestedFilename(...)`;
    - Typed local entry links (`OpenYtEntryLink`, `OpenYtLink`, `OpenYtLinkTest`): builds/parses `openyt://library/entry/<percent-encoded-id>?t=<millis>` links strictly rejecting path traversal separators (`/` and `\`) in `entryId`, malformed schemes, or negative timestamps; limits local-link scope strictly to the library retaining that `entry_id` (non-portable across distinct entry IDs);
    - Host capability gating (`StudyHostLinkCapability`): omits clickable `openyt://library/entry/...` Markdown links when `supportsClickableEntryLinks` is `false` (default unless the host verifies OS scheme dispatch);
    - Host OS dispatch & file export (`DesktopDeepLinks`, `DesktopInstance`, `DesktopCli`, `DesktopTextFileExporter`, `AndroidTextFileExporter`, `AndroidManifest.xml`, `DesktopDeepLinksTest`): Linux `.desktop` (`openyt-link.desktop`), Windows HKCU protocol registration/verification, macOS OpenURI, and Android `ACTION_VIEW` scheme dispatch route safely into app open/seek without auto-playing unrequested loops.
  - Status: **VERIFIED**.
- **NX-T05:** accessible edit/export/progress UI and authored-data removal preview.
  - Contract & Invariants:
    - Focused Study UI & responsive Compose layout (`StudySheetDialog`, `StudySheetSections`, `LibraryScreen`, `StudyUiTest`): accessible add/edit/delete actions for notes, bookmarks, and A/B ranges, manual studied completion toggle, visible collection study counts (`studiedCount / totalMemberCount`), unsaved draft discard prompts, Markdown export toggle (with explicit local-link capability state), and timestamp navigation remain clickable without clipping across compact `320x640` (`1.5x` text scale, dark Portuguese) and enlarged `1280x800` (`2.0x` text scale, English) viewports via unified scrollable `LazyVerticalGrid` + `FlowRow` toolbars;
    - Cold deep-link safety & removal preview (`StudyRemovalDialog`, `StudyUiTest`): cold `openyt://library/entry/...` links navigate safely without auto-starting playback unexpectedly, and removal confirmation explicitly separates authored study text deletion from non-destructive media retention.
  - Status: **VERIFIED** (`:desktopApp:test --tests '*UiTest'`: 57 passed, 0 failed across responsive mobile/desktop configurations; `:ui:desktopTest`: passed).
- **NX-T06:** full offline study flow, PiP/mini-player and actual exported links.
  - Contract & Invariants:
    - Cross-layer offline lifecycle (`StudyPlaybackLocalTest`, `AndroidStudyFlowContractTest`, `WindowsContractHarness` `"study"`): captures live timestamps, executes 5 real A/B repeat loops with mini-player/PiP and pause preservation (`maxOvershootMs=400`, `maxReturnDeltaMs=0` measured on Linux VLCJ+FFmpeg vs `<= 1000 ms` budget), maintains manual studied completion across playback-end resume resets (`resumeMillis == 0`), reopens SQLite cleanly without auto-play or auto-repeat, seeks to exact note timestamps, exports privacy-safe Markdown with clickable local links, dispatches `openyt://library/entry/<id>?t=<ms>` routes, and purges study rows after confirmation while preserving physical media hashes byte-for-byte.
    - Explicit scope limits documented: local entry links remain valid only on libraries preserving that `entry_id` and when `supportsClickableEntryLinks` is verified by host OS dispatch; manual `entry_study_state` completion is independent of end-of-playback resume checkpoint reset.
  - Status: **VERIFIED — Linux VLCJ (`StudyPlaybackLocalTest`: 1/1), Windows MSI/EXE CI (`msi-study` & `exe-study`), macOS CI, and Android Emulator CI (`AndroidStudyFlowContractTest`) passed**.

### Backup — NX-B

Implemented & verified suites: `PortableManifestTest`, `PortableSnapshotSourceTest`,
`PortableZipStreamingTest`, `PortableExportTest`, `PortableExportLocalTest`,
`PortableArchiveSecurityTest`, `PortableArchiveSecurityLocalTest`,
`PortableRestoreJournalTest`, `PortableBackupUiTest` (`4/4` UI configurations),
`PortableRoundTripTest` (`1/1`), `AndroidPortableBackupTest` (`2/2` on API 30 emulator).

- **NX-B01:** independent manifest version, consistent metadata and safe fields — **VERIFIED** (`PortableManifestTest`, `PortableSnapshotSourceTest`, golden manifest v1 fixture; URLs/cookies/local paths/FTS tables strictly excluded).
- **NX-B02:** measured streaming/ZIP64 support including >4 GiB native fixture — **VERIFIED** (`PortableZipStreamingTest`: opt-in `OPENYT_RUN_ZIP64_INCOMPRESSIBLE_TESTS=1` streamed `4,350,000,000`-byte incompressible payload into `4,351,322,924`-byte ZIP64 archive and extracted via `JvmPortableArchiveReader` with `7 MiB` peak heap delta; default suite tests >4 GiB sparse stream + cancellation + SAF non-seekable pipes `4/4`).
- **NX-B03:** complete export receipt, cancellation and source-change detection — **VERIFIED** (`PortableExportTest`, `PortableExportLocalTest`, `DatabaseMigrationTest` Schema v25 `portable_operation` journal + startup reconciliation).
- **NX-B04:** hostile archive bounds, traversal/collision/symlink/digest rejection — **VERIFIED** (`PortableArchiveSecurityTest`, `PortableArchiveSecurityLocalTest`, `AndroidPortableBackupTest`; rejects symlinks, `.` / `..`, drive/UNC, NUL, case-folded duplicates, >16 MiB manifest, undeclared entries, and digest mismatches).
- **NX-B05:** journaled restore, complete ID remapping and no existing changes — **VERIFIED** (`PortableRestoreJournalTest`, fault-injected commit & restart reconciliation, full ID remapping across entries/assets/collections/smart collections/profiles/checkpoints/study, post-commit local FTS rebuild).
- **NX-B06:** desktop–Android–desktop round-trip with media hashes and context — **VERIFIED** (`PortableRoundTripTest`, `AndroidPortableBackupTest` `FileProvider` `content://` round-trip + SQLite reopen + FTS4 search, `PortableBackupUiTest` `4/4`).

### Recovery — NX-R

Implemented suites: `LibraryHealthTest`, `JvmMediaHealthInspectorTest`,
`RecoveryCandidateScannerTest`, `JvmRecoveryCandidateScannerTest`, `RelinkPlannerTest`,
`RelinkJournalTest`, `RecoveryUiTest` (`4/4`), `AndroidLibraryRecoveryTest` (`1/1` on
API 30 emulator), host `RelinkLocalTest` (`3/3` Linux VLCJ), and `WindowsContractHarness`
(`"recovery"`).

- **NX-R01:** read-only accurate health/permission/content states — **VERIFIED** (`LibraryHealthTest`, `JvmMediaHealthInspectorTest`, `AndroidLibraryRecoveryTest`; `AVAILABLE`, `MISSING`, `PERMISSION_LOST`, `CHANGED_CONTENT`, `UNKNOWN` without mutating DB/files).
- **NX-R02:** bounded root scan/hash, verified identity vs heuristics — **VERIFIED** (`RecoveryCandidateScannerTest`, `JvmRecoveryCandidateScannerTest`; bounded depth/count, symlink loop guard, `Mutex` sequential SHA-256, `EXACT_CONTENT` vs `HEURISTIC_METADATA`).
- **NX-R03:** exact preview/confirmation, active playback and stale-plan rejection — **VERIFIED** (`RelinkPlannerTest`, `RelinkJournalTest`, `RelinkLocalTest`, `RecoveryUiTest` `4/4`; stops active player before relink and checks stale plan state).
- **NX-R04:** same entry/context/checkpoint after relink and interrupted retry — **VERIFIED** (`RelinkJournalTest`, `RelinkLocalTest`, `AndroidLibraryRecoveryTest`; preserves `LibraryEntry.id`, collections, study records, checkpoints; selectively stamps stale anchors & invalidates FTS on `CHANGED_CONTENT_REPLACEMENT`).
- **NX-R05:** advisory duplicates and record-only removal with authored context — **VERIFIED** (`LibraryHealthTest`, `RecoveryUiTest` `4/4`; duplicate review unlinks record only without touching user media files).
- **NX-R06:** native moved-file recovery, reopen and Windows shell/player behavior — **PARTIAL / AWAITING WINDOWS CI** (`RelinkLocalTest` `3/3` passed on Linux with `OPENYT_RUN_PLAYBACK_TESTS=1` real VLCJ stop-relink-reopen; `AndroidLibraryRecoveryTest` `1/1` passed on API 30 emulator; Windows `"recovery"` compiled in `windowsContractHarnessJar`, awaiting `windows-latest` CI verification).

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
