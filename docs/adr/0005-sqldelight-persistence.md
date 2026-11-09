# ADR-0005: SQLDelight persistence and crash recovery

- Status: accepted
- Date: 2026-11-09

## Context

The in-session queue lost all state when the process died. Phase 3 requires a
persistent queue, a durable library, recovery without duplicate tasks, and a clear
separation between removing a library record and deleting a user file.

## Decision

Use SQLDelight 2.4 with SQLite on both targets:

- Android: `AndroidSqliteDriver`, private app database `openyt.db`.
- Desktop: `JdbcSqliteDriver`, database under the platform data directory
  (`$XDG_DATA_HOME/OpenYT/openyt.db`, `%APPDATA%/OpenYT`, or
  `~/Library/Application Support/OpenYT`).

Schema version 1 stores `download_task` (queue snapshot) and `library_entry`
(completed media). Future schema changes use `.sqm` migration files; version 1 has
no migration step.

## Recovery semantics

- On startup the repository marks `QUEUED`, `DOWNLOADING`, and `PROCESSING` rows as
  `INTERRUPTED`.
- Nothing is auto-resumed. The user can retry an interrupted task, which creates a
  new task id and starts a fresh engine job.
- Per-task temporary directories (`.openyt-tmp-*`) are deleted when a download ends
  or is cancelled, and stale directories are removed at app startup.
- Progress is persisted at 10% intervals; terminal states are always persisted.
- Persistence writes are serialized through an unlimited channel, so state
  transitions cannot be reordered by a slow database.
- Duplicate active requests (same URL, kind, and format) return the existing task
  id instead of creating another job.
- At most two downloads run concurrently in this version; queued tasks wait for a
  permit.

## Library semantics

Removing a library entry deletes only the database record. The file is never deleted
automatically; the UI states this explicitly. File deletion is out of scope for this
ADR.

## Risks

- A corrupt database currently disables persistence at startup (logged error, the
  app continues in memory). There is no recovery UI yet.
- Automatic retry/backoff and user-configurable concurrency are not implemented;
  retry is manual.
- Writes are asynchronous; a process kill immediately after enqueue may lose the
  last few progress updates, but active tasks are still marked interrupted if the
  insert was committed.
