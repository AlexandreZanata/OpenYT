# Next expansion validation ledger

Date: 2026-11-14. Branch: `codex/complete-library-expansion`.
Status: **Linux/Windows/Android local verification passed; final GitHub CI pending.**
This is not a release certification or physical-device performance benchmark.

## Recovered baseline

The previous work ended on `b1a4bcafde626610884d779757e9d7019a9b219a`, PR #63,
after M2/M3 had already merged. PR #63's first CI attempt failed in the existing
Windows shortcut/window probe, before its new offline contract. The failed job's
logs and artifact 14070807691 were read. Bugbot found no actionable NX2.6 product
regressions. A failed-job retry of run 43492611769 passed Linux/build, Windows
MSI/EXE, macOS and Android emulator; PR #63 was merged as
`f132e6ee8f1ba0681cee100109b819adde85830e`.

Local baseline compilation succeeded. The first baseline Android run had 28 cases, five intentional network skips and
one large-screen activity-launch timeout. Final API 26/36 results are recorded
below. No physical Android device is claimed.

## Executed focused checks

- Core/data subscription rule contracts passed: legacy JSON defaults, filter
  validation, distinct Wi-Fi/unmetered decisions, unknown metadata, caps/backlog
  across restart, immutable profiles, preview without writes, fair inspection
  continuation, compact filtered history and confirmed replay.
- Queue execution-gate tests exercise actual coordinator dispatch with changing
  injected conditions: delayed start, manual-work fairness and pause on charging
  loss. These are deterministic tests, not physical network/battery measurements.
- Real external FFmpeg tests passed on Linux: portrait dimensions/codecs/duration,
  visible caption pixels on a black fixture, cover output, private preview,
  source hash preservation, idempotent retry and injected publication failures.
- SQLite clip tests passed: schema upgrade/reopen, running→interrupted recovery,
  retained recipes and successful receipt/library publication.
- Three shared Compose UI contracts passed: read-only preview plus explicit replay,
  profile refresh, partial batch success/retry, and narrow pt-BR/large-text controls.
- AndroidClipContractsTest passed against the actual packaged Android FFmpeg,
  Android media probe, SQLite journal, durable asset store and real Media3 playback.
  API 26 reports preciseClips/portrait/burnCaptions/covers/durableBatches/previews=true.
- AndroidBackgroundSubscriptionTest passed using real WorkManager with no visible
  activity: opt-in, actual loopback RSS fetch, persisted app-open-only queue and
  unique periodic cancellation. Its initial failing fixture bound IPv6 while
  requesting IPv4; the fixture was corrected to bind 127.0.0.1 explicitly.

## Review corrections

Bugbot's M4 review identified seven actionable issues. Corrections add queued
constraint snapshots/revalidation, fair inspection continuation, explicit replay
approval, CLI adapter injection, profile refresh, atomic derived-entry/receipt
publication, and ownership-proof preservation across repeated cover failures.
The added contracts cover these behaviors. A second pass found a cover retry
publication gap, UI metadata overwrite and anamorphic aspect distortion.
`ClipCoverPublisher`, committed-row reload and SAR-aware normalization correct
those defects; actual one-cover-budget, retained-author and FFmpeg DAR/pixel
contracts passed. Bugbot re-reviewed those corrections with no remaining findings.

## Native environments and pending gates

- Linux: real bundled tools, libVLC and Xvfb are available. The first combined run
  found environment-contaminated locator tests and pre-activation samples included
  in A/B telemetry. Locator tests now inject their environment; loop measurement
  excludes inactive playback and retains its one-second tolerance. The focused rerun and final combined suite passed; commands are recorded below.
- Windows: existing Docker VM `tbg-win11`, interactive job agent, JDK 17/21,
  Gradle 8.14.4 and WiX. Native builds use an isolated test checkout. The real
  desktop shortcut opened the actual application in 2.11 seconds in a measured
  run. The harness now uses monotonic time, refreshed process handles and an
  actual AWT application frame, rejecting error dialogs. An existing test-package
  identity caused MSI 1638; only that OpenYT installation was removed, retaining
  app data. Final MSI/EXE/native clip contracts passed (details below).
- Android target: API 36 x86_64 emulator `emulator-5588`; a separate API 26 test
  emulator `OpenYT-NX-API26-20261114` uses an already installed system image. Emulator
  results do not establish physical-device battery, thermal or ASR memory budgets.
- Internet extraction tests are separately opt-in. No skipped network test is
  counted as a passed real-source download.

