# Phase 0 — technical viability

Date: 2026-11-09
Scope: prove that yt-dlp can run behind the intended architecture on Android and
Linux, and identify blockers before building UI.

This document records what was actually executed. Items marked **pending** are not
claims of support.

## Reference review

Abastevo (commit `e051925`, inspected read-only) uses a four-module Kotlin/Compose
Android app (`app`, `domain`, `application`, `data`) with Hilt, Room, WorkManager,
a version catalog, ADRs, and a phased delivery process. ClipBaú adopts the layering
and evidence discipline, not the backend or Android-only assumptions. No code was
copied and the repository was not modified.

## Environment used

| Item | Value |
|------|-------|
| OS | Linux x86_64 |
| JDK | Temurin 21 |
| Android SDK | platform 36, build-tools 36.0.0 |
| Real device | Xiaomi 2311DRK48G, Android 16 (API 36), arm64-v8a |
| Desktop yt-dlp | 2026.10.26 standalone |
| FFmpeg | 6.1.1 (system) |
| Node (desktop EJS test) | v26.3.1 |

## Evidence — Linux desktop

Commands were run directly against the official yt-dlp 2026.10.26 binary.

| Check | Result |
|-------|--------|
| Metadata (`-J`) from archive.org public-domain sample | PASS — "Big Buck Bunny", 3 formats |
| Full download (`worst`) | PASS — 44.76 MiB final file |
| Final file integrity (`ffprobe`) | PASS — duration 596.48 s, 46,935,223 bytes |
| YouTube metadata (public test video) | PASS — 24 formats |
| YouTube format 18 without JS runtime | FAIL — warning: no supported JS runtime; format unavailable |
| YouTube format 18 with `--js-runtimes node` | PASS |
| YouTube `bestvideo+bestaudio` with Node | PASS — formats 395+251 (merge required) |

Conclusion: the desktop path works. EJS is mandatory for complete YouTube support;
Node >= 22 or Deno >= 2.3 satisfy it. The official standalone binary already ships
the EJS scripts; only a runtime is needed.

## Evidence — Android

`io.github.junkfood02.youtubedl-android` 0.18.1 (published 2025-11-16) was inspected
at source level:

| Property | Finding |
|----------|---------|
| Bundled Python | 3.12.11 (satisfies yt-dlp >= 3.11) |
| Bundled yt-dlp | 2025.11.12 |
| Bundled FFmpeg | 7.1.1 |
| JS runtime | QuickJS (`libqjs.so`); the wrapper adds `--js-runtimes quickjs:<path>` |
| yt-dlp update | `updateYoutubeDL()` downloads a release from GitHub |
| ABIs | arm64-v8a, armeabi-v7a, x86, x86_64 |
| License | GPL-3.0 |

On-device execution of metadata, download, progress, cancel, and failure handling is
**pending** and tracked in Phase 0 acceptance. No Android support is claimed until
the debug APK runs on the connected device and the executed yt-dlp version is
recorded.

## Viability matrix

| Area | Android | Linux | Windows | macOS |
|------|---------|-------|---------|-------|
| yt-dlp runtime | Bundled Python 3.12 (wrapper) | External binary — verified | External binary — pending | External binary — pending |
| Current yt-dlp | 2025.11.12 bundled + explicit update | 2026.10.26 verified | managed by user/app | managed by user/app |
| EJS / JS runtime | QuickJS bundled (needs device proof) | Node/Deno — verified | Node/Deno — pending | Node/Deno — pending |
| FFmpeg merge | Bundled 7.1.1 | System 6.1.1 — verified | external — pending | external — pending |
| Process isolation | In-process Python via wrapper | ProcessBuilder — designed | ProcessBuilder — designed | ProcessBuilder — designed |
| Progress/cancel | Wrapper `YoutubeDLProcess` + process destroy | stdout parsing + process tree kill | same code path, untested | same code path, untested |
| Storage | SAF / app-specific dirs | user-selected directory | user-selected directory | user-selected directory |
| Packaging | APK/AAB, arm64 first | JAR + deb | MSI/EXE | DMG/PKG |
| License | GPL-3.0 (wrapper) | GPL-3.0 app, yt-dlp Unlicense | GPL-3.0 app | GPL-3.0 app |

## Risks and mitigations

1. **Bundled yt-dlp is older than current YouTube requirements.** Mitigation: expose
   an explicit update action, record the executed version in diagnostics, and keep
   the engine replaceable. Never claim YouTube support from the bundle alone.
2. **EJS packaging on Android is unconfirmed.** QuickJS is present, but yt-dlp-ejs
   files must be verified on device. If formats are missing, either ship the ejs
   package in the app Python path or document the limitation and test alternatives.
3. **GPL-3.0 obligations.** Mitigation: project licensed GPL-3.0, notices kept,
   source available.
4. **APK size.** Native Python/FFmpeg/QuickJS are large. Mitigation: arm64-only for
   now, ABI splits later.
5. **Android background execution limits.** Mitigation: use user-initiated data
   transfer jobs / foreground service in Phase 3; never fake pause support.
6. **Scoped storage.** Mitigation: SAF tree URIs; download to app-owned staging,
   then publish atomically; never assume arbitrary filesystem paths.
7. **Windows/macOS unverified.** Mitigation: isolate process invocation behind a
   port and validate on native runners before claiming support.

## Acceptance status

- Linux metadata, download, integrity, EJS: **PASS** (executed).
- Android runtime integration: **PASS/PENDING** (updated by the device PoC commit).
- Windows/macOS: **NOT STARTED**.
