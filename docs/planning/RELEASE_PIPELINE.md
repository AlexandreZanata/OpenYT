# Release pipeline

Workflow: `.github/workflows/release.yml`.

## Trigger and version

- Runs on every push to `main` and on manual dispatch.
- `gradle.properties` is the single source of truth:
  - `openyt.version` (SemVer, currently `0.6.0`)
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

The publish job also generates `SHA256SUMS.txt` and attaches every artifact to the
GitHub release `v<version>` (marked latest).

## Signing

Android release signing is optional and driven by repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Without them, the workflow produces a **debug-signed** APK for testing. It is not
suitable for public production distribution.

Windows and macOS installers are currently unsigned. macOS Gatekeeper will warn;
Windows SmartScreen may warn. Code signing/notarization is a future step and
requires certificates and repository secrets.

## Tool requirements

- Linux: `dpkg-deb`/`fakeroot` (provided by the Ubuntu runner).
- Windows: WiX Toolset, installed by the workflow via Chocolatey.
- macOS: `jpackage`/`hdiutil` from the runner JDK.
- All desktop jobs use JDK 21 and the Gradle wrapper.

## Safety

- The publish job only runs when all builds succeed.
- No release is created from pull requests.
- Artifacts are built on native runners; Windows/macOS packages are not cross-built.
- No automatic version bump or auto-update mechanism exists yet.
