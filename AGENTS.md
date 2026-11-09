# Working on ClipBaú

Read `docs/architecture/OVERVIEW.md` and the relevant ADRs before changing modules.
The user request defines the authorized scope; this file defines engineering rules.

## Non-negotiable constraints

- Product code is Kotlin only. Android and desktop share logic through Kotlin
  Multiplatform.
- Never reimplement yt-dlp, FFmpeg, or media codecs. They are external dependencies.
- No backend, cloud service, account system, or remote download server.
- No Rust, Flutter, React Native, Electron, or Node/Python services written for the
  product. Python/JS runtimes are allowed only as required by yt-dlp.
- Do not add a dependency without a concrete need and a license/security check.

## Layering

- `core` is pure Kotlin: domain models, use cases, ports. No Compose, Android SDK,
  yt-dlp, FFmpeg, filesystem, database, or process APIs.
- `ui` contains shared Compose UI. It depends on `core` and must not invoke process
  or filesystem APIs directly.
- Host apps (`androidApp`, `desktopApp`) implement platform ports and inject them.
  No screen or ViewModel may build shell commands or know engine arguments.
- The download engine is replaceable and always accessed through `DownloadEngine`.

## Correctness and evidence

- Run the smallest meaningful verification for every change. Never claim a test,
  build, install, or download succeeded without executing it.
- Do not hide platform limitations. If a source or feature does not work on a target,
  document it in `docs/planning/PHASE_0_VIABILITY.md` or a new ADR.
- Do not present a feature as complete when it is a stub.
- Keep real-network integration tests separate from deterministic unit tests.
- Use structured errors; never surface raw Python tracebacks to users.

## Security and privacy

- Pass process arguments as a list. Never build shell command strings.
- Validate URLs, sanitize file names, and prevent path traversal.
- Logs must not contain cookies, tokens, credentials, or full local paths when they
  are not needed for diagnosis.
- Never auto-delete user files when a library record is removed.

## Process

- Keep commits atomic and describe the behavior change.
- Update the relevant ADR when a durable architectural decision changes.
- Code, identifiers, docs, and commit messages in English; user communication in
  Brazilian Portuguese.
- Do not modify reference repositories (for example Abastevo).
- Do not publish releases or perform destructive remote operations without explicit
  authorization.
