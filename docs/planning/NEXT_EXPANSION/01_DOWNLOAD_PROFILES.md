# NX1 — complete download profiles

Outcome: a person chooses an understandable goal, reviews the effective choices,
and queues a reproducible download. Prerequisite: NX0.3.
Read ADR-0020, ADR-0021 and ADR-0016; inspect `DownloadOptions`, `MediaInfo`,
`DownloadRequest`, settings, queue serialization and current home-screen choices.

## Fixed behavior

- Four built-ins: **Study offline**, **Audio only**, **Save space**, **Watch on TV**.
  Built-ins are immutable; users may duplicate them into custom profiles.
- Study: video up to 1080p, prefer MP4/AVC/AAC; prefer human subtitles matching
  the UI language's primary tag (`pt` or `en`). Automatic subtitles require opt-in.
  Missing subtitles/artwork appear in preview; do not promise offline readiness.
- Audio: audio/M4A using the existing audio-preset behavior. Do not invent a
  guaranteed bitrate or quality absent from source metadata.
- Save space: video up to 480p, prefer MP4/AVC/AAC. State that final size is an
  estimate and lower resolution is not a guaranteed byte limit.
- TV: MP4 with AVC/H.264 and AAC up to 1080p when the source actually offers that
  combination. Otherwise offer explicit manual selection or block this profile;
  no silent incompatible codec and no unimplemented video-transcoding fallback.
  This is a compatibility preference, not certification of every television.
- Persist selection rules, not provider-specific format IDs. Resolve against
  inspected `MediaInfo`; preserve existing video/audio stream merge semantics.
  Missing/unknown metadata yields an explanation, never an invented capability.
- Profiles contain no cookie paths, tokens or device paths. Destination bindings
  are separate local settings. Effective queued choices are immutable snapshots.

<a id="nx1-1"></a>

## NX1.1 — model profiles and deterministic resolution

- **Read/modify:** core domain/options and inspection use cases. Proposed types:
  `DownloadProfile`, `ProfileResolution`, `ResolvedDownloadPlan`; follow existing
  naming conventions if equivalents exist.
- **Do:** model quality ceiling, kind, audio preset, subtitle preference,
  organization choice and warnings. Implement pure resolution against formats
  with deterministic tie-breaking. Represent unavailable/conflicting requirements
  explicitly. Domain choices must not contain yt-dlp argument strings.
- **Tests:** `NX-P01`: known/unknown codecs, absent resolution, combined streams,
  human-vs-automatic language matches, no subtitles, no compatible TV option,
  audio-only source and deterministic tie-breaking.
- **Done:** the four built-ins produce inspectable plans or actionable failures;
  no network, Compose, filesystem or process dependency enters `core`.

<a id="nx1-2"></a>

## NX1.2 — persist custom profiles and local bindings

- **Prerequisite:** NX1.1. **Read/modify:** data SQLDelight schema/settings and
  migration tests. Add a focused profile repository and versioned serialization.
- **Do:** seed stable built-in IDs, clone/edit/delete custom profiles, store an
  optional default and device-local destination bindings separately. Preserve all
  current settings on upgrade. Deleted profiles must not invalidate queued tasks.
- **Tests:** `NX-P02`: upgrade the previous schema, reopen the database, duplicate
  names/IDs, corrupt definitions, missing destination permission and deletion of
  a profile still referenced by a queue snapshot.
- **Done:** profile definitions round-trip without secrets/absolute paths; local
  bindings are excluded from portable definition export. No migration resets data.

<a id="nx1-3"></a>

## NX1.3 — snapshot profiles into queued requests

- **Prerequisite:** NX1.2. **Read/modify:** coordinator enqueue, persisted request
  serialization, retry/restart and Android SAF export routing.
- **Do:** store the resolved effective request plus the per-task export target
  and necessary profile provenance. Selection never mutates global settings.
  Resolve before enqueue; retry uses the saved choices. If a new inspection is
  required because a source format expired, show the changed plan for approval.
- **Tests:** `NX-P03`: editing/deleting a profile or changing global destination
  after enqueue does not change the queued request; restart preserves snapshot;
  legacy tasks retain existing behavior; revoked SAF grant produces a copy-export
  warning without falsely failing a successful local download.
- **Done:** two tasks with different targets remain independent. A SAF URI never
  enters the filesystem destination field. No credentials are duplicated into a
  portable profile or diagnostics.

<a id="nx1-4"></a>

## NX1.4 — connect the host/engine adapters

- **Prerequisite:** NX1.3. **Read/modify:** existing desktop/Android engine adapters
  and tagged output confirmation; do not replace extraction or codec logic.
- **Do:** translate resolved typed choices through the existing engine boundary.
  Confirm requested subtitles/containers with controlled local inputs. Preserve
  structured errors and existing raw/manual format selection.
- **Tests:** `NX-P04`: real local-tool download with each compatible profile,
  actual stream/container probe, absent subtitle, source format disappearance and
  safe arguments for Unicode/spaces. Keep internet source smoke tests separate.
- **Done:** actual output matches the approved plan or reports a clear failure;
  no success inferred solely from the process exit code.

<a id="nx1-5"></a>

## NX1.5 — add profile selection, preview and editing UI

- **Prerequisite:** NX1.4. **Read/modify:** shared home/settings components and
  grouped pt/en feature strings. Keep the current manual download flow available.
- **Do:** select a profile, show summary/warnings/destination/estimated size,
  offer custom profile editing and an explicit Download action. Show why a
  profile cannot apply. Restore selection across navigation without enqueueing.
- **Tests:** `NX-P05`: Compose flows at phone/desktop widths, enlarged text,
  keyboard focus, screen-reader names, pt/en; changing a profile does not start a
  download; unavailable TV/source options are actionable, not dead buttons.
- **Done:** preview and queued snapshot agree. Advanced codec details are secondary
  to the understandable goal and next action.

<a id="nx1-6"></a>

## NX1.6 — milestone evidence and documentation

- **Prerequisite:** NX1.5. Run the NX-P family, current queue/output regressions,
  migration checks and the native matrix affected by profile/SAF changes.
- **Tests:** `NX-P06`: combined native preview/download/restart/retry flow and
  regression of the existing manual format flow.
- **Do:** document the four profiles and fallback rules; capture real UI examples,
  not design mockups presented as implementation. Record evidence and limitations.
- **Gate:** Windows/Linux desktop and Android verify a real output, restart/retry
  preserves its plan, and current manual selection still works. Use macOS native
  smoke checks for the shared desktop change before a cross-platform release.
  NX1 is not VERIFIED while required native checks are only planned.

**Next:** NX2.1; no release is created by this gate automatically.
