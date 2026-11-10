# ADR-0018: Automatic GitHub update banner

- Status: accepted
- Date: 2026-11-10

## Context

Users should learn about new releases without visiting Settings or relying on a
backend. GitHub Releases is the only release source.

## Decision

- `UpdateController` performs an automatic check once per app process when the app
  is entered; Settings can trigger manual checks.
- The latest release is fetched from the GitHub REST API and filtered by platform
  asset (APK, DEB, MSI, DMG).
- A dismissible banner above the app content shows the new version, a
  download-and-install action, and the release link.
- Installation remains assisted: the installer is downloaded and opened for OS/user
  confirmation. No silent install.
- No server, account, telemetry, or background polling is introduced.

## Consequences

- Users see new releases immediately on app entry without a backend.
- GitHub API rate limits are avoided by the once-per-process policy; manual checks
  are explicit.
- If the release has no asset for the platform, no banner is shown.
- Full signed auto-update remains future work for Windows/macOS.
