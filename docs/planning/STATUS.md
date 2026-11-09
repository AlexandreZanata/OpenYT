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

## Phase 2 completed

- Download action in the UI with video/audio selection and concrete format choice.
- In-session Downloads list with progress, speed, ETA, cancel, dismiss, and
  structured errors; completed files appear in the Library tab.
- Per-task temporary directories (`--paths temp:...`) are removed on completion,
  failure, and cancellation.
- Android real device (arm64): YouTube video download with FFmpeg merge and audio
  extraction to m4a pass; cancellation cleans temporary files; all 5 network tests
  pass.
- Linux desktop: YouTube merge is validated with `ffprobe`, confirming both video
  and audio streams; progress and cancel pass.
- Fixes discovered by real execution: call `FFmpeg.getInstance().init()` in addition
  to `YoutubeDL.init()`, pass `--no-quiet` when using `--print`, and keep per-task
  temp paths.

## Known limitations

- No persistent queue or database yet; tasks and the Library are session-only.
- No pause support (engine only supports cancellation).
- No subtitle download, collections, or media playback yet.
- Only arm64-v8a Android artifacts; no ABI splits.
- Windows/macOS desktop compile is verified in CI, but runtime downloads, JS runtime
  detection, and packaging are not.
- Strings are centralized but not yet backed by Compose resources.
- No i18n locale switching yet (pt-BR is the default; English strings exist).

## Next step (Phase 3)

1. Persist queue and task state in SQLite with versioned migrations and recovery.
2. Persist library entries and distinguish "remove from library" from "delete file".
3. Add configurable concurrency, retry/backoff, and duplicate detection.
4. Add structured diagnostics with redaction.
5. Add release packaging once persistence behavior is verified.

Every claim above must be backed by a command or test recorded in the repository.
