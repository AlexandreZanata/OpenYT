# ADR-0014: Android SAF import

- Status: accepted
- Date: 2026-11-09

## Context

Users may already have media on the device. Importing through the Storage Access
Framework avoids broad storage permissions and keeps files app-owned for sharing.

## Decision

- Use `ActivityResultContracts.OpenMultipleDocuments` for video/audio selection.
- Copy selected streams into `downloads/imported/` with sanitized, collision-safe
  names; create persisted `LibraryEntry` records with kinds and sizes.
- Imported files are covered by the existing read-only FileProvider and can be
  shared, opened, probed, and marked missing like downloaded files.
- Do not persist temporary content URIs or request legacy storage permissions.
- Choosing an external SAF destination for downloads remains out of scope.

## Consequences

- Import works without storage permissions and is immediately available in the
  library.
- Files are copied, using app storage until a future SAF destination/export flow.
- Imported entries have no remote thumbnail and use the placeholder.