## Combined Linux evidence

Executed on Linux x86_64 with bundled yt-dlp/FFmpeg/FFprobe, system VLC/libvlc,
JDK 21, Gradle 8.14.4 and Xvfb:

```bash
env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE OPENYT_RUN_LOCAL_ENGINE_TESTS=1 OPENYT_RUN_MEDIA_TESTS=1 OPENYT_RUN_PLAYBACK_TESTS=1 OPENYT_FFMPEG="$PWD/desktopApp/build/bundled-tools/linux-x64/ffmpeg" OPENYT_FFPROBE="$PWD/desktopApp/build/bundled-tools/linux-x64/ffprobe" OPENYT_YTDLP="$PWD/desktopApp/build/bundled-tools/linux-x64/yt-dlp" xvfb-run -a ./gradlew :core:desktopTest :ui:desktopTest :engine-ytdlp:desktopTest :data:desktopTest :media-ffmpeg:desktopTest :desktopApp:test :desktopApp:packageDeb --console=plain --max-workers=2
env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE xvfb-run -a scripts/validate-linux.sh --scenario all --no-build --offline
```

Both exited 0. The combined Gradle invocation completed in 3m14s; unchanged
contracts were up-to-date from the executed focused runs. The installed DEB
harness passed all local scenarios with four explicit internet scenario skips.
Do not count those as real-source download passes. Rendering includes actual
cancellation/reaping, source preservation, portrait/caption pixels, cover retry
and anamorphic original/fit/crop checks.

## Android test-fixture corrections

The API 26 visual failures were diagnosed from actual logcat/thread dumps.
`SurfaceFlinger` rejects virtual capture width exceeding its physical framebuffer;
that target now uses 1000x1600 at density160, still asserting >=840dp expanded
navigation. The activity is opened before changing density, tracked through
recreation, and the launcher transition is isolated before the next test. D-pad,
icon visibility, screenshot and large-text assertions remain enabled.

A/B pause verification reads the actual Media3 controller position on its main
thread. It no longer converts an unavailable bridge snapshot into a false zero
position. The 250ms stationary-position and 1000ms repeat tolerances are unchanged.

## Android API 26 final evidence

`ANDROID_SERIAL=emulator-5590 ./gradlew :androidApp:connectedDebugAndroidTest
-Popenyt.testAbi=x86_64 -Popenyt.testApplicationIdSuffix=.regression
-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --console=plain
--max-workers=2` exited 0. The final JUnit XML contains 30 cases: **25 passed,
5 explicit internet skips, 0 failed/errors**. AGP's progress lines overcount the
skip notifications; these numbers come from the actual XML cases.

Coverage includes packaged FFmpeg three-range output, real Media3 playback,
WorkManager without an activity, actual connection/power/free-space reads,
SQLite persistence/migration, offline assets, subtitle FTS/search playback, study
notes/A-B/export/deep links, portable SAF backup/restore, recovery, PiP, all
provider vectors and expanded D-pad/large-text layouts. Capability enumeration
reports all six processing flags true on this emulator. This is emulator evidence,
not a physical-device thermal or battery benchmark.

![Actual Media3 playback of a newly exported clip on API26](evidence/expansion-20261114/android-api26-exported-clip-playing.png)

## Separate internet subscription smoke

The extracted final DEB, using isolated temporary XDG data/cache/config, executed:

```text
OpenYT --cli watch --add --name <smoke> --url https://feeds.npr.org/510298/podcast.xml --kind feed --minutes 60
OpenYT --cli watch --discover-only
OpenYT --cli watch
```

All exited 0. Actual public RSS fetched **300 episodes**, set initialized baseline,
and retained an empty download candidate list. No episode media was downloaded.
Temporary data was removed afterward. This is distinct from fixture-based unit
rules and from real-source video/audio extraction smoke.

## Android API 36 final evidence

The same full instrumentation command with `ANDROID_SERIAL=emulator-5588`
(API36 x86_64) exited 0 in 2m03s: **25 passed, 5 internet skips, 0 failed/errors**.
The separately opted-in `AndroidEngineNetworkTest` then passed **5/5, no skips**:
Archive.org/YouTube metadata, real video merge, M4A extraction, progress/cancel
and temporary-file cleanup. Public sources were used without user credentials.
[Per-case results](evidence/expansion-20261114/android-results.json) preserve the
full-suite names/statuses for API26 and API36.

## Windows 11 Docker VM final evidence

