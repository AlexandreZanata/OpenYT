# Release pipeline

Workflow: `.github/workflows/release.yml`.

## Trigger and version

- Runs on every push to `main` and on manual dispatch.
- `gradle.properties` is the single source of truth:
  - `openyt.version` (SemVer, currently `2.8.0`)
  - `openyt.versionCode` (Android integer)
- Every main push rebuilds and **updates** the release for `v<version>`. Bump the
  version before merging to publish a new release; otherwise assets of the current
  release are replaced with the latest main build.

## Artifacts

| Platform | Task | Artifact |
|----------|------|----------|
| Android | `:androidApp:assembleRelease` | `androidApp-release.apk` |
| Linux | `:desktopApp:packageDeb` | `openyt_<version>_amd64.deb` |
| Windows | `:desktopApp:packageMsi` | `OpenYT-<version>.msi` |
| macOS | `:desktopApp:packageDmg` | `OpenYT-<version>.dmg` |

Desktop packages embed yt-dlp, FFmpeg, FFprobe, and Deno through
`desktopApp/build.gradle.kts` (`prepareBundledTools`). The task downloads the
pinned versions below for the packaging host and `appResourcesRootDir` adds them
to the package. At first run the app copies them to a user-writable cache and
restores the executable bit, because native installers do not preserve file
permissions.

| Tool | Version | Source |
|------|---------|--------|
| yt-dlp | 2026.10.26 | `yt-dlp/yt-dlp` release binaries |
| FFmpeg / FFprobe | b6.1.1 | `eugeneware/ffmpeg-static` |
| Deno | 2.9.8 | `denoland/deno` releases |

The publish job also generates `SHA256SUMS.txt` and attaches every artifact to the
GitHub release `v<version>` (marked latest).

## Signing

Android release signing is optional and driven by repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Without them, the workflow produces a **debug-signed** APK for testing. Production
signing is now configured through these repository secrets; the keystore and
passwords are stored outside the repository (`~/.openyt`). Back them up.

Windows and macOS installers are currently unsigned. macOS Gatekeeper will warn;
Windows SmartScreen may warn. Code signing/notarization is a future step and
requires certificates and repository secrets.

## Versioning note

macOS `jpackage` rejects versions whose first component is zero. Use major version
`1` or higher for releases, even for pre-1.0 development builds. Android and Linux
accept zero-major versions, but the shared version must remain compatible with all
packagers.

## Tool requirements

- Linux: `dpkg-deb`/`fakeroot` (provided by the Ubuntu runner).
- Windows: WiX Toolset, installed by the workflow via Chocolatey.
- macOS: `jpackage`/`hdiutil` from the runner JDK.
- All desktop jobs use JDK 21 and the Gradle wrapper.
- Desktop jobs need network access to download the pinned yt-dlp, FFmpeg, and
  Deno binaries during packaging.

## Pre-release validation

Run the real Linux validation against the built `.deb` before merging a release
branch:

```bash
./scripts/validate-linux.sh --scenario all
```

It extracts the package into an isolated prefix, uses isolated `XDG_*` directories,
exercises the headless CLI against real sources, checks playlist layout and
database migration, and captures a GUI screenshot. Use `--offline` to skip network
scenarios. The scheduled/manual `network-integration` CI job runs the real-network
integration tests separately.

## Safety

- The publish job only runs when all builds succeed.
- No release is created from pull requests.
- Artifacts are built on native runners; Windows/macOS packages are not cross-built.
- No automatic version bump; the pipeline publishes the version in
  `gradle.properties`.
