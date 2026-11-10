# ADR-0017: Assisted update installation

- Status: accepted
- Date: 2026-11-09

## Context

The app checks GitHub releases but only linked to them. Silent self-update is unsafe
while Windows/macOS artifacts are unsigned and Android requires package-installer
confirmation.

## Decision

- Extend the release parser with asset download URLs.
- The update card downloads the platform asset (APK/MSI/DMG/DEB) and opens the OS
  installer; the user confirms installation.
- Android production signing uses a stable keystore so future APKs can update the
  installed app.
- Never install silently or execute downloaded installers without user action.

## Consequences

- Updates are one click plus OS confirmation.
- Signature mismatch or missing certificates can prevent installation; the UI reports
  that the installer could not be started.
- Full background auto-update remains out of scope until all platforms are signed.
