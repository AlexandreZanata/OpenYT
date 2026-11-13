# NX6 — assisted library recovery

Outcome: reconnect moved media without losing its identity or authored context,
and review content duplicates safely. Prerequisite: NX5.6. Read ADR-0008,
ADR-0014/0016, bulk-file operations and the entry-ID playback bridge from NX3.

## Fixed behavior

- Start with a read-only diagnostic and explicit selected scope. No automatic
  disk-wide scan, broad Android storage permission or symlink traversal.
- A full matching SHA-256 plus size proves candidate content identity; filename,
  size and timestamps alone are suggestions requiring manual confirmation.
  A missing original without a stored hash cannot be called a verified match.
- Desktop scans user-selected roots. Android uses explicit file picking and
  copies through the existing app-owned SAF import pattern. Do not replace an
  entry's file path with a temporary `content://` URI.
- Keep the same entry ID and associated assets, notes, tags, collections, study
  state and playback checkpoint. A confirmed different-content replacement is
  explicitly flagged; reindex and warn that old timed annotations may be stale.
- No automatic media deletion, duplicate merging or content replacement. Offer
  record-only cleanup separately. Take/recommend a verified NX5 backup before
  applying recovery to a user's important library; never fake a backup result.

<a id="nx6-1"></a>

## NX6.1 — build a read-only health report

- **Read/modify:** focused core diagnostic models/use case and I/O ports, existing
  scanner/storage adapters, asset/index state.
- **Do:** classify available, missing, permission-lost, changed-content and unknown
  entries. Distinguish revoked external permission from absent bytes. Show counts
  without exposing full paths in shared logs. Do not mutate the library in a scan.
- **Tests:** `NX-R01`: unchanged file, moved file, permission loss, equal-size
  changed bytes, stale assets, cancellation and external drive disconnected.
- **Done:** report states are supported by observations and contain no destructive
  side effects. Unknown is not silently considered missing or healthy.

<a id="nx6-2"></a>

## NX6.2 — find bounded candidates and verify content hashes

- **Prerequisite:** NX6.1. **Read/modify:** platform root picker/scanner, NX2 jobs
  and content-hash adapter. Reuse cached hashes only for verified revisions.
- **Do:** filter by cheap metadata, then stream hashes with concurrency one and
  cancellation. Respect root boundaries, unreadable directories and total scan
  limits. Android picks files explicitly instead of recursive SAF-tree assumptions.
- **Tests:** `NX-R02`: two distinct equal-size files, renamed identical bytes,
  symlink escape, cyclic directories, changed file during hashing, huge file,
  unavailable root and cancel. A failed/partial hash cannot prove identity.
- **Done:** verified match versus heuristic candidate are different types/UI labels.

<a id="nx6-3"></a>

## NX6.3 — prepare a confirmed relink plan

- **Prerequisite:** NX6.2. **Read/modify:** pure relink planner and playback state
  ports, recovery journal/snapshot boundary.
- **Do:** display old locator, candidate, proof/confidence, affected annotations
  and any required import copy. Multiple candidates require explicit choice.
  If the item is playing, offer an explicit stop before changing it; do not move
  an open native handle. Revalidate source revision/permissions at apply time.
- **Tests:** `NX-R03`: active playback, stale preview, ambiguous candidates,
  manual non-hash match, canceled confirmation and backup failure. A changed plan
  requires a new confirmation; no automatic record/path changes during preview.
- **Done:** a user can see the exact action and data that will be preserved/stale.

<a id="nx6-4"></a>

## NX6.4 — apply relinking atomically and preserve context

- **Prerequisite:** NX6.3. **Read/modify:** data transaction, Android owned-copy
  adapter, journal, entry-ID playback bridge and invalidation hooks.
- **Do:** publish/verify any imported copy, update the existing entry locator in a
  transaction, preserve references, invalidate only content-dependent derived data
  when appropriate. Journal retries are idempotent. Original candidate bytes and
  unrelated records are never overwritten/deleted.
- **Tests:** `NX-R04`: move/relink/restart retains entry ID, notes, collection,
  study state and position; changed content marks timed data stale; failures at
  copy/promote/DB boundaries leave a recoverable operation. Windows player can
  close, relink and reopen without holding the old file handle.
- **Done:** media is playable from its new locator and no reference points to a
  fabricated new entry. Restoring permission alone does not create duplicates.

<a id="nx6-5"></a>

## NX6.5 — deliver advisory duplicate review and recovery UI

- **Prerequisite:** NX6.4. **Read/modify:** shared recovery/health/duplicate views
  and grouped pt/en strings. Extend the existing advisory duplicate feature.
- **Do:** group exact-content duplicates separately from name/size suggestions;
  show which records share a file and where annotations differ. Allow reveal/open,
  explicit relink and record-only removal with annotation export/confirmation.
  Do not add physical deletion or auto-merge as a shortcut.
- **Tests:** `NX-R05`: same path vs same bytes/different paths, multiple records
  with notes, ambiguous match, empty/canceled scan, keyboard/touch, large text,
  pt/en. Preview always says whether any owned copies will be created.
- **Done:** a user can finish recovery without being asked to resolve raw paths
  or hashes manually, while uncertain choices remain clearly uncertain.

<a id="nx6-6"></a>

## NX6.6 — prove recovery and close milestone 3

- **Prerequisite:** NX6.5. Run NX-R, NX-B, storage/removal and native playback/file
  action regressions. `NX-R06`: move a fixture outside the library, detect it missing, find
  and confirm its match, relink, restart offline, play at a saved note/checkpoint.
- **Gate:** Windows Unicode paths/open/reveal/close, Linux/macOS path handling and
  Android picker-copy work on real hosts. Verify original hashes and record-only
  removal semantics. Document exact-vs-heuristic matching and backup guidance.

**Next:** NX7.1.
