# NX5 — portable media backup and restore

Outcome: export selected files and library context, then restore them on another
device without an account. Prerequisite: NX4.6. Read ADR-0005, ADR-0010,
ADR-0014/0016, current `LibraryArchive` and the asset/study repositories.

## Fixed behavior

- Keep existing JSON-record export/import working. Add a separate streamed ZIP
  bundle with manifest **formatVersion 1**, independent of SQL schema version and
  the legacy JSON schema. Never copy a live SQLite database as the portable format.
- Include selected media, durable artwork/subtitles, tags, collections/membership,
  notes/bookmarks/studied state, playback checkpoints and portable profile
  definitions referenced by the selection. Include provenance with safe fields.
- Exclude secrets, cookie jars, tokens, machine paths/destination bindings,
  subscription automation, transient queues, ASR models and rebuildable FTS data.
  No encryption claim for this MVP. Explain that media/notes in the ZIP are private.
- Each blob has a controlled relative name, byte size and SHA-256. Preserve IDs
  when free; remap conflicts explicitly across **all** references. A manifest is
  untrusted input, not a command or a trusted absolute filesystem locator.
- Restore creates new copies under a chosen owned destination. No overwriting or
  deleting existing media/library. Preview selection, size, missing files and
  ID/title conflicts before confirmation. Do not use legacy REPLACE mode here.
- Archive limits are explicit and checked before extraction: manifest <=16 MiB,
  <=100,000 entries, declared/actual expanded bytes bounded by approved payload
  total and available-space reserve. Stream all large blobs; never load a movie
  into memory. ZIP64 >4 GiB needs a real capability test, not an assumption.

<a id="nx5-1"></a>

## NX5.1 — specify and validate manifest v1

- **Read/modify:** core portable models/validation and focused snapshot APIs.
- **Do:** write a small checked-in format specification and golden valid manifest.
  Define required/optional fields, deterministic ordering, content references,
  version rejection and conflict ID mapping. Read a consistent metadata snapshot
  without holding a database transaction during large file copies.
- **Tests:** `NX-B01`: golden round-trip, unknown compatible fields, unsupported
  version, duplicate IDs, dangling refs, negative/overflow sizes, secret/path
  exclusion and metadata snapshot consistency while the library changes.
- **Done:** format can represent NX1–4 without depending on raw SQL table layouts.

<a id="nx5-2"></a>

## NX5.2 — prove streaming archive support on each host

- **Prerequisite:** NX5.1. **Read/modify:** platform/archive adapters and license
  record only if an additional dependency is concretely needed.
- **Do:** prefer available platform APIs; verify ZIP64, streaming cancellation,
  Unicode entry names and Android SAF output. Provide sequential private staging
  if SAF cannot seek; budget its disk cost. Progress uses actual bytes.
- **Tests:** `NX-B02`: generated >4 GiB incompressible payload in an explicit
  resource-gated test on supported hosts, memory measurement and no integer
  overflow. Ordinary CI uses small deterministic fixtures. If ZIP64 fails, stop
  this gate and resolve the format/adapter decision; do not label small ZIP tests
  as proof of large-library support.
- **Done:** documented native support and storage costs, no unreviewed dependency.

<a id="nx5-3"></a>

## NX5.3 — implement consistent cancellable export

- **Prerequisite:** NX5.2. **Read/modify:** snapshot use case, NX2 job/journal,
  file/archive adapters and host save pickers.
- **Do:** preflight size/free space, write owned temporary bundle, stream/hash
  payloads, verify source revision before/after copying and publish only a complete
  archive. A changed/missing source triggers a report and explicit retry/skip
  decision; never silently claim that every selected file was backed up.
- **Tests:** `NX-B03`: cancel, disk-full, changed source mid-copy, missing media,
  permission loss and crash/restart. Original hashes and existing backups remain
  unchanged. Final success receipt includes payload count/bytes and verification.
- **Done:** partially written exports are not shown as valid backups.

<a id="nx5-4"></a>

## NX5.4 — validate hostile bundles and build restore preview

- **Prerequisite:** NX5.3. **Read/modify:** core restore planner and archive adapter.
- **Do:** enforce size/count budgets while streaming, not only declared headers.
  Reject `..`, absolute/drive/UNC paths, backslash traversal, symlinks, duplicate
  normalized names, case-fold collisions, unexpected blobs, CRC/hash mismatches,
  oversized expansion and dangling refs. Keep extracted bytes in private staging.
  Show complete proposed ID remapping and new destination/space requirements.
- **Tests:** `NX-B04`: ZIP-slip, symlink, decompression bomb, dishonest sizes,
  duplicate/case-colliding entries, truncated archive, corrupt digest, unsupported
  schema and canceled preview. No writes outside staging and no existing changes.
- **Done:** confirmation applies to this validated plan/hash; replacing the input
  archive invalidates the approval and requires a new preview.

<a id="nx5-5"></a>

## NX5.5 — publish restore with crash recovery and reference remapping

- **Prerequisite:** NX5.4. **Read/modify:** restore use case, platform promotion,
  data transaction and small publication journal from NX2.
- **Do:** copy verified files into unique final paths, commit remapped metadata
  atomically, then finalize the receipt. Journal handles crashes between file
  promotion and database commit. Retry/reconciliation recognizes the same restore
  operation; cleanup touches only newly owned files. Rebuild text indexes later.
- **Tests:** `NX-B05`: fault injection before/after each boundary, cancellation,
  ID collisions across entries/assets/notes/collections/profiles, legacy playback
  bridge, duplicate retry and destination revocation. Existing library/file hashes
  are unchanged; restored references resolve to the intended entry.
- **Done:** no success with missing media, no duplicate restored rows on retry,
  and interrupted restore offers resume/cleanup according to its actual journal.

<a id="nx5-6"></a>

## NX5.6 — restore UI and real cross-device round trip

- **Prerequisite:** NX5.5. **Read/modify:** backup/restore screens, file pickers,
  pt/en strings and usage docs. Show selection, sensitive contents, estimated disk
  use, conflicts, progress/cancel and a final verified summary.
- **Tests:** `NX-B06`: export from desktop, restore on Android via picker, reopen
  offline and compare media hashes, cues, notes, collections and positions; export
  that selection back to desktop. Repeat conflicting restore. Check Windows path
  rules and Android SAF copy behavior; include macOS/Linux smoke evidence.
- **Gate:** NX-B family and existing legacy JSON contracts pass. Document missing
  source handling, large-file support and plaintext bundle privacy without
  implying synchronization, encryption or automatic cloud backup.

**Next:** NX6.1.
