# ADR-0026 — Subscription constraints, bounded discovery and clip publication

- Status: accepted
- Date: 2026-11-14

## Context

The remaining mandatory NX7/NX8 phases need durable subscription decisions and
original-preserving batch processing. A queue entry must retain its approved
profile and resource conditions. A successful FFmpeg exit must not create a gap
where a verified file is lost from the library after a crash.

## Subscription decisions

`SubscriptionRule` evolves its existing JSON encoding with defaulted revision,
filters, candidate states, waiting/failure reasons, fair continuation and compact
filtered-history IDs. No persistence migration or replacement extraction engine
is needed for rule definitions. Old seen IDs remain historical baseline.

Candidates are discovered in batches of 200; at most 20 metadata inspections run
per check. The continuation uses episode identity, so persistent early failures
cannot consume every future inspection budget. Queue persistence precedes seen
acknowledgment. Filter changes do not replay rejected historical episodes;
read-only history previews require an explicit download confirmation to replay.
Keep at most 5,000 detailed candidates, archive older filtered identities after
500 detailed filtered records, and stop further discovery at 50,000 archived IDs
with a visible limit. Pending records are retained. Remote feeds can remove old
episodes; the application cannot recover metadata no longer offered by a source.

`DownloadExecutionConstraints` is an immutable part of `DownloadOptions`.
`DownloadExecutionGate` rechecks it before a task starts; blocked tasks do not
starve manual work. An active constrained task is checked every five seconds and
paused if its conditions are lost. Resuming a paused task is explicit. Missing
platform state fails closed for requested conditions. Wi-Fi and unmetered access
are distinct. Desktop does not infer either from interface names and disables
unsupported device-only choices in the editor. Storage reservations are estimates,
not a guarantee against all concurrent writes or disk-full conditions.

Both GUI hosts and the desktop watch CLI inject actual platform adapters and the
profile repository. Opening the editor refreshes profile definitions. Local
destination bindings stay separate from portable profile definitions.

## Android background discovery

WorkManager **2.12.0** is the newly introduced runtime dependency. The official Maven
POM identifies AndroidX and Apache-2.0; its AAR metadata requires compile SDK 35
and AGP 8.6.0, compatible with this project's SDK 36/AGP 8.13.2. It supplies the
existing Android process with OS scheduling, not a backend or a new downloader.
The application accepts no externally supplied worker class, process argument,
credential or remote job payload. Default scheduling is off; the platform's
normal app-data sandbox protects consent and WorkManager state. This dependency
review does not claim the absence of all future vulnerabilities.

The data adapter also declares an explicit **OkHttp 4.12.0** dependency, reusing
the same Apache-2.0 runtime already shipped through Coil (ADR-0009). A concrete
need is request cancellation plus a whole-call deadline: the former blocking
`HttpURLConnection` reader could exceed the worker quota with trickling bodies.
The published 4.12.0 source/license and `Call.cancel`/whole-call timeout contract
were reviewed; the GitHub global Maven advisory query for this exact coordinate
returned zero entries on 2026-11-14. This is a dated query result, not a claim of
universal vulnerability absence. The client retains standard TLS validation,
has no cookie jar, credential/logging interceptor or server component, and keeps
the existing 4 MiB response/DOCTYPE rejection. Connect/read timeouts are 15s and a
whole HTTP call is limited to 30s. Coroutine cancellation calls `Call.cancel` and
releases the actual connection rather than leaving blocking reads behind.

Desktop metadata capture drains bounded outputs in parallel, waits interruptibly,
limits a process to 90s, and kills/reaps only that capture on cancellation. Android
inspection assigns a unique internal process ID and uses `runInterruptible`;
the pinned wrapper's interrupted-join path destroys its process. Cleanup addresses
that inspection ID, preserving concurrent downloads. Neither worker nor CLI uses
`engine.close()` to implement individual request cancellation.

