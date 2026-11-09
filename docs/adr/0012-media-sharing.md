# ADR-0012: Sharing completed media

- Status: accepted
- Date: 2026-11-09

## Context

Users need to send downloaded videos through WhatsApp, Drive, or other installed
apps. Embeding vendor SDKs would add accounts, permissions, and maintenance burden.

## Decision

- Add a `MediaActions` port for share/open/reveal.
- Android exposes app-owned files with a read-only `FileProvider` and starts the
  system chooser (`ACTION_SEND`/`ACTION_SEND_MULTIPLE`) with
  `FLAG_GRANT_READ_URI_PERMISSION` and `ClipData`.
- MIME types come from the file extension, with `video/*`/`audio/*` fallbacks.
- "Open" uses `ACTION_VIEW` with the same content URI. "Reveal" is unsupported on
  Android and returns false.
- Desktop uses `Desktop.open`/`browseFileDirectory` with an `xdg-open` fallback.
- Never request broad storage permissions for app-owned downloads.

## Consequences

- WhatsApp, Drive, and any handler installed by the user appear through the OS
  chooser without vendor SDKs or credentials.
- The user remains in control of the destination and can cancel.
- Sharing does not work for arbitrary external files or SAF URIs yet; imports and
  SAF export remain future work.
