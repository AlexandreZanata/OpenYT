# NX2 — durable offline library

Outcome: selected media, artwork and subtitle tracks remain usable with networking
disabled, with an honest readiness indicator and clear storage controls.
Prerequisite: NX1.6. Read ADR-0009, ADR-0014, ADR-0016, ADR-0020 and ADR-0021.

## Fixed behavior

- Store owned assets in app data using generated IDs, separate from Coil's cache
  and original media. Never infer ownership from an arbitrary directory scan.
- Each asset references entry ID, kind, language/source type if applicable,
  revision, byte count, SHA-256 and verified relative locator. Stable entry ID is
  not proof that unchanged content still exists at the path.
- Readiness is relative to the selected required assets: accessible media,
  selected subtitles and optional required artwork. Missing/unverified assets are
  not green. Imported media without requested artwork need not fail a nonexistent
  requirement. Show missing items and a repair/retry action.
- Pin explicitly downloaded offline assets. Initial default asset budget: 512 MiB
  and 128 MiB free-space reserve, adjustable in settings. These are product
  defaults to test, not measured OS guarantees. Never evict pinned assets or user
  media silently. Explain insufficient space and let the user choose cleanup.
- Coil remains the rendering/cache dependency; it is not the durable asset store.

<a id="nx2-1"></a>

## NX2.1 — model assets, revisions and readiness

- **Read/modify:** `LibraryEntry`, repositories, SQLDelight schema/migrations.
  Add focused asset metadata and readiness ports/use cases; keep I/O in adapters.
- **Do:** represent required/optional assets and states missing, pending, verified,
  failed/stale. Model media revision using size/mtime hints plus content digest
  when verified. Invalidate assets/indexes when the associated revision changes.
  Define the structured library-operation failures/recovery actions from the
  overview. Run file checks/hashing off the UI thread through ports; size/mtime
  hints can schedule verification but are not proof of identical content.
- **Tests:** `NX-O01`: missing media, changed bytes at the same path, subtitle-only
  entries, optional vs required artwork, old records with unknown asset state,
  migration and reopen. Never use display name as a content identifier.
- **Done:** readiness is computed from verified evidence, not URL presence.

<a id="nx2-2"></a>

## NX2.2 — confirm engine-produced companion artifacts

- **Prerequisite:** NX2.1. **Read/modify:** `DownloadEvent.Completed`, engine output
  receipts/parsers, desktop/Android completion and coordinator insertion.
- **Do:** add backward-compatible optional artifact receipts. Capture only paths
  explicitly emitted/tagged by the engine for this task: artwork/subtitles plus
  language/type and timeline basis when known. Validate existence and expected
  task-owned roots; copy into the asset store through its API.
- **Tests:** `NX-O02`: output path with quotes/Unicode, forged/out-of-root receipt,
  absent file, multiple language tracks, interrupted completion, legacy Completed
  events. An unrelated neighboring subtitle/image is never attached by guessing.
- **Done:** primary media completion remains truthful even if optional artwork
  fails. Missing required assets leave readiness incomplete with retry available.

<a id="nx2-3"></a>

## NX2.3 — implement owned storage and durable local operations

- **Prerequisite:** NX2.2. **Read/modify:** data/platform I/O implementations and
  SQLDelight; create only the small operation repository needed by asset jobs.
- **Do:** stage, bound, stream, hash, verify and publish assets. Persist operation
  states QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELED/INTERRUPTED, deduplicate retries,
  serialize disk-heavy jobs initially, and reconcile the publication journal after
  crashes. Keep download ownership and its queue intact.
- **Tests:** `NX-O03`: cancel, disk-full, revoked permission, truncated copy,
  crash before/after file promotion and DB commit, restart and explicit retry.
  Inject failures rather than filling the user's real disk.
- **Done:** no success with missing bytes; no duplicate asset on retry; cleanup
  cannot escape the operation's owned staging directory or delete source media.

<a id="nx2-4"></a>

## NX2.4 — fetch artwork and import subtitle assets safely

- **Prerequisite:** NX2.3. **Read/modify:** existing HTTP/image support, thumbnail
  component, Android picker adapters and asset store.
- **Do:** reuse reviewed transports where possible; validate HTTP(S) redirects,
  apply timeout/byte/pixel limits and cancellation. Strip credential-bearing URLs
  from diagnostics/export. Allow explicit SRT/VTT import through host pickers;
  use Android's copy-to-owned-storage pattern. Network fetches require a user
  request or the approved download profile; no idle credential scraping.
- **Tests:** `NX-O04`: offline, redirect loop, oversized/corrupt image, expired
  URL, invalid encoding, revoked URI grant, duplicate request and cancellation.
- **Done:** downloaded artwork survives Coil cache clearing and restart. A failed
  asset fetch never destroys an already verified asset.

<a id="nx2-5"></a>

## NX2.5 — show offline readiness and storage actions

- **Prerequisite:** NX2.4. **Read/modify:** `MediaThumbnail`, library details,
  storage dialog/settings and pt/en strings.
- **Do:** prefer verified local artwork; show per-item missing requirements,
  progress/retry, explicit pin/unpin and asset cleanup preview. Separate original
  media bytes, durable assets and disposable rendering cache in storage totals.
  Removing a library record still leaves original media untouched.
- **Tests:** `NX-O05`: UI state transitions, 320dp phone/desktop, large text,
  keyboard/touch, pt/en, cancel/retry and cleanup confirmation. No green badge
  solely because a remote URL can be displayed while online.
- **Done:** users can understand what is offline and what cleanup will remove.

<a id="nx2-6"></a>

## NX2.6 — prove offline behavior and close milestone 1

- **Prerequisite:** NX2.5. Run NX-O and NX-P contracts plus existing storage/import
  regressions. Download/select assets, restart, disable all networking and clear
  Coil's cache. Open media and display artwork/subtitles on native targets.
- **Tests:** `NX-O06`: changed/missing external media is detected; failures remain
  actionable; pinned assets survive the quota path; storage totals reconcile.
- **Gate:** actual offline evidence on Windows/Linux and Android; macOS smoke
  before release. Document asset ownership, quota and SAF copy behavior. Capture
  screenshots and update milestone status only for the checks actually run.

**Next:** NX3.1.
