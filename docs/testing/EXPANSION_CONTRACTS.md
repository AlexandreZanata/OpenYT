# Expansion regression contracts

Version: 4.0.0. These are executable checks, not a guarantee against every future
source change or operating-system failure. Real-network checks remain opt-in.

## Deterministic contracts

- `SystemLanguageTest`: OS Portuguese variants, English fallback, explicit override.
- `MediaPreviewTest`: distinct cached clips for distinct previews and cancellation
  of the platform engine when leaving a preview.
- `DesktopInstanceTest`: exclusive database ownership, validated link/focus handoff,
  and no duplicate GUI coordinator.
- `PlaybackCheckpointTest` / `PlaybackStoreTest`: completed-file reset, bounded
  mini-player dimensions, queue/position persistence across database reopen.
- `AutomationConcurrencyTest` / `SubscriptionControllerTest`: concurrent duplicate
  checks, first-run baseline, persisted seen IDs, retry intervals, invalid rules.
- `RssFeedReaderTest`: RSS/Atom enclosure parsing and rejection of external entities.
- `ConfirmedEngineOutputTest`: a moved subtitle resolves only its declared relative
  path; missing outputs cannot be replaced by an unrelated directory scan.
- `VerifiedReleaseTest`: production signature verification, changed manifest,
  changed signature, corrupted installer, duplicate checksums, unsafe file names.
- `DownloadNotificationTest`: restored history remains silent; new terminal states
  notify once, including very fast downloads.
- `ParserRobustnessTest`: 2,000 seeded malformed inputs across external parsers.
- Existing schema migrations, queue, UI/storage and final-output contracts remain
  in [VIDEO_REGRESSION_CONTRACTS.md](VIDEO_REGRESSION_CONTRACTS.md).

## Real local media

```sh
OPENYT_RUN_LOCAL_ENGINE_TESTS=1 ./gradlew :desktopApp:test --tests '*DesktopYtDlpLocalTest'
OPENYT_RUN_MEDIA_TESTS=1 ./gradlew :media-ffmpeg:desktopTest
OPENYT_RUN_MEDIA_TESTS=1 ./gradlew :desktopApp:test --tests '*DesktopMediaTaggerTest'
OPENYT_RUN_PLAYBACK_TESTS=1 env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE xvfb-run -a \
  ./gradlew :desktopApp:test --tests '*DesktopPlaybackLocalTest'
```

The engine test serves synthetic media over loopback and runs real yt-dlp/FFmpeg
for merge, audio extraction, MP3/Opus normalization, SRT sidecar, embedded captions,
segments and duration limits. FFmpeg tests probe the codecs and trimmed duration,
and compare the original bytes. The playback test decodes a real video through
libvlc, asserts always-on-top sizing, saves a position and reopens the session.
Its screenshot is written to `desktopApp/build/validation/mini-player.png`.
Set `OPENYT_FFMPEG` and `OPENYT_FFPROBE` to the prepared bundled executables when
they are absent from the host; CI does this explicitly. Opt-in flags and executable
paths are Gradle test inputs, so enabling a real-media contract cannot reuse an
earlier report in which it was skipped. Missing tools fail an explicitly enabled
contract instead of silently skipping it.

## Packaged Linux and browser handoff

```sh
./gradlew :desktopApp:packageDeb :browserExtension:packageHandoff releaseSbom
python3 scripts/validate-expansion.py <current.deb> [previous.deb]
node scripts/validate-browser-handoff.cjs
scripts/validate-linux.sh --no-build --offline
```

The package contract extracts the actual installer, runs its bundled JVM/tools,
upgrades a prior database, verifies one launcher definition, converts/trims media,
and drives RSS baseline/new-episode/deduplication/failure scenarios through the
same CLI use cases. It does not modify the host installation. The browser contract
executes compiler output with browser API fixtures; browser-store publication and
manual browser installation are separate from this contract.

## Android

```sh
./gradlew :androidApp:connectedDebugAndroidTest \
  -Popenyt.testAbi=x86_64 -Popenyt.testApplicationIdSuffix=.regression
```

CI provides an API 30 emulator with KVM. Instrumentation renders provider vectors,
checks storage recovery with enlarged text, opens actual Media3 playback and enters
system PiP. A separate Android FFmpeg test trims, converts, normalizes and tags a
real fixture, re-probes output and verifies the original bytes. The platform RSS
parser is checked on Android too. Android network tests require the separate instrumentation argument
`-Pandroid.testInstrumentationRunnerArguments.openyt.network=1`.
The local USB installation was declined; no claim of a physical-device test is made.

## Native release gates

The release workflow builds APK/DEB/RPM/MSI/EXE/DMG and the browser handoff. Linux
also builds AppImage, Flatpak and an Arch binary package from its checksum-pinned
recipe. Native smoke steps execute the installed MSI launcher and the copied macOS
bundle. A branch dispatch builds without publishing; only `main` publishes.
CI logs and downloadable reports distinguish pass, failure and skip. Missing
Authenticode/notarization certificates are documented in ADR-0022.
