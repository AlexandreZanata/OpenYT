# Offline library

OpenYT keeps selected companion files beside the app, not in the original media
folder and not in Coil's rendering cache. This document records ownership,
readiness, quota and copy behavior for the durable offline library.

## Owned storage

- Owned assets live under the app data directory in `offline-assets/<entryId>/`.
- Temporary work stays in `offline-assets/.staging/<operationId>/` and is deleted
  after success, failure or cancellation.
- Every record stores the entry ID, asset kind, language/source type, relative
  locator, byte count, SHA-256 digest, media revision and verification timestamps.
- A relative locator is always resolved inside the owned asset root. Cleanup and
  promote operations reject paths that escape it.
- Ownership is never inferred by scanning an arbitrary directory. Removing a
  library record does not delete original user media, and asset cleanup deletes
  only explicitly owned files.

## Asset identity and readiness

- Readiness is derived from verified evidence: the media file, its size/mtime
  hints and the selected companion assets.
- A remote `thumbnailUrl` is only a display fallback. Its presence never marks an
  entry as offline-ready.
- Duration of validity is tied to the media revision. A changed size or timestamp
  makes existing assets stale until re-verified.
- States: `READY`, `PENDING`, `INCOMPLETE`, `STALE`, `UNAVAILABLE`.
- Missing requirements expose recovery actions: relink media, re-verify, fetch
  artwork, import subtitle, retry download or clean stale assets.

## Pinning, budget and cleanup

- Pinned assets are never evicted automatically.
- Defaults are a 512 MiB owned-asset budget and a 128 MiB free-space reserve.
  The reserve is checked before staging; new bytes that would exceed the asset
  budget are rejected with a structured budget failure.
- The budget and reserve values are persisted in app settings and wired live into
  host `JvmOfflineAssetStore` operators via dynamic providers, editable directly
  in the Settings screen via preset chips (`256 MiB`–`2 GiB` budget and
  `64 MiB`–`512 MiB` reserve, clamped to at least `16 MiB`).
- Cleanup is explicit: the UI previews unpinned assets, asks for confirmation and
  removes only owned files plus their records. Pinned assets are excluded even
  when requested by ID.
- Storage totals separate original media bytes, durable owned assets and the
  disposable rendering cache.

## Subtitle import and SAF copy behavior

- Android uses the scoped document picker for SRT/VTT files. The selected URI is
  read with a bounded stream and copied into owned storage; no broad storage
  permission is requested.
- Desktop uses a native file chooser and the same bounded import service.
- A failed or cancelled import leaves previously verified subtitles untouched and
  never deletes the source file.

## Evidence and limits

Verified on Linux x86_64 with networking disabled through a network namespace
(`unshare -rn`, loopback only):

- Owned artwork/subtitles survive database reopen and deleting the rendering
  cache.
- Changed and missing external media are detected as `STALE`/`UNAVAILABLE` with
  actionable recovery.
- Pinned artwork survives a budget breach that rejects a new asset.
- Owned storage totals reconcile with physical file bytes.
- Offline readiness UI renders at 320dp, enlarged text and desktop widths in
  pt-BR and English. Screenshots are in `docs/assets/offline/`.

```
unshare -rn bash -c 'ip link set lo up; \
  HOME=/home/iiii GRADLE_USER_HOME=/home/iiii/.gradle \
  JAVA_TOOL_OPTIONS=-Duser.home=/home/iiii \
  ./gradlew --no-daemon --offline --rerun-tasks :data:desktopTest \
  --tests "*Offline*" --console=plain'
```

Blocked native checks:

- Android device instrumentation is blocked by OEM USB install restrictions on
  the available Xiaomi device. Android compilation and test APK assembly pass.
- Windows and macOS native offline runs are blocked by the absence of those
  hosts. Milestone 1 therefore remains open until the native gate is executed.
