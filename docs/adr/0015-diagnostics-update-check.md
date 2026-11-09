# ADR-0015: Redacted diagnostics and release update check

- Status: accepted
- Date: 2026-11-09

## Context

Support needs platform/engine information without exposing credentials or full local
paths. Users also need a safe way to discover new releases.

## Decision

- `DiagnosticsProvider` returns app version, platform, engine, redacted paths,
  counts, and tool availability. `DiagnosticsRedactor` removes URL credentials,
  queries/fragments, and replaces the home directory with `~`.
- The formatted snapshot is copyable through a platform clipboard.
- `UpdateChecker` queries the GitHub latest release, compares SemVer, and exposes a
  release URL. It runs only on explicit user action.
- Automatic download/install is not implemented; installers are unsigned or
  debug-signed, so silent updates would be unsafe.

## Consequences

- Diagnostics are safe to paste into issues.
- Update discovery is assisted, not silent. Users download installers/releases
  manually.
- Network failures surface as "no update found"; a richer error state is future work.
