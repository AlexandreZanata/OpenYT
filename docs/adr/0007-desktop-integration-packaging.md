# ADR-0007: Desktop integration and packaging

- Status: accepted
- Date: 2026-11-09

## Context

The desktop host needs native capabilities that the shared Compose UI cannot use:
a folder chooser, dropped-link intake, keyboard shortcuts, and distributable
installers. The SQLDelight JDBC driver also requires JDK modules that jpackage does
not include by default.

## Decision

- Expose folder selection through the `FolderPicker` port in `core`; the desktop
  host implements it with a Swing `JFileChooser` (directories only, writability
  checked) and persists the chosen path in `app_settings`.
- Install an AWT `DropTarget` on the Compose window. Dropped text/URI-list content is
  parsed by `ShareTextParser`; only validated http(s) URLs are forwarded.
- Shortcuts are split: Ctrl+1…4 are handled in shared UI (also useful with hardware
  keyboards on Android); Ctrl+Q is handled by the desktop window and shuts down the
  engine and app scope.
- Package with Compose Desktop `nativeDistributions`. Targets: deb, rpm, msi, dmg.
  Only the Linux `.deb` is built and validated in this phase.
- Include the `java.sql` module in the packaged runtime. Without it, the JDBC driver
  fails at startup and persistence silently falls back to memory.
- Pin `packageVersion` to `0.1.0` until a release-versioning process exists.

## Consequences

- The desktop app has a native folder chooser, dropped-link intake, and keyboard
  navigation without platform code leaking into `ui`.
- Dropped local files are not imported yet; only links are accepted.
- Packaging requires a JDK with `jpackage`; Linux deb validation also uses
  `dpkg-deb`/`fakeroot` tooling.
- Windows/macOS artifacts remain unvalidated until built on their native runners.