Native JDK17/21, Gradle8.14.4 and WiX generated MSI+EXE+harness successfully.
`scripts/validate-windows.ps1` exited 0 in the existing interactive Windows11 VM
`tbg-win11`. Both installer formats
passed actual desktop/Start-menu icon comparison, launcher, external association,
exact Unicode Explorer selection including a new MP4-only folder, three visible
VLC decode/mini-player cycles, study/A-B/deep links, recovery and offline quotas.

The new installed `clips` case exported three ranges, checked portrait/caption/
cover output, verified durable assets and SQLite receipt reopen, played each new
file through actual packaged VLC, retried idempotently and preserved the source.
[MSI clip log](evidence/expansion-20261114/windows-msi-clips.txt),
[EXE clip log](evidence/expansion-20261114/windows-exe-clips.txt).

![Actual installed Windows EXE mini-player decoding the synthetic fixture](evidence/expansion-20261114/windows-exe-native-playback.png)

The current [source/build fingerprints](evidence/expansion-20261114/native-source-fingerprint.json)
compare Windows-target Kotlin and build inputs with the source archive used by
the final native job. Android-specific sources/tests are validated separately.
Evidence copy initially hit PowerShell5 long-path limits; robocopy preserves deep
assets without changing test assertions. Native results remain execution evidence;
a matching fingerprint alone is not a passed test.

The loaded local VM took **85.87s (MSI) / 86.62s (EXE)** to expose the application
frame via shortcut. Functional success does not certify fast startup; this cold
VM measurement is retained instead of claiming the earlier 2.11s sample as typical.

## Desktop internet extraction

`OPENYT_RUN_NETWORK_TESTS=1`, bundled yt-dlp/FFmpeg/FFprobe/Deno and
`:desktopApp:test --tests '*DesktopYtDlpEngineIntegrationTest'` exited 0:
**6 passed, no skips** (metadata, playlist, output/progress, video merge, cancel
and pause/resume). [Per-case result](evidence/expansion-20261114/desktop-internet-results.json).

The initial combined extraction run had one pause/resume failure. A diagnostic
reproduction also showed the fast CDN could finish the 15MB fixture before its
1.5s pause observation. The test now limits that transfer to 1MB/s so it proves
an active partial pause, retained staging and successful resume. The focused
check and then all six tests passed with the original bundled binary. Product
engine behavior and test success criteria were not relaxed.

## Final plan audit

The plan audit added the missing source-bitrate space estimate to the batch
editor, including unknown inputs, overlapping selections and overflow bounds.
`ClipStorageEstimateTest`, all three `ExpansionM4UiTest` cases, all five actual
`ClipRenderingContractTest` cases and Android application/test APK assembly passed.
Rendering now also proves display-matrix rotation before framing, a green cover
at the chosen 3.5s source interval, and cancellation of an observed live FFmpeg
PID with its handle confirmed dead afterward. No staging-directory observation
alone is used as proof that encoding was running.

## Bounded discovery cancellation audit

Bugbot confirmed that cooperative timeouts alone did not bound the old blocking
RSS/inspection calls. The correction makes RSS use cancelable whole-call deadlines,
desktop capture wait interruptibly with bounded drains and process-tree cleanup,
and Android inspect use a unique process ID with the pinned wrapper's interrupt
cleanup. Tests cover a trickling HTTP response, an observed real JVM child with
another download preserved, actual Android Python/socket cancellation with a
concurrent transfer, and real WorkManager behavior. Focused tests passed; final
combined/native reruns below supersede earlier source snapshots.

The Linux combined rerun exited 0: 297 core, 29 engine, 130 data, 8 media and 129 desktop
cases, with 7 explicit skips (6 opt-in internet cases and 1 incompressible ZIP64
budget case), no failures/errors. [JUnit summary](evidence/expansion-20261114/linux-results.json).
The API26 full suite then passed 26 functional cases plus 5 explicit network skips.
Desktop online extraction was repeated after the adapter fix and passed 6/6.
The first combined API36+internet attempt failed the old StudyFlow seek poll.
The focused reproduction passed with the first native prepared position and
explicit pause for measurement, retaining the one-second accuracy assertion.
This also avoids falsely accepting natural playback that later crosses a target.
The full API36/internet run is being repeated with that stronger observation.

## GitHub gate

Final Linux/Windows/macOS/Android CI on the delivery PR is required before merge.
Mandatory M4 native gates remain open until that result is green. This ledger
records executed local results; the PR checks provide the immutable remote run.
