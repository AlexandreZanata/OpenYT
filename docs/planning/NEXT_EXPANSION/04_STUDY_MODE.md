# NX4 — study mode

Outcome: save useful moments, annotate them, repeat a section and export notes.
Prerequisite: NX3.7. Read collections and playback ADRs; use the entry-aware
control port and cue pipeline from NX3 instead of another player implementation.

## Fixed behavior

- Notes/bookmarks reference stable entry ID and local-media milliseconds, not a
  filename or source URL. Store a media-revision reference to flag stale anchors.
- Plain-text notes: max 20,000 characters. Bookmark labels: max 120 characters.
  Capture time from the currently loaded entry; never from another queue item.
- A/B repeat is an explicit session action, with `0 <= A < B <= known duration`
  and interval >=1 second. Unknown duration or unsupported seek disables it with
  explanation. Saved ranges do not auto-start playback/repeat after app restart.
- Study progress is **manually marked studied per entry**, independent of seeking
  or playback resume. Collections show studied entries / current member entries;
  shared membership uses the same entry state. Playback near the end resets resume
  position today; that must not erase study completion.
- Export Markdown with readable timecodes and typed local-entry links. Links are
  meaningful on a library retaining that entry ID; no public/cloud sharing claim.
  Removing a record with authored notes shows an export/removal preview first.
  Original media is never implicitly deleted.

<a id="nx4-1"></a>

## NX4.1 — persist notes, bookmarks, saved ranges and study state

- **Read/modify:** core focused models/repository, SQLDelight and migration tests.
- **Do:** CRUD using stable IDs, timestamps from an injected clock, deterministic
  ordering and validation. Store explicit completion state separately from
  `PlaybackCheckpoint`. Preserve annotations after a media locator changes; flag
  potentially stale timestamps after a content revision changes.
- **Tests:** `NX-T01`: migration/reopen, Unicode/length limits, overlapping notes,
  same-time ordering, missing entry, stale revision, collection membership changes,
  completion independent of playback end, and record-removal preview semantics.
- **Done:** no silent loss of authored text through a path update or cascade.

<a id="nx4-2"></a>

## NX4.2 — implement timestamp capture and note navigation

- **Prerequisite:** NX4.1. **Read/modify:** core study use cases and playback port.
- **Do:** capture loaded-entry position atomically with entry identity, add/edit
  notes/bookmarks, and open their saved time. Validate against known duration;
  surface stale/out-of-range anchors for user correction instead of silently
  rewriting them. Unsaved edits survive normal navigation or prompt before loss.
- **Tests:** `NX-T02`: queue switches during capture, stopped player, no seek
  capability, missing file, edited/shortened media, canceled edits and duplicate
  taps. A note cannot accidentally target the previously playing item.
- **Done:** actual player position and displayed note time agree.

<a id="nx4-3"></a>

## NX4.3 — implement A/B repetition through platform capabilities

- **Prerequisite:** NX4.2. **Read/modify:** shared repeat controller and actual
  Android Media3/desktop VLC adapters; keep platform state ownership clear.
- **Do:** set A/B, start/stop repeat, seek to A at B; clear active repeat when
  changing items or closing playback. Preserve pause, audio focus, PiP and
  mini-player behavior. Serialize/coalesce seeks so slow callbacks do not loop
  infinitely. External fallback states unsupported, not simulated success.
- **Tests:** `NX-T03`: invalid ranges, seek delay, pause/resume, end of media,
  item switch, player close, native error and restart. On the controlled local
  fixture, run five loops: return to A within 1 s and overshoot B by at most 1 s.
- **Done:** real desktop/Android loops meet that measured tolerance; no unbounded
  callback/seek storm or background playback after explicit stop.

<a id="nx4-4"></a>

## NX4.4 — add safe note export and local-entry links

- **Prerequisite:** NX4.3. **Read/modify:** core link parsing/export, existing
  `OpenYtLink` routing, host scheme registration and file-picker adapters.
- **Do:** use `openyt://library/entry/<percent-encoded-id>?t=<milliseconds>` with
  strict ID/time/length validation and a typed library route. Preserve the old
  download-link parser and its inspection-only behavior. External entry links
  navigate to a preview; explicit Play starts media. Internal note clicks can use
  the authorized player control directly. No links accept arbitrary local paths.
- **Tests:** `NX-T04`: Markdown escaping, malformed/duplicate query fields,
  unknown entry, oversized ID/time, URI injection and export cancellation. Export
  contains notes/title/timecodes, not cookie/source secrets or full private paths.
- **Done:** actual OS link dispatch works on advertised hosts and opens the right
  preview. Until verified, exported timecodes remain useful but clickable-link
  support must not be advertised on that host.

<a id="nx4-5"></a>

## NX4.5 — deliver a focused study UI

- **Prerequisite:** NX4.4. **Read/modify:** shared entry details/player UI and
  grouped pt/en strings; avoid turning the library screen into a giant editor.
- **Do:** add a Study area with timed notes/bookmarks, A/B controls, manual studied
  action, collection progress, search/filter and export. Show stale anchors and
  unsupported capabilities with next steps. Include note-removal confirmation.
- **Tests:** `NX-T05`: keyboard/touch flows, unsaved edits, screen reader, large
  text, narrow phone/desktop, empty/long note list, pt/en and failed export.
- **Done:** a user can create a note, return to it and export without codec or
  database knowledge. Hidden unsupported controls have an accessible explanation.

<a id="nx4-6"></a>

## NX4.6 — prove the full study flow and close milestone 2

- **Prerequisite:** NX4.5. Run NX-T, NX-S, legacy playback and native lifecycle
  contracts. `NX-T06`: create notes, repeat a range, mark studied, restart offline, reopen
  a note and verify exact entry/time; export and open a supported local link.
- **Gate:** native Android/PiP and desktop/mini-player checks, including Windows
  close/reopen. Record actual loop tolerances and limits. Document local-link
  portability, manual completion and original-preserving record removal.

**Next:** NX5.1. Optional NX9 evaluation can begin in a separate authorized task.
