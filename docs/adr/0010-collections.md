# ADR-0010: Library collections

- Status: accepted
- Date: 2026-11-09

## Context

The library needed a way to organize media beyond search and duplicate detection.
Collections must not duplicate files or couple membership to the queue.

## Decision

- Model collections as a many-to-many relation: `collection` rows hold names and
  `collection_entry` maps collections to library entry ids (schema v5, migration
  `4.sqm`).
- `LibraryController` is the in-memory owner of collections and membership,
  persisted through `DownloadRepository` using a serialized write channel.
- The UI filters the library by collection; assignment is a checkbox list per
  entry. Duplicate collection names are rejected case-insensitively.
- Deleting a collection or unassigning an entry only removes metadata. Deleting a
  library entry also deletes its memberships; files are never touched.

## Consequences

- Collections are durable, testable, and independent of the download queue.
- No rename UI yet; duplicate names are rejected and delete/recreate is available.
- Sorting and missing/duplicate filters compose with the collection filter.
