# NX7 — subscription rules and gated scheduling

Outcome: subscriptions apply understandable filters, profiles and resource limits
without losing deferred episodes or duplicating queued work. Prerequisite: NX6.6.
Read ADR-0021, `Subscriptions.kt`, feed readers, coordinator ownership and settings.

## Fixed behavior

- Mandatory NX7.1–NX7.6 run **while the app is alive**, preserving existing scope.
  Optional NX7.7–NX7.9 are separate background tracks, not hidden prerequisites.
- Rules: optional minimum/maximum duration, selected profile, max new episodes per
  check, max pending episodes and explicit network/power/free-space constraints.
  Start with no automatic retention/deletion. Existing subscriptions retain their
  previous defaults after migration.
- Current `FeedEpisode` has no duration/size. Resolve duration through bounded
  inspection when a rule needs it. Unknown duration is deferred with explanation,
  not treated as zero or permanently filtered out. Do not inspect an unbounded feed.
- Persist candidate states DISCOVERED/FILTERED/DEFERRED/QUEUED with reason and rule
  revision. Mark queued/seen only after enqueue persistence. A cap or unavailable
  constraint must not drop the rest of the feed. Rule edits do not secretly enqueue
  historical backlog; replay is an explicit previewed action.
- Preserve `originKey = rule.id + ':' + episode.id` deduplication and baseline-first
  semantics. Migrate old seen IDs as historical baseline without surprise downloads.
- Network constraints distinguish **Wi-Fi** from **unmetered**. Unknown/unsupported
  device state is explicit and cannot be treated as satisfied. Check constraints
  before feed requests, metadata inspection and enqueue; honor later loss too.
- Reserve space for active/pending work. If size is unknown and a free-space rule
  is active, defer until a usable estimate or explicit user override; don't promise
  protection from all disk-full cases. Existing structured storage errors remain.

<a id="nx7-1"></a>

## NX7.1 — model versioned subscription rules and decisions

- **Read/modify:** pure automation models, selected-profile reference and reason
  codes; extend serialization compatibly instead of replacing the subsystem.
- **Do:** validate duration bounds, positive caps, interval and constraint choices.
  Model ALLOW/FILTER/DEFER with localized reason IDs and capabilities. Rules capture
  a revision; effective download plans are resolved/snapshotted at enqueue.
- **Tests:** `NX-A01`: legacy defaults, invalid ranges, unknown duration/size,
  unsupported power/network state, missing/deleted profile and deterministic rule
  ordering. No rule contains platform APIs or engine arguments.
- **Done:** decisions are testable with injected clocks, episode metadata and state.

<a id="nx7-2"></a>

## NX7.2 — persist candidates without dropping deferred work

- **Prerequisite:** NX7.1. **Read/modify:** subscription repository/schema, seen-ID
  migration and bounded candidate repository.
- **Do:** baseline old/new subscriptions correctly, upsert discovered candidates,
  retain deferred backlog across checks, persist rule revision/reason, and support
  explicit backlog-preview/retry. Bound feed reads and stored candidate batches;
  reaching a bound reports truncation and a continuation, never silently skips IDs.
- **Tests:** `NX-A02`: more episodes than max-new, restart with pending backlog,
  overlapping checks, first check/include-existing, rule edit, capped discovery,
  crash before/after enqueue persistence and duplicate feed IDs.
- **Done:** every eligible deferred episode can be revisited, queued episodes do
  not duplicate, and upgrading old subscriptions causes no unsolicited backlog.

<a id="nx7-3"></a>

## NX7.3 — implement host constraints and storage reservations

- **Prerequisite:** NX7.2. **Read/modify:** core capability ports and host network,
  battery/charging and free-space adapters; no Compose/system API in core.
- **Do:** report true/false/unknown/unsupported accurately; use the same selected
  destination as the immutable download plan. Refresh at decision boundaries.
  Integrate existing concurrency/storage handling; do not add a parallel downloader.
- **Tests:** `NX-A03`: metered Wi-Fi, unmetered Ethernet, disconnected state,
  laptop without battery, revoked destination, space reservation contention,
  unknown output size and cancellation. Offline constraints cause no feed/inspect
  network request. Use fake state for deterministic tests and native smoke checks.
- **Done:** UI can explain every deferred state and offer a conscious override.

<a id="nx7-4"></a>

## NX7.4 — apply rules through the existing foreground coordinator

- **Prerequisite:** NX7.3. **Read/modify:** `SubscriptionController`, feed/inspection
  orchestration, profile resolver and the existing persisted enqueue boundary.
- **Do:** inspect bounded candidates only when allowed; snapshot profile and target;
  enforce max-new/max-pending, reserve space, persist enqueue before marking queued,
  retain deferred work and use existing interval/backoff/cancellation. Serialize
  checks for one rule and preserve single application ownership.
