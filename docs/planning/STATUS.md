# Project status

Updated: 2026-11-09

## Completed

- Phase 0 viability, with real execution evidence:
  - Linux: metadata, download, integrity, progress, EJS requirement.
  - Android real device: metadata (archive.org and YouTube), 332 MB download,
    progress, cancel, and end-to-end UI inspection.
- Phase 1 foundation:
  - Gradle Kotlin DSL, version catalog, wrapper pinned to 8.14.4.
  - Modules: `core`, `engine-ytdlp`, `ui`, `androidApp`, `desktopApp`.
  - Pure domain/ports (`DownloadEngine`, structured failures, typed events).
  - URL validation and `InspectMediaUseCase` with unit tests.
  - yt-dlp JSON/error/progress parsing with unit tests.
  - Android engine backed by youtubedl-android (QuickJS bundled).
  - Desktop engine driving an external yt-dlp binary with argument lists.
  - Shared Compose UI (pt-BR strings, black/white theme, adaptive navigation).
  - CI: Linux unit/build job and cross-OS desktop compile matrix.

## Known limitations

- No persistent queue or database yet; downloads are not started from the UI.
- No download action in the UI; only inspection is wired.
- Android YouTube metadata is verified; YouTube download/merge is not yet verified.
- Only arm64-v8a Android artifacts; no ABI splits.
- Windows/macOS desktop compile is verified in CI, but runtime downloads and
  packaging are not.
- Strings are centralized but not yet backed by Compose resources.
- No i18n locale switching yet (pt-BR is the default; English strings exist).

## Next step (Phase 2)

1. Add the download action and format selection to the UI.
2. Verify Android YouTube download with merge and the bundled QuickJS/EJS path.
3. Add persisted queue and SQLite with migrations (Phase 3).
4. Add structured diagnostics with redaction.
5. Add release packaging once runtime behavior is verified.

Every claim above must be backed by a command or test recorded in the repository.
