# CI plan

Current workflow: `.github/workflows/ci.yml`.

## Active checks

Release workflow: `.github/workflows/release.yml`. It runs on `main` and manual
dispatch, builds Android/Linux/Windows/macOS artifacts on native runners, and
publishes the `v<version>` release with a signed checksum manifest and per-host
CycloneDX inventories. Branch dispatches cannot publish. See `RELEASE_PIPELINE.md`.

| Job | Runner | Command | Purpose |
|-----|--------|---------|---------|
| `build` | ubuntu | `:core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :desktopApp:packageDeb` | Deterministic unit/persistence tests, parser tests, APK/test APK build, and Linux `.deb` package |
| `desktop-compile` | ubuntu/windows/macos | `:desktopApp:compileKotlin` | Cross-OS compile signal for the desktop host |

The build also checks `media-ffmpeg`, compiled Kotlin browser handoff, packaged
Linux migration/RSS/GUI handoff and real local media processing/VLC playback.
The Android KVM emulator job runs actual PiP, provider vector rendering,
enlarged-text storage, D-pad/tablet, FFmpeg processing/tagging and RSS contracts.
Native Windows/macOS checks include queue/parser/persistence contracts.

Real-network tests in `desktopApp/src/test` and `androidApp/src/androidTest` are
opt-in and never run in CI by default:

- Desktop: requires `OPENYT_RUN_NETWORK_TESTS=1` and a yt-dlp binary.
- Android: requires a connected device/emulator with network access.

## Optional future checks

1. Android lint and Kotlin formatting checks.
2. Dependency/license verification with a pinned allow-list.

CI must not become the only place where a change is verified. Failures block merge;
skipped checks are not evidence.
