# NX9 — optional local transcription

Outcome if feasible: explicitly transcribe selected local media on-device and
feed the existing search/study pipeline. Prerequisite: NX4.6; independent of NX5–8.
This is an optional gated track. The plan does not approve a new dependency,
download a model, or promise transcription on every device.

## Fixed behavior

- Evaluate an external maintained engine such as whisper.cpp, not a project-built
  speech recognizer. Engine license and **each model's** license must be reviewed.
  Pin engine/model versions and SHA-256; document redistribution obligations.
- Product code stays Kotlin. Prefer a maintained Java/Android binding or external
  desktop executable. No new Python/Node service, cloud fallback, or project-owned
  C/C++/Rust JNI implementation. If no acceptable binding fits Android, stop that
  target and request an architectural decision; don't quietly break AGENTS.md.
- Start with multilingual tiny/base candidates, never English-only `.en` for
  Portuguese. Published memory estimates are not measurements of OpenYT.
- Explicit model download consent shows language/size/storage and deletion action.
  Model bytes are outside ordinary asset quota and excluded from portable backups.
- Transcripts are labeled machine-generated, not human verified. They use the
  same cue/revision/timebase/index pipeline as subtitles; do not create a second
  search engine or fabricate confidence scores.

<a id="nx9-1"></a>

## NX9.1 — dependency, binding and privacy feasibility

- **Read/modify:** research/ADR report and small isolated probe only. Inspect
  official engine/binding/model sources and existing package license policy.
- **Do:** record supported ABIs/OSs, maintained releases, model origin/licenses,
  native distribution size, Android integration route, security/update support
  and whether microphone/network permissions are unnecessary for file inference.
- **Gate:** `NX-TX01` GO/NO_GO per target with concrete evidence. No model auto-fetch,
  no disabled placeholder UI shipped as a feature, no policy exception inferred
  from a GitHub repository merely existing.

<a id="nx9-2"></a>

## NX9.2 — benchmark bounded multilingual inference

- **Prerequisite:** NX9.1 GO; task explicitly selected. **Do:** use licensed fixed
  ten-minute pt/en speech fixtures and reference text/timestamps; test multilingual
  tiny then base. Decode via external FFmpeg to bounded chunks as the engine needs;
  never put an entire long movie's PCM in memory. No automatic larger-model retry.
- **Tests:** `NX-TX02`: record model/hash, hardware, cold/warm runtime, added peak
  RAM, temporary disk, cancellation, battery/thermal behavior and transcript quality.
  Proposed budgets: 8 GiB/4-core desktop <=10 minutes and <=1.5 GiB added RAM;
  4 GiB physical Android <=20 minutes and <=750 MiB added RAM; cancel <=3 seconds.
  On the fixed clear-speech references, target word error rate <=30% and median
  cue alignment error <=1 second. Document normalization/alignment measurement.
- **Gate:** these are acceptance targets, **not existing measurements**. Choose a
  passing candidate per target. If neither passes, record NO_GO or request a
  justified explicit budget change; don't silently weaken gates or extrapolate
  from an emulator. Test noisy/long/silent audio as limitations too.

<a id="nx9-3"></a>

## NX9.3 — implement model acquisition and lifecycle

- **Prerequisite:** NX9.2 GO and reviewed model distribution rights.
- **Read/modify:** typed model metadata/repository, bounded host fetch/storage and
  settings UI. Do not use arbitrary remote URLs from an imported bundle.
- **Do:** show size/language/requirements, explicitly download, verify digest before
  publication, support cancel/retry/delete and available-space checks. Keep model
  staging separate from user media; never delete a model while inference owns it.
- **Tests:** `NX-TX03`: bad checksum, interrupted transfer, offline start, disk-full,
  cancel/delete during use, restart and version migration. No network call occurs
  until consent; local transcription works without networking after installation.
- **Done:** model availability is verified and errors are actionable/localized.

<a id="nx9-4"></a>

## NX9.4 — integrate bounded transcription jobs

- **Prerequisite:** NX9.3. **Read/modify:** core transcription port, host external
  engine adapter, NX2 journal and NX3 cue ingestion.
- **Do:** one inference at a time, explicit language/auto-detect choice if supported,
  progress/cancel, stable segment IDs, original-media preservation and process
  cleanup. Commit a new track revision only when complete; source changes abort
  or mark stale. Keep existing subtitles/search available during inference.
- **Tests:** `NX-TX04`: engine crash, unsupported media/language, silent/no-speech
  input, chunk boundaries, long duration, cancellation, low memory and restart.
  No raw engine tracebacks or private speech text appears in diagnostics.
- **Done:** actual track output indexes through NX3; no partial result called a
  complete transcript and no unreaped native process after cancel.

<a id="nx9-5"></a>

## NX9.5 — add transcription UI and correction flow

- **Prerequisite:** NX9.4. **Read/modify:** shared track/search/study/settings UI,
  grouped pt/en strings and usage docs.
- **Do:** explicit transcribe action, model/storage preflight, language selection,
  progress/cancel and machine-generated label. Allow plain-text cue correction
  with a new revision/reindex; preserve original generated provenance. Show target
  limitations and keep human subtitle selection available.
- **Tests:** `NX-TX05`: no model, unsupported device, canceled consent, failed job,
  edited cue search result, large text, keyboard/touch and pt/en.
- **Done:** users can distinguish generated, corrected and source-provided tracks.

<a id="nx9-6"></a>

## NX9.6 — native offline proof and release decision

- **Prerequisite:** NX9.5. Run NX-TX and search/study/playback baseline contracts.
  `NX-TX06`: transcribe a permitted local fixture with networking disabled, search a known
  phrase and navigate to its actual media time on every advertised target.
- **Gate:** repeat benchmark/quality/resource checks on physical Android and native
  desktops; record unsupported devices/ABIs and known accuracy limits. Optional
  feature stays unreleased on any target without passing evidence. A NO_GO does
  not invalidate the already delivered subtitle-search/study milestones.
