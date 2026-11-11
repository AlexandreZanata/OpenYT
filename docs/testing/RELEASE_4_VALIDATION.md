# OpenYT 4.0.0 validation ledger

Date: 2026-11-11. This ledger separates executed checks from platform limits.
Regression requirements and repeatable commands live in
[VIDEO_REGRESSION_CONTRACTS.md](VIDEO_REGRESSION_CONTRACTS.md) and
[EXPANSION_CONTRACTS.md](EXPANSION_CONTRACTS.md).

## Local execution

- Final deterministic suite: 208 discovered tests, 197 executed successfully and
  11 opt-in scenarios skipped; zero failures/errors. Module totals: core 113,
  engine parsers 18, persistence 33, media adapter 3 and desktop/UI 41.
- Android debug APK and instrumentation APK assembled successfully. This build
  check is separate from running instrumentation.
- Real yt-dlp/FFmpeg loopback contracts passed for merged video, audio extraction,
  MP3/Opus normalization, SRT sidecar, embedded captions, segment and duration
  limits, plus bounded live HLS capture. Output codecs/durations were probed.
- Real FFmpeg processing/tagging and VLC playback passed. The original media bytes
  were preserved; stop, close/reopen, saved position and mini-player size/topmost
  behavior were checked. The screenshot contains decoded video and player controls.
- Six opt-in external-network integration tests passed separately from unit tests.
- The actual Linux DEB was extracted and executed using its bundled JVM/tools.
  Upgrade from 3.1.1 preserved the library and migrated schema 13 to 18. One visible
  launcher remained. Conversion/trim, RSS baseline/new-item/deduplication, failed
  download exit status and persisted CLI output records passed.
- Two real packaged GUI launches reused the existing coordinator and analyzed the
  handed-off URL. Concurrent CLI mutation was rejected while the GUI owned state.
- The Linux validation harness passed; four external-network scenarios were
  explicitly skipped in its offline run. The separate network suite above covers
  external access without mixing it into deterministic tests.
- The Kotlin/JS browser output passed URL/protocol handoff fixtures. This does not
  claim browser-store publication or manual installation in every browser.
- The explicit official yt-dlp update downloaded, checksum-verified and selected
  the cached executable successfully.
- The production APK from candidate run 43032211578 passed `apksigner verify`.
  Certificate SHA-256 matches the pinned release trust certificate:
  `a431d8ac149c67811dcec710a6419916a8944ddf7e1284701ab00d8e235c49bf`.

## Native CI and publication

Candidate [43032211578](https://github.com/AlexandreZanata/OpenYT/actions/runs/43032211578)
built the production APK, Windows MSI/EXE, macOS DMG, Linux DEB/RPM/AppImage/
Flatpak/Arch package and browser handoff. MSI installation/CLI launch, the copied
macOS bundle/CLI and Linux package smoke checks passed. Publication was skipped
because the candidate was outside `main`.

The final [native media candidate 43048343000](https://github.com/AlexandreZanata/OpenYT/actions/runs/43048343000)
passed on Linux, Windows and macOS after the FFmpeg fixes, including all eight
download-option variants and actual bounded live HLS capture. Every host also
generated its inventory from the native binaries.

The [final implementation CI 43048357446](https://github.com/AlexandreZanata/OpenYT/actions/runs/43048357446)
passed Android API 30 instrumentation: 19 discovered tests, 14 executed and five
external-network tests intentionally skipped. This includes actual Media3 PiP,
all 159 provider vectors, enlarged-text storage, D-pad/tablet navigation, bundled
FFmpeg trim/conversion/normalization/tagging and Android XML safety. Screenshots
and HTML reports are in its `android-instrumentation` artifact.

Published assets must also pass
`python3 scripts/verify-release.py v4.0.0 --commit <tested-main-sha>`.
That contract verifies the pinned RSA signature, tag commit, complete package
inventory and every signed checksum against GitHub's uploaded-asset SHA-256
metadata. It does not claim to download and execute every published asset.

## Findings resolved during validation

Real playback exposed a VLC local-path/URI mismatch and a Linux video-output
crash; local paths and an X11 software-output configuration resolved the tested
case. Bounded live capture exposed a crash in the previous Linux FFmpeg binary;
the checksum-pinned GPL 8.1.3 replacement passed the same fixture. Cross-platform
checks exposed line-ending changes in signed fixtures and a DASH fixture working
directory dependency. Signed bytes are now preserved and fixture segments are
generated relative to their dedicated directory. Android processing now initializes
both required library packages and includes Python's transitive shared-library
directory. Completion publishes its terminal state after enqueueing associated
database writes, so an immediate CLI flush/exit cannot omit the library record.
These checks remain automated.

## Explicit limits

Android checks use a KVM emulator. No new physical-device, TV or foldable
certification is claimed. Windows/macOS native CI is separate from manual GUI
validation. Android has the available production signing key; Windows/macOS do
not have Authenticode/notarization certificates. The detached manifest signature
authenticates release bytes and does not replace OS certificates. D4/G1 are closed
as rejected viability decisions in ADR-0023. Future upstream changes remain
possible; the contracts detect regressions in the exercised behavior.
