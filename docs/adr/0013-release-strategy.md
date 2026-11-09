# ADR-0013: Native-runner release pipeline

- Status: accepted
- Date: 2026-11-09

## Context

OpenYT ships on Android, Linux, Windows, and macOS. Installers must be produced by
the native platform tooling, and users expect a release for every main update.

## Decision

- Use a dedicated GitHub Actions workflow triggered on every push to `main`.
- Read the version from `gradle.properties` (`openyt.version`); a release is
  identified by `v<version>` and its assets are replaced on subsequent main builds.
- Build Android on Ubuntu, deb on Ubuntu, MSI on Windows (WiX installed in the
  workflow), and DMG on macOS.
- Publish all artifacts plus `SHA256SUMS.txt` through a single release job that
  requires every platform build to succeed.
- Android signing is optional: keystore secrets produce a production APK; otherwise
  the workflow falls back to the debug key for testable artifacts.
- Do not cross-build installers and do not publish from pull requests.

## Consequences

- Every main update yields a downloadable, checksummed release.
- Version bumps are explicit; an unchanged version rolls the release forward.
- Windows/macOS artifacts are unsigned until signing/notarization certificates and
  secrets are configured.
- Auto-update is not implemented; users download new installers manually.
