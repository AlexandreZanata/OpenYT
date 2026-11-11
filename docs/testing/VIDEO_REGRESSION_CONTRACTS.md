# User video regression contracts

Reviewed on 2026-11-11 against development revision `a5b50d9`.

The supplied recordings are evidence of application behavior. Spoken diagnoses
are hypotheses to verify against the implementation.

## Reported behavior

- Video recorded at 22.33.01, approximately 0–29 seconds. Downloads reach
  processing and end in an engine-processing error. The narrator suspects FFmpeg.
- Video recorded at 22.34.43, approximately 0–8 seconds. The storage panel lists
  long Windows paths as files outside the library while the library is empty.
- The second recording, approximately 16–29 seconds, shows the files in Explorer
  and opens a downloaded video successfully. This establishes that the displayed
  failure can disagree with a usable file on disk. The recordings do not include
  process diagnostics that would identify one specific FFmpeg error.

## R01 — Completion corresponds to a confirmed final file

A successful tool exit, a tagged JSON path from `after_move`, and a nonempty file
are all required. Unicode, Windows drive paths, UNC paths, and escaped characters
must round-trip. Log messages and intermediate files must never count as proof of
completion. Real nonzero processing exits remain failures even when a file exists.

Automated coverage lives in `YtDlpSupportTest`, `DesktopYtDlpEngineTest`, and
`DesktopYtDlpLocalTest`. The local test generates separate audio/video streams,
serves them on loopback, runs real merge and audio conversion, and probes both
outputs. Production code remains Kotlin and delegates all media processing to
yt-dlp and FFmpeg.

## R02 — Existing files have a safe recovery path

Storage explains that files outside the library already exist and offers folder
import. Repeated import preserves a single record for each file. Windows slash
and case differences cannot turn an existing library record into a false orphan.
POSIX paths remain case-sensitive. Import and inspection leave media untouched.

Automated coverage lives in `FolderImportServiceTest`, `StorageAnalyzerTest`, and
`FileFolderScannerTest`.

## R03 — Storage and download controls remain usable

Storage paths and names stay inside the dialog. The list scrolls through every
reported file and composes visible rows lazily. Empty categories say that there
are no files. Close and recovery actions remain accessible independently of list
scrolling. Scan errors stop loading and expose retry. Download actions wrap on
narrow screens. Processing uses an indeterminate indicator until completion.

`StorageUiTest` covers 320–1280 px widths, light/dark themes, pt-BR/en strings, and
font scales up to 1.5. It reaches the twelfth orphan file and verifies both footer
actions. `DownloadsUiTest` covers a narrow viewport with enlarged text.
`LibraryStorageUiTest` opens the panel through the Library and retries a failed
scan. Generated screenshots are written to `desktopApp/build/ui-regressions`.

## R04 — Queue actions preserve unfinished work

Clearing finished tasks keeps paused and active work in memory and persistence.
An explicit pause followed by resume and cancel must finish as canceled. Manual
retry starts with a new automatic-retry budget. Non-finite progress is rejected
before it reaches layout or accessibility semantics.

Automated coverage lives in `DownloadCoordinatorTest` and `YtDlpSupportTest`.

## R05 — Restart preserves the download request

A new task retains its playlist item index and filename template through database
reopen. Migration supplies defaults for older records and uses stored individual
entry URLs for legacy playlist tasks whose indices were never persisted. No
migration removes media files.

Automated coverage lives in `SqlDelightDownloadRepositoryTest` and
`DatabaseMigrationTest`, including an upgrade from schema 13 and an older schema 6.

## R06 — Shared provider icons render on Android

Provider glyphs use XML vectors that the shared resource loader supports on both
Android and desktop. Retain the original Simple Icons path geometry and notices.
`ProviderIconsUiTest` loads all sixteen vectors locally.
`AndroidVisualRegressionTest` provides the corresponding Android rendering check
and checks storage recovery controls with enlarged text.
The UI tests also load the grouped translations, guarding against the JVM method
parameter limit of the former flat string constructor.

## Running the contracts

Run deterministic checks and build both Android APKs from the repository root.
Use a virtual display on a Linux host that requires one.

```bash
xvfb-run -a ./gradlew --offline \
  :core:desktopTest :engine-ytdlp:desktopTest :data:desktopTest \
  :desktopApp:test :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
```

Run the real-tool loopback contract separately after preparing the pinned tools.
It uses local HTTP only. External-source integration tests require their own flag.

```bash
./gradlew :desktopApp:prepareBundledTools
OPENYT_RUN_LOCAL_ENGINE_TESTS=1 \
OPENYT_YTDLP="$PWD/desktopApp/build/bundled-tools/linux-x64/yt-dlp" \
OPENYT_FFMPEG="$PWD/desktopApp/build/bundled-tools/linux-x64/ffmpeg" \
OPENYT_FFPROBE="$PWD/desktopApp/build/bundled-tools/linux-x64/ffprobe" \
./gradlew :desktopApp:test --tests '*DesktopYtDlpLocalTest'
```

On a connected Android test device, run the visual contract with
`./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.openyt.android.AndroidVisualRegressionTest`.
Use an isolated application ID when the device contains a production installation.

CI runs the deterministic suite, uploads screenshots/reports, runs the loopback
contract with real tools on Linux, and executes the process/persistence contracts
on Windows and macOS. A configured check is complete only after that CI run passes.

## Executed local evidence

Validation on 2026-11-11 used the commands above with the existing dependency cache.

- The deterministic command completed successfully. Core ran 98 tests, engine
  parsing ran 17, persistence ran 26, and desktop ran 37, with eight optional
  desktop scenarios skipped and no failures.
- The separate real-tool loopback test passed. It executes one of the scenarios
  skipped by the deterministic command and verifies both video merge and audio
  conversion with FFprobe.
- Both `assembleDebug` and `assembleDebugAndroidTest` completed. The final debug
  APK uses the normal `com.openyt.android` package identifier.
- UI screenshots were rendered and inspected for the narrow enlarged-text layout
  and desktop storage dialog. All sixteen converted provider paths were compared
  with their original SVG geometry.
- The Android device rejected installation of the isolated regression package
  with `INSTALL_FAILED_USER_RESTRICTED`. The user chose local validation, so the
  compiled Android instrumentation tests were not executed on the device.
- Native Windows/macOS execution and the newly configured GitHub CI checks have
  not been executed as part of this local validation.
