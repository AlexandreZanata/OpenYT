# Optional capability gates

Updated: 2026-11-14. These decisions distinguish implemented behavior from
capabilities that have not passed their shipping gate. A rejected or blocked gate
is not a delivered user feature, and its dependent tasks are not checked off.

## NX7.7 / NX7.8 — Android bounded discovery: GO, API26/API36 verified

The shared application container owns one coordinator and one subscription
controller. WorkManager runs a bounded discovery pass in that same process;
it never starts an additional downloader or promises indefinite execution.
Requests created by a worker require the application to be open before execution.
Cold restart retains the existing explicit-retry policy.

The official WorkManager 2.12.0 artifact/license/SDK checks passed. A real API 36
WorkManager integration test passed without an activity, with loopback feed
discovery, a persisted queue and opt-out cancellation. The final API26 full suite passed26 functional cases with five explicit
internet skips. The API36 full suite passed31 cases, including all five internet
cases, with no skips. See the validation ledger for commands and per-case evidence. Physical battery/thermal behavior
and exact background timing are not certified. Android may defer work, and
force-stop is not bypassed.

See [ADR-0026](../../adr/0026-subscription-rules-and-clip-journals.md) and the
[executed ledger](../../testing/NEXT_EXPANSION_VALIDATION.md).

## NX7.9 — automatic desktop OS registration: NO_GO for this delivery

**Available:** the new `OpenYT --cli watch --discover-only` is a bounded,
single-owner pass that persists reviewable episodes without starting downloads.
A running GUI keeps ownership. It installs no scheduled task or resident service.

**Not shipped:** automatic registration of Linux user timers, Windows scheduled
tasks or macOS LaunchAgents. Current packages include managed installers and
portable distributions. They do not provide a common, verified per-user scheduler
ownership/uninstall contract. In particular, deleting a portable application
provides no application callback to remove a registered persistent OS job.
Shipping a persistent registration with no verified cleanup path fails NX7.9's
own uninstall criterion; adding a resident cleanup daemon conflicts with the
program's non-goals. No platform is advertised as automatically registered.

This is not a claim that OS scheduling is technically impossible. A follow-up
needs installer-specific ownership, a stable executable location, explicit status
and removal, and native disable/uninstall tests for each advertised package type.
Portable packages must either remain unsupported or have an explicitly approved
different cleanup contract. Manual user-owned scheduling of the documented CLI
is distinct from OpenYT managing it automatically.

## NX9.1 — production speech-runtime gate: NO_GO for this delivery

The reviewed candidate is the official whisper.cpp project, not an approved or
bundled dependency. Its source/runtime and Java bindings use MIT licensing.
The official Java binding documentation lists Ubuntu/Windows testing and strikes
out its former macOS target. The Android documentation describes a sample tied
to the full native source tree, not a self-contained production SDK/artifact.

This checkout has no qualified, pinned Android/macOS speech-runtime distribution
and update path. The Android route needs a separate upstream native packaging
decision; project-owned C/C++ JNI glue remains outside the frozen Kotlin-only
product rules. No model license/hash distribution review or physical Android
runtime/RAM/thermal benchmark has been completed. An emulator cannot establish
the required physical-device budget. Therefore the production runtime gate does
not pass, and no speech-recognition control/model download is exposed.

NX9.2–NX9.6 are **not executed / blocked by the gate**, not implemented, passed,
or replaced by a fake transcript. Subtitle search and study remain fully useful
without speech recognition. A future explicit speech-runtime integration must
qualify each target, model rights/checksums, licensed pt/en references and the
measured quality/resource budgets before proceeding through those tasks.

Primary sources inspected:

- [Official Java binding documentation](https://github.com/ggml-org/whisper.cpp/blob/master/bindings/java/README.md)
- [Official Android sample](https://github.com/ggml-org/whisper.cpp/blob/master/examples/whisper.android/README.md)
- [Runtime/model documentation](https://github.com/ggml-org/whisper.cpp)

No models were fetched and no speech performance or accuracy result is claimed.
