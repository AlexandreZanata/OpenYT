# OpenYT Portable Media Backup Format (`formatVersion = 1`)

This document specifies the portable backup archive format produced and consumed by OpenYT (`NX5`).

## 1. Container & Layout

- **File extension**: `.openyt-backup.zip` (`application/zip`).
- **Streamability**: Standard UTF-8 ZIP container written and read sequentially (`java.util.zip.ZipOutputStream` / `ZipInputStream`). Archives may use ZIP64 extensions when total bundle size or any individual file exceeds 4 GiB.
- **Required manifest entry**: `manifest.json` (UTF-8 JSON). Readers must accept `manifest.json` at any stream position (first entry or trailing entry after single-pass media streaming).
- **Blob entries**: Media files and companion offline assets live exclusively under normalized relative paths:
  - `media/<entrySlot>/<sanitized-filename>`
  - `assets/<entrySlot>/<sanitized-filename>`
- **Hard container safety caps**:
  - `manifest.json` maximum uncompressed size: `16 MiB` (`16,777,216` bytes).
  - Maximum ZIP entries per bundle: `100,000`.
  - No symlink entries, no absolute paths, no leading `/`, no `\` separators, no `.` or `..` segments, no NUL bytes, no Windows drive letters or UNC prefixes, and no exact or case-folded duplicate entry paths.
  - Every blob referenced in `manifest.json` must match its declared `byteCount` and lowercase SHA-256 hex digest. No undeclared blob entries are allowed in the ZIP.

## 2. Included vs. Excluded Data

### Included (Portable User Data)
- `entries[]`: portable entry metadata (`exportId`, `title`, `kind`, `author`, `source`, sanitized `thumbnailUrl`, `createdAtEpochMillis`, `sizeBytes`, `tags[]`).
- `primaryMedia`: normalized `PortableBlobRef` (`archivePath`, `byteCount`, `sha256`, `originalFileName`, `mediaKind`, `lastModifiedEpochMillis`), or `null` in metadata-only / explicitly skipped backups.
- `playbackCheckpoint`: entry-scoped resume position (`positionMillis`, `durationMillis`) without host paths.
- `study`: authored notes (`StudyNote`), bookmarks (`StudyBookmark`), saved A/B intervals (`SavedStudyRange`), and manual `isStudied` status anchored to the media revision.
- `offlineAssets[]`: durable portable companion assets (`THUMBNAIL`, `SUBTITLE`) with normalized `PortableBlobRef`.
- `collections[]` & `smartCollections[]`: user collections, membership lists referencing `exportId`, and filter definitions.
- `downloadProfiles[]`: custom `DownloadProfile` definitions without host destination bindings.
- `skippedItems[]`: explicit record of omitted assets/media when partial backup is chosen.

### Strictly Excluded
- Live SQLite database files (`.db`, `-wal`, `-shm`, `-journal`).
- Authentication state, cookies, cookie encryption keys, tokens, credentials, and URLs containing userinfo (`user:pass@`) or secret query tokens.
- Host-local filesystem paths (`filePath`, `downloadsDirectory`, `targetLocator`), Android SAF `content://` `storageTreeUri` bindings, and queued engine tasks.
- Derived/regenerable subtitle cue tables (`subtitle_cue`, `subtitle_cue_fts`) and temporary ASR/rendering caches. Secondary search indexes are rebuilt locally after restore commits.

## 3. Compatibility & Conflict Rules

- `formatVersion`: integer format revision (`1`).
- `minReaderVersion`: minimum reader version required (`1`). Readers reject archives where `minReaderVersion > 1`.
- Restores never overwrite existing media files on disk or existing database records silently. Conflicting IDs are remapped consistently across entries, assets, tags, collections, playback checkpoints, and study records unless `SKIP_IF_PRESENT` skips an exact matching content SHA-256.

## 4. Privacy Notice

`privacyNotice = "UNENCRYPTED_PORTABLE_BACKUP"`.
The archive stores titles, tags, study notes, bookmarks, collections, and playback positions in plaintext JSON so archives remain inspectable and portable across Android and Desktop without platform keystores. Users should protect exported `.openyt-backup.zip` files externally when backing up sensitive study libraries.
