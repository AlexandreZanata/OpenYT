# NX8 — batch clip creation

Outcome: select several moments, preview safe export choices and create new clips
without changing the source. Prerequisite: NX7.6 (optional scheduling excluded).
Read ADR-0021 and `MediaProcessor` / `FfmpegPlan`; use external FFmpeg throughout.

## Fixed behavior

- Each recipe has a stable clip ID, entry/revision, valid range, mode, output
  settings, optional caption track and optional cover timestamp. No raw filter
  strings, shell commands or arbitrary codec arguments in UI/domain input.
- Lossless copy remains keyframe-aligned and is labeled approximate. Precise cuts
  require explicitly chosen re-encoding with verified encoders. Do not relabel
  `-c copy` as frame-accurate trimming.
- Portrait default is **fit and pad to 720x1280**, preserving the full image.
  Center crop is explicit with preview. No face tracking or AI reframing.
- MP4/H.264/AAC and caption burning are available only where actual packaged
  encoders/filters support them. Source without audio remains valid video-only.
  Unsupported configurations are explained before processing; no silent fallback.
- Burning captions requires a tested subtitle renderer (for example libass).
  Place a validated caption file under a fixed ASCII relative name in private
  staging and construct trusted filter arguments there; argument lists alone do
  not escape FFmpeg's internal filter syntax. Test Windows drive/apostrophe paths.
- Outputs get unique names/new library entries and provenance. Originals are
  unchanged. Each clip has its own result; partial batch success is retained.

<a id="nx8-1"></a>

## NX8.1 — probe the actual processing capabilities

- **Read/modify:** existing FFmpeg adapter/capability tests and bundled-tool
  inventories; use fixed known probe arguments, not user-supplied shell strings.
- **Do:** inspect `-filters`/`-encoders` and run tiny real proofs for trim-copy,
  H.264/AAC re-encode, scale/pad/crop, caption rendering and cover extraction on
  desktop bundles and Android. Check distribution/license implications of codecs.
- **Tests:** `NX-CL01`: absent renderer/encoder, malformed tool response and real
  tiny output verification. Record tool/build versions and host capability matrix.
- **Gate:** reject unsupported recipe options clearly. If required cross-platform
  rendering is missing, stop that subfeature and document the smallest approved
  packaging decision; do not add a new codec implementation or hidden download.

<a id="nx8-2"></a>

## NX8.2 — model recipes and build a safe processing plan

- **Prerequisite:** NX8.1. **Read/modify:** core media-processing models and the
  existing FFmpeg plan generator; preserve current trim/audio APIs compatibly.
- **Do:** validate bounded ranges, known duration, resolution/aspect, caption
  timebase, cover timestamp and capability requirements. Compile only typed
  options to argument lists. Reserve unique outputs; retain `-n` protection.
- **Tests:** `NX-CL02`: invalid/overlapping ranges, same-name outputs, no audio,
  rotation metadata, out-of-range captions, source revision mismatch and strings
  containing FFmpeg filter metacharacters. Overlapping independent clips are valid.
- **Done:** deterministic plan with explicit approximate-vs-reencoded mode.

<a id="nx8-3"></a>

## NX8.3 — run batches with cancellation and per-clip receipts

- **Prerequisite:** NX8.2. **Read/modify:** processing use case/host adapters,
  NX2 operation journal and library insertion.
- **Do:** process one clip at a time initially, verify/probe output before success,
  preserve completed clips if a later one fails, allow retry failed clips without
  redoing successes, and cancel current/pending work. Persist provenance and
  operation status; restart marks active work interrupted, not silently complete.
- **Tests:** `NX-CL03`: process failure mid-batch, cancellation, disk-full,
  permission loss, crash around publication, duplicate retry and tool timeout.
  Hash originals before/after; cleanup touches only owned partial outputs.
- **Done:** final summary distinguishes success, failure, canceled and interrupted.

<a id="nx8-4"></a>

## NX8.4 — implement portrait rendering, captions and covers

- **Prerequisite:** NX8.3 and corresponding NX8.1 capabilities. **Read/modify:**
  trusted processing plans and host staging, not UI process calls.
- **Do:** apply display rotation before fitting/cropping, keep aspect ratio,
  map captions into clip-local time, render UTF-8 text, and extract a chosen cover
  into the durable asset store. Use reviewed fixed filter grammar and safe owned
  caption staging; avoid copying the entire source only to escape its filename.
- **Tests:** `NX-CL04`: real portrait/landscape/rotated input; exact output
  dimensions, duration, codec/audio; visible accented captions, segment offsets,
  cover timestamp and Windows paths with colon/backslash/apostrophe/Unicode.
- **Done:** inspect rendered frames as well as probe metadata. A successful exit
  code alone does not establish visible captions or a correct crop.

<a id="nx8-5"></a>

## NX8.5 — deliver multi-range editing and preview UI

- **Prerequisite:** NX8.4. **Read/modify:** shared processing/player UI and pt/en
  strings; reuse current trim/conversion entry points.
- **Do:** add/remove/reorder ranges, preview each range/aspect/cover, choose
  approximate vs precise modes, estimate storage, start batch explicitly and show
  individual results. Warn when source captions/timebase are unavailable.
- **Tests:** `NX-CL05`: keyboard/touch, narrow screen/desktop, large text, pt/en,
  unsupported capability explanation, canceled edit, partial success and retry.
- **Done:** preview uses the same typed recipe as export; no nonfunctional option
  is shown as available. Sources and original library records stay unchanged.

<a id="nx8-6"></a>

## NX8.6 — prove outputs and close milestone 4

- **Prerequisite:** NX8.5. Run NX-CL, current FFmpeg/queue/library contracts and
  native Windows/Linux/macOS/Android checks for supported processing options.
- **Gate:** `NX-CL06`: export three ranges, one portrait/captioned variant and a cover; probe
  outputs, inspect frames/audio, play them, restart and verify provenance/assets.
  Verify a controlled failed clip leaves successes playable and originals intact.
  Publish capability limits in usage docs and record real UI evidence.

**Main program finished** only when NX0–NX8 mandatory gates are VERIFIED. Optional
background/transcription limits remain explicit; publication needs its own
authorized release process and existing native/release contracts.
