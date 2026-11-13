# ADR-0025 — Portable media backup and assisted library recovery

- Status accepted
- Date 2026-11-13

## Context

Milestone 2 added offline enrichment, subtitle FTS search, and study workflows anchored
to library entries (`LibraryEntry`), playback checkpoints, companion `OfflineAsset`
files, collections, smart collections, download profiles, and authored study records.
Before Milestone 3, migrating a library between devices required manually re-downloading
or re-importing files and lost study annotations, checkpoints, collections, and profiles.
At the same time, real users rename, move, or re-grant access to media files outside
OpenYT (on Windows, Linux, macOS, or via Android Storage Access Framework), leaving
entries broken on disk or duplicated without an explanation.

Directly shipping `.db` SQLite files or auto-rewriting library paths on startup would
break schema evolution across versions, leak machine paths, SAF URIs, cookies, or tokens,
expose path-traversal/ZIP-bomb vectors on untrusted bundles, or silently point an entry's
timestamps and subtitle index at a completely different file.

## Decision

### 1. Portable Media Backup and Restore (`NX5`)

- **Decoupled versioned format (`formatVersion = 1`)**: Archives use `.openyt-backup.zip`
  with UTF-8 `manifest.json` specified in `docs/spec/PORTABLE_BACKUP_FORMAT.md`, independent
  from SQLite schema 25 and legacy JSON storage archives. Readers reject archives where
  `minReaderVersion > 1`.
- **Portable content vs. excluded local state**: `PortableBackupSnapshotSource` /
  `PortableSnapshotCollector` export `LibraryEntry` metadata, media & offline companion
  asset blob references (`PortableBlobRef` with size and lowercase SHA-256), entry-scoped
  `PlaybackCheckpoint`s, authored study data (`StudyNote`, `StudyBookmark`,
  `SavedStudyRange`, `EntryStudyState`), `Collection` / `SmartCollection` definitions, and
  custom `DownloadProfile`s. Live SQLite files (`.db`, `-wal`, `-shm`, `-journal`), raw host
  paths, SAF `storageTreeUri` bindings, cookies, credentials, secret tokens in URLs, queued
  engine tasks, and regenerable subtitle cue FTS rows (`subtitle_cue`, `subtitle_cue_fts`)
  are excluded; post-restore FTS search is rebuilt cleanly locally.
- **Plaintext inspection and consent**: Backups carry `privacyNotice =
  "UNENCRYPTED_PORTABLE_BACKUP"` in plaintext JSON to remain interoperable across Android
  and Desktop without platform keystores, and restore previews explicitly disclose this before
  confirmation.
- **Streaming ZIP64 & SAF sequential pipe I/O**: `JvmPortableArchiveWriter` and
  `JvmPortableArchiveReader` use platform `java.util.zip.ZipOutputStream` /
  `ZipInputStream` (with zero-copy/private staging on Android SAF via
  `AndroidPortableStreamBridge`), streaming large files (>4 GiB ZIP64 uncompressed and
  compressed sizes) without full-buffer heap growth and preserving clean cancellation cleanup.
- **Hostile archive defense & SHA-bound restore plan**: `PortableArchivePathRules` and
  `PortableRestorePlanner` enforce hard container caps (`manifest.json <= 16 MiB`,
  `<= 100,000` entries), accept `manifest.json` at any ZIP stream position, reject symlinks,
  traversal (`.`, `..`, backslashes, drive/UNC prefixes), NUL bytes, case-folded duplicates,
  undeclared ZIP entries, and SHA-256 / byte-count mismatches. Restores bind execution to the
  validated `archiveSha256` and require explicit user confirmation.
- **Journaled atomic execution & full ID remapping**: Schema 25 (`24.sqm`) adds
  `portable_operation` for persistent export/restore/relink journaling and startup
  reconciliation of interrupted runs. `PortableIdRemapper` remaps conflicting IDs consistently
  across entries, assets, tags, collections, checkpoints, and study records unless
  `SKIP_IF_PRESENT` matches existing SHA-256 content. `PortableRestoreExecutor` and
  `SqlDelightPortableRestorePublicationPort` write media into unique non-overwriting targets
  and commit all SQLite rows in a single transaction without ever touching pre-existing user
  files on disk.

### 2. Assisted Library Recovery (`NX6`)

- **Read-only health inspection**: `LibraryHealthAnalyzer`, `JvmMediaHealthInspector`, and
  `AndroidMediaHealthInspector` inspect library entries without mutating files or rows,
  classifying each entry as `AVAILABLE`, `MISSING`, `PERMISSION_LOST`, `CHANGED_CONTENT`, or
  `UNKNOWN` (plus nullable `repository` guards across UI entry points).
- **Bounded candidate scanning & explicit evidence separation**: `RecoveryCandidateScanner`,
  `JvmRecoveryCandidateScanner`, and `AndroidRecoveryCandidateScanner` walk user-selected
  folders or SAF trees with bounded depth/count, symlink/cycle protection, and `Mutex`
  sequential SHA-256 verification. UI (`RecoveryDialog`) and domain planners strictly
  distinguish verified cryptographic matches (`EXACT_CONTENT`) from filename/size heuristics
  (`HEURISTIC_METADATA`).
- **Explicit preview, playback release, and Android SAF copy**: `RelinkPlanner` builds a
  preview bound to entry ID, prior revision, target digest, and `RelinkContentMode`;
  `RelinkExecutor` re-validates plan freshness, stops active playback on the target path
  before relinking (releasing Windows/VLCJ file locks via `PlaybackSessionControl`), and
  copies Android SAF `content://` picks into app-owned storage (`COPY_SAF_TO_OWNED`)
  instead of persisting transient document URIs.
- **Atomic relink & selective staleness/FTS invalidation**: `SqlDelightRelinkPublicationPort`
  updates `file_path`, `size_bytes`, media revision, playback checkpoint mapping, collections,
  and study linkage in one transaction while preserving the original `LibraryEntry.id`. Only
  `CHANGED_CONTENT_REPLACEMENT` stamps prior study anchors/offline assets as potentially
  stale (`StudyAnchorInspector`) and invalidates derived subtitle cues (`subtitle_cue` / FTS).
- **Advisory duplicate review & non-destructive deletion**: Duplicate groups by matching
  SHA-256 are strictly advisory; cleanup unlinks redundant library rows (supporting optional
  authored study transfer or warning) via `requestRemoval` without deleting user media files.
- **JVM UI string grouping**: `AppStrings` extracts `ErrorStrings` alongside `StorageStrings`
  with forwarding getters so adding `RecoveryStrings` keeps generated JVM `copy$default`
  methods below the 255-parameter JVM bytecode signature limit (`ClassFormatError`).

## Consequences

- Cross-platform library migrations between Desktop and Android preserve authored study
  data, checkpoints, assets, collections, and custom profiles without database version
  coupling.
- Interrupted or hostile exports/restores/relinks leave existing user media files and
  database rows untouched.
- Future encryption (`UNENCRYPTED_PORTABLE_BACKUP` -> passphrase-wrapped container) remains
  additive via `formatVersion = 2` without altering the core snapshot model.
- Recovery UI and storage dialogs maintain safe JVM bytecode signatures even as additional
  localized modules are added to `AppStrings`.
