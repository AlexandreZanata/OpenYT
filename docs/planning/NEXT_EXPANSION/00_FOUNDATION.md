# NX0 — execution foundation

Prerequisite: read the [overview](README.md), architecture and AGENTS.md.
This phase creates a measured baseline and reusable fixtures, not a framework
or a broad refactor. Do not implement another phase while preparing it.

<a id="nx0-1"></a>

## NX0.1 — establish the actual baseline

- **Read/modify:** existing status, architecture, Gradle targets, native harnesses
  and [PROGRESS.md](PROGRESS.md). Record evidence there or in a linked small
  execution ledger. Do not change product behavior.
- **Do:** record checkout/branch, dirty state, schema version, available desktop
  tools, Android SDK/device and Windows test VM. Run the current deterministic
  suite and Android compilation from CONTRACTS.md. Inspect existing real playback,
  file-action, installer and subscription tests before proposing replacements.
- **Acceptance:** distinguish pre-existing failures from new failures. Record
  exactly which native checks can run; record unavailable environments as blocked.
  Baseline only means the executed baseline checks passed, not every future gate.
- **Evidence:** commands, exit codes, commit and tool versions; no feature claims.
  The remaining phases depend on this task being VERIFIED for their usable host.

<a id="nx0-2"></a>

## NX0.2 — establish small, redistributable fixtures

- **Prerequisite:** NX0.1. **Read:** existing local-tool fixtures and test helpers;
  reuse them before adding new files under relevant test roots.
- **Do:** define one short synthetic media timeline shared by subtitle/search,
  study and clip tests. Include SRT/VTT in pt/en, accents, overlapping cues,
  a subtitle-free item, identical files under different names, distinct files of
  equal size, and filenames with spaces/apostrophes/Unicode. Generate larger media
  or corpus fixtures at test runtime; do not commit large movies.
- **Acceptance:** provenance/license and expected cues/times/checksums are checked
  in. No network or user-media dependency. Malformed/oversized/traversal fixtures
  are inert test data. Use fixed seeds and controlled clocks where needed.
- **Evidence:** fixture verification runs without network. Do not build a universal
  fixture framework or add product dependencies for test convenience.

<a id="nx0-3"></a>

## NX0.3 — wire the verification and handoff process

- **Prerequisite:** NX0.2. **Read:** existing CI, CONTRACTS.md and executor protocol.
- **Do:** map existing test tasks to the contract families in this plan; add only
  missing fixture smoke checks. Record the future contracts as pending, not passed.
  Establish a task evidence section in PROGRESS.md and document the native matrix.
- **Acceptance:** deterministic CI remains offline; network integration remains
  opt-in; native tests have explicit OS/device requirements. Regression failures
  block dependent delivery. No fake all-platform pass from a single runner.
- **Gate:** existing download, local playback, open/reveal, queue persistence and
  record-only removal checks remain green on the executed baseline. Native gaps
  must be resolved before the relevant release gate, not hidden by this task.

**Next:** NX1.1. The common contracts apply from the first product change.
