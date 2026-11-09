# ClipBaú

Local-first, multiplatform media downloader and library manager built with Kotlin.

ClipBaú lets a user paste or share a media URL, inspect available formats, download
authorized content, and organize it in a local library. There is no account and no
backend: the app runs entirely on the device.

> Status: Phase 0 (viability) and Phase 2 (download flow) validated on Linux and a
> real Android device; Phase 1 foundation complete. Persistent storage (Phase 3) is
> not implemented. Only features listed under "Current status" are implied.

## Stack

| Concern            | Choice                                                |
|--------------------|-------------------------------------------------------|
| Language           | Kotlin (product code)                                 |
| Shared logic       | Kotlin Multiplatform (`jvm`, `android`)               |
| UI                 | Compose Multiplatform (Android + desktop)             |
| Concurrency        | Kotlin Coroutines + `StateFlow`                       |
| Build              | Gradle Kotlin DSL + version catalog                   |
| Serialization      | kotlinx.serialization                                 |
| Extraction engine  | yt-dlp (external dependency; never reimplemented)     |
| Conversion         | FFmpeg (external dependency)                          |
| Distribution       | GitHub Actions                                        |

## Supported platforms

- Android (primary, `minSdk 26`) — verified on a real arm64 device during Phase 0.
- Linux desktop — verified during Phase 0.
- Windows/macOS — planned; not yet validated.

## Repository layout

```text
androidApp/     Android host application
desktopApp/     Desktop (Compose JVM) host application
core/           Pure Kotlin Multiplatform domain + application layer
engine-ytdlp/   Shared yt-dlp output/error/progress parsing
ui/             Shared Compose Multiplatform UI and design system
docs/           Architecture, ADRs, planning, product, security, testing
```

`core` must never depend on Android SDK, Compose, yt-dlp, FFmpeg, or any I/O
framework. `ui` may depend on `core`, never the reverse. Host applications wire
platform implementations into the shared use cases.

## Build

Requirements:

- JDK 17+ (JDK 21 recommended)
- Android SDK with platform 36 and build-tools 36
- `ANDROID_HOME` or `local.properties` with `sdk.dir`
- yt-dlp and FFmpeg on `PATH` for real desktop downloads (not needed to build)

```bash
./gradlew :core:allTests
./gradlew :androidApp:assembleDebug
./gradlew :desktopApp:run
```

Install the debug APK on a connected device:

```bash
./gradlew :androidApp:installDebug
```

## Current status

- Paste a URL, inspect metadata/formats, choose video or audio, download with
  progress, cancel, and see completed files in the session Library.
- Phase 0 viability report: `docs/planning/PHASE_0_VIABILITY.md`
- Project status and limitations: `docs/planning/STATUS.md`
- Architecture and module rules: `docs/architecture/OVERVIEW.md`
- Decisions: `docs/adr/`

## Licensing

ClipBaú is distributed under GPL-3.0. This is required by the Android yt-dlp
wrapper (`io.github.junkfood02.youtubedl-android`, GPL-3.0). yt-dlp itself is
Unlicense; FFmpeg licensing depends on the build. See `THIRD_PARTY_NOTICES.md`.

ClipBaú does not bypass DRM or access restrictions and must only be used to obtain
content the user is authorized to download, in accordance with the source's terms.