`SubscriptionDiscoveryWorker` shares the `OpenYtApplication` container with UI
activities. It waits for initialization, processes at most 20 due rules under a
two-minute timeout, confirms persistence, and exits. Oldest attempted rules run
first. There is no `:remote` process, foreground download service or resident
polling worker. Periodic work has an initial delay and a minimum 15-minute interval;
Android can defer execution. Opt-out cancels the unique periodic work.

Background discovery saves requests with `requireAppOpen`. No background-only
task starts downloading in a process with no visible activity. Opening the app
allows eligible work in that process; a cold restart still follows the established
INTERRUPTED recovery policy and requires explicit retry. Activity lifecycle changes
control the foreground subscription loop. Force-stop is not bypassed.

Desktop `watch --discover-only` is also bounded and persists reviewable requests.
It skips work when an existing GUI owns the database. It installs no OS tasks.
Automatic desktop registration and transcription have separate documented gates;
see [optional capability decisions](../planning/NEXT_EXPANSION/OPTIONAL_GATES.md).

## Clip recipes and publication

`MediaProcessingRequest` adds typed rendering/framing/caption/cover settings,
source identity/revision, known duration, destination snapshot and stable job ID.
No user-supplied filter strings or shell command strings are accepted. Fast copy
remains approximate/keyframe-aligned. Precise MP4 output requires actual H.264/AAC
encoders. Portrait output fits/pads to 720x1280 by default; center crop is explicit.
Capabilities are probed from the actual external FFmpeg on each host. Precise
rendering first normalizes non-square sample pixels while preserving display
aspect ratio, before portrait fitting or cropping.

Captions use normalized local-media cue times. The adapter writes an owned ASCII
`captions.srt` in private staging and uses a fixed filter expression; Windows paths
and apostrophes never enter FFmpeg's filter grammar. SRT timestamps use Locale.ROOT.
Accurate input seeking resets the rendered clip timeline. A chosen cover frame is
a separate output and is copied into the durable asset store before publication.
`ClipCoverPublisher` reuses only a verified asset with the same entry, kind, media
revision, digest and actual owned bytes. This makes retry after the asset commit
but before the clip commit safe even with a budget for just one cover.

Schema **26** (`25.sqm`) adds `clip_job`. The journal stores the approved request,
source digest, operation state and expected output/cover digests before file
promotion. Retry recognizes a verified prior receipt, retains cover ownership
proof across repeated failures, and never overwrites an existing unrelated file.
`SqlDelightClipJobStore.complete` inserts the derived library row and successful
receipt in one database transaction, preserving an existing row's authored metadata.
The UI reloads that committed row instead of reconstructing and overwriting its
authored metadata. This closes the file-ready/library-publication crash gap.

The Android processor belongs to the application container, so configuration
changes cannot create two independent executors for the same queued recipe.
Completed batch items survive a later failure; retries skip verified successes.
Process restart marks running jobs INTERRUPTED and never silently rerenders them.
Preview uses the same recipe, renders to private application storage and is not
inserted into the library. Closing/replacing a preview stops its internal player
before cleanup. The user's original media is never overwritten or deleted.

New clips use the selected downloads directory captured by the editor, allowing
read-only source folders. The original single trim/audio conversion APIs remain
compatible. Detailed local operation history is not a portable executable queue;
portable backups continue to include media, companion assets and library/study
metadata without local operation paths, credentials or OS scheduling state.

## References and verification

- [AndroidX WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work)
- [Official 2.12.0 POM](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0.pom)
- [Android long-running work restrictions](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- [FFmpeg filters](https://ffmpeg.org/ffmpeg-filters.html)
- [Expansion contracts](../planning/NEXT_EXPANSION/CONTRACTS.md)
- [Executed validation ledger](../testing/NEXT_EXPANSION_VALIDATION.md)

Cancellation references:

- [Pinned OkHttp Call contract](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Call.kt)
- [Pinned Apache-2.0 license](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/LICENSE.txt)
- The actual youtubedl-android 0.18.1 AAR bytecode was inspected for its interrupted-join process destruction path; upstream source is [YoutubeDL.kt](https://github.com/junkfood02/youtubedl-android/blob/master/library/src/main/java/com/yausername/youtubedl_android/YoutubeDL.kt).
