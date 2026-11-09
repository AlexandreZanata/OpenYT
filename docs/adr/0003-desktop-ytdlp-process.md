# ADR-0003: Desktop extraction through external yt-dlp executables

- Status: accepted
- Date: 2026-11-09

## Context

On Linux, Windows, and macOS the official yt-dlp executable is self-contained,
updated frequently, and licensed Unlicense. Bundling it per platform increases
installer size and update lag. FFmpeg is required for merging separate streams and
conversion.

## Decision

Invoke a user-provisioned or app-managed yt-dlp executable through a process port:

- Arguments are passed as a list; no shell interpolation.
- The executable path is configurable and resolved from known locations.
- Version and capabilities are probed before download.
- Progress is parsed from machine-readable output (`--newline`, `--progress-template`).
- Cancellation destroys the process tree and cleans task-scoped temporary files.
- FFmpeg/FFprobe paths are passed explicitly when needed.
- A supported JavaScript runtime (Deno or Node) is detected and passed with
  `--js-runtimes`; EJS is required for complete YouTube format availability.

Do not implement a silent self-updater. A future managed update flow must verify a
checksum/signature and require user action.

## Consequences

- Same engine contract as Android; host differences stay in the desktop module.
- Users on Windows/macOS may need to install yt-dlp/FFmpeg until packaged installers
  ship. This must be documented in the UI.
- Tests use a fake process runner; real-network tests are separate and opt-in.
