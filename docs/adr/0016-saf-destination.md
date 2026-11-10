# ADR-0016: SAF destination export

- Status: accepted
- Date: 2026-11-09

## Context

Users need downloads outside app-specific storage. yt-dlp writes filesystem paths,
not SAF content URIs.

## Decision

- Let Android choose an external tree with `OpenDocumentTree` and persist read/write
  permission. Store the tree URI in `app_settings` (schema v6).
- After a download completes, copy the app-owned file into the chosen tree through
  `DocumentsContract`; keep the local copy for library management, sharing, probing,
  and missing-file detection.
- Do not move or delete the app copy. Export failures do not fail the download.

## Consequences

- Files appear in the user's chosen folder without broad storage permissions.
- Storage usage can double for exported downloads.
- Moving the library itself to SAF URIs and importing existing tree contents remain
  future work.