- **Tests:** `NX-A04`: transient inspect failure, changed rule/profile mid-check,
  constraint loss, concurrent manual/rule enqueue, retry after crash and cap reset.
  Verify exactly one persisted task per originKey and no swallowed pending episode.
- **Done:** actual foreground scheduling matches previewed decisions and survives
  restart; disabling/deleting a rule stops future checks without deleting files.

<a id="nx7-5"></a>

## NX7.5 — deliver rule editor and decision history

- **Prerequisite:** NX7.4. **Read/modify:** subscription UI and grouped pt/en strings.
- **Do:** show profile, filters, caps and supported device constraints; provide a
  read-only Check preview, explicit enqueue confirmation for backlog, and per-episode
  explanations: queued, filtered, waiting for connection/space, unknown metadata.
  Clearly state that default scheduling requires the app to stay running.
- **Tests:** `NX-A05`: phone/desktop, keyboard/touch, large text, pt/en, missing
  profile, unsupported constraint, pending backlog and no automatic enqueue from
  opening the preview. Disable unsupported settings with an explanation.
- **Done:** a user can understand why a specific episode did or did not download.

<a id="nx7-6"></a>

## NX7.6 — prove rule behavior on real hosts

- **Prerequisite:** NX7.5. Run NX-A and baseline subscription/download contracts.
  `NX-A06`: use a local feed with known durations, then a separate permitted internet smoke
  test. Change constraints, restart, and verify remaining episodes enqueue once.
- **Gate:** Android and desktop verify actual connection/space capabilities;
  unsupported states remain visible. Record baseline-first and retry evidence,
  resource limits and app-alive limitation in usage docs. NX7 core is complete
  after this gate even when optional background work is explicitly deferred.

<a id="nx7-7"></a>

## NX7.7 — optional Android background feasibility gate

- **Prerequisite:** NX7.6; explicit selection of this optional task. **Read:**
  current official WorkManager, foreground-service and Android target-36 rules;
  Android host composition/ownership and existing download service lifecycle.
- **Do:** propose one process-owned coordinator/repository composition accessible
  to UI and workers; no separate `:remote` process or duplicate scheduler. Assess
  a bounded discovery/inspection-and-enqueue worker, not an indefinite downloader.
  Review/pin WorkManager only if needed and license/security checks pass.
- **Tests/report:** `NX-A07`: prototype on API 26 and 36, UI/worker overlap, kill/
  restart, Doze, reboot and force-stop as distinct cases. Measure work duration.
  Periodic minimum is 15 minutes and execution is inexact; Android 16 job quotas
  affect long-running workers. Never call an emulator run a physical-device pass.
- **Gate:** document GO/NO_GO and ADR with concrete ownership, runtime limits and
  unsupported scenarios. No user-visible promise until NX7.8 passes. Do not claim
  background downloads from a prototype that merely persists queue records.

<a id="nx7-8"></a>

## NX7.8 — optional bounded Android background checks

- **Prerequisite:** NX7.7 GO, dependency review and task selected for execution.
- **Do:** implement explicit opt-in scheduling/cancel through the approved design;
  unique bounded work checks rules, persists candidates/queue then exits. UI says
  checks are inexact and whether actual downloads wait for app open. Network and
  charging constraints are reconciled with rule semantics, not silently weakened.
  No installer auto-opt-in or new long-running service for this task.
- **Tests:** `NX-A08`: real device background check, UI overlap deduplication,
  reboot reschedule, opt-out cancellation, permission/constraint loss, force-stop
  documented behavior, quota/backoff and queue survival. Confirm no second owner.
- **Gate:** only advertise observed background behavior. If native validation is
  unavailable, retain IMPLEMENTED/BLOCKED status and keep the feature unreleased.

<a id="nx7-9"></a>

## NX7.9 — optional desktop closed-app checks

- **Prerequisite:** NX7.6; choose this track explicitly; independent of NX7.8.
- **Do:** first record GO/NO_GO per Linux/Windows/macOS for opt-in OS-scheduled,
  bounded one-shot CLI execution under the existing single-owner protocol. If GO,
  add the smallest native scheduling adapter with preview, enable/disable/status
  and uninstall cleanup. No resident backend/daemon or installer auto-activation.
- **Tests:** `NX-A09`: GUI open vs closed, simultaneous scheduled invocations,
  user account paths/Unicode, restart, missed schedule, disable/uninstall and
  permission loss on each advertised OS. Existing foreground use stays unchanged.
- **Gate:** state actual download-vs-check behavior and OS limits explicitly. Ship
  only validated OS adapters; unsupported OSs display that limitation honestly.

**Next mandatory task:** NX8.1. Optional scheduling is not allowed to block it.
