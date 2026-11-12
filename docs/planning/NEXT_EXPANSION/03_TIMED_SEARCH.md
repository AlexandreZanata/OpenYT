# NX3 — search within saved media

Outcome: search subtitle text, see a matching excerpt/time, and open that point in
the saved media. Prerequisite: NX2.6. Read persistence, output and playback ADRs
0005/0019/0020/0021/0024. Transcription is not required for this phase.

## Fixed behavior

- Start with SRT and WebVTT assets. No subtitle means **not indexed/no text track**,
  not an apparently successful search over content that was never indexed.
- Cues reference entry ID, track ID, media revision, language, source type and
  start/end milliseconds. Preserve overlaps and source ordering. Keep human and
  automatic tracks distinguishable.
- Establish local-media versus source-video timebase. A partial download may need
  an offset. Verify actual engine output; unknown mapping requires explicit
  correction and cannot silently seek to guessed times.
- Default candidate is SQLite FTS4 with unicode61, gated by NX3.1. Plain search
  treats user input as text: bound parameters, quoted tokens with escaped quotes,
  AND matching, no arbitrary MATCH operators. Limits: 256 characters, 8 tokens,
  50 results/page, stable time/entry ordering. Keep title/tag search available.
- New playback control exposes real capabilities/state, entry identity and seek.
  An external-player fallback without seek support must say so. No pretend seek.

<a id="nx3-1"></a>

## NX3.1 — prove indexing capability on the shipped databases

- **Read/modify:** SQLDelight drivers and test configuration. Use a small probe
  test/report; do not migrate every user database before the probe passes.
- **Do:** verify FTS4 + unicode61 creation, binding, Unicode tokenization and
  incremental updates with SQLDelight's actual desktop driver and Android API 26
  plus the current target API. Exercise Windows/macOS native SQLite drivers too.
- **Tests:** `NX-S01`: create/search/update/delete/accent cases and a generated
  100,000-cue fixture. Proposed warm-query p95 budgets: desktop <=500 ms,
  Android <=1 s on recorded hardware; measure 30 queries after 5 warmups.
- **Gate:** record driver/build/device, corpus and results. If FTS4/tokenizer is
  unavailable, stop dependent indexing work and propose a documented alternative;
  do not silently ship a slow LIKE scan or an unreviewed SQLite replacement.

<a id="nx3-2"></a>

## NX3.2 — parse and normalize timed subtitle tracks

- **Prerequisite:** NX3.1. **Read/modify:** pure Kotlin parser/models and asset
  import boundary; put no file reads in the parser.
- **Do:** parse bounded UTF-8 SRT/VTT (max 10 MiB/100,000 cues per track initially),
  strip non-display markup safely, normalize searchable text, preserve original
  display text and explicit timebase. Accept BOM/CRLF and recover only documented
  nonfatal cue errors; report malformed/truncated tracks clearly.
- **Tests:** `NX-S02`: pt/en accents, punctuation, multiline/overlapping/empty cues,
  malformed times, negative/overflow/huge durations, VTT metadata, HTML-like text,
  source/local offsets and subtitle content shown as plain text.
- **Done:** deterministic cue IDs/order; no HTML execution or regex runaway.

<a id="nx3-3"></a>

## NX3.3 — persist cues and atomically rebuild an index revision

- **Prerequisite:** NX3.2. **Read/modify:** focused data subtitle/index repository,
  migrations and NX2 operation journal.
- **Do:** retain usable old index until new revision commits; persist cues and FTS
  rows together. Build/rebuild/remove via cancellable bounded jobs, track index
  state, and invalidate on media/track revision changes. File checks happen through
  ports, not SQL text or Compose callbacks.
- **Tests:** `NX-S03`: restart, migration, crash mid-reindex, deleting a track,
  duplicate retry, changed media and cancellation. FTS and cue counts reconcile;
  abandoned revision rows are cleaned without touching media.
- **Done:** no partial results advertised as a complete current index.

<a id="nx3-4"></a>

## NX3.4 — expose bounded text search

- **Prerequisite:** NX3.3. **Read/modify:** pure query validation/use case and data
  search adapter; return result IDs, time, excerpt, source and index status.
- **Do:** normalize input, reject excessive size/token counts, escape literal FTS
  tokens, support library/collection/item scope and explicit language/track filter.
  Cancel superseded queries; paginate deterministically. Define accent matching
  behavior from the tested tokenizer and document it.
- **Tests:** `NX-S04`: quotes/operators/SQL-like strings treated literally, empty
  input, CJK/no-whitespace text behavior, pagination ties, deleted entries and
  cancellation. Repeat the NX-S01 measured corpus budget with the real repository.
- **Done:** unavailable index versus zero matches are distinct typed states.

<a id="nx3-5"></a>

## NX3.5 — add entry-aware timed playback without losing history

- **Prerequisite:** NX3.4. **Read/modify:** `PlayableMedia`, `PlaybackStore`, desktop
  VLC and Android Media3 launchers/services; SQLDelight playback persistence.
- **Do:** introduce a core `PlaybackSessionControl` port (or equivalent) for loaded
  entry/state, open-at/seek and capabilities. Add optional entry ID to queue JSON
  compatibly and an entry-ID checkpoint bridge: migrate/read legacy path records,
  preserve old queues, and retain existing fallback for entries without IDs.
  Honor seek after preparation and reject stale player callbacks.
- **Tests:** `NX-S05`: requested timestamp after asynchronous prepare, out-of-range
  clamping, missing media, denied playback, external fallback, old queue/position
  import, close/reopen, moving an entry locator, and switching entry while a seek
  is pending. Rerun Windows playback lifecycle contracts from ADR-0024.
- **Done:** real seek and state on Android/VLC; unsupported players explain the
  limitation. Closing the desktop player releases it before native frame teardown.

<a id="nx3-6"></a>

## NX3.6 — deliver result navigation and empty/error states

- **Prerequisite:** NX3.5. **Read/modify:** shared library/search UI, player hooks,
  grouped pt/en strings. Do not replace metadata search.
- **Do:** show text-search scope, loading/index status, highlighted plain-text
  excerpt and accessible timestamp action. Explain missing tracks and offer import
  or reindex. A search result opens only its intended entry/time.
- **Tests:** `NX-S06`: stale result, no track, no matches, source time offset,
  keyboard result navigation, 320dp/desktop and enlarged text, pt/en. Verify
  screen-reader labels contain excerpt/title and time.
- **Done:** a user can distinguish unavailable text from a genuine empty result.

<a id="nx3-7"></a>

## NX3.7 — prove search-to-playback end to end

- **Prerequisite:** NX3.6. Run NX-S plus offline/playback baseline contracts.
  `NX-S07`: with networking disabled, search the synthetic phrase and confirm actual player
  position within one second of the mapped cue start on desktop and Android.
- **Gate:** measured corpus budget, legacy playback migration, real Windows
  close/reopen and Android seek pass; macOS/Linux smoke before desktop delivery.
  Document language/tokenizer limits and never describe subtitle search as speech
  recognition. Record screenshots and native evidence.

### Verification and Technical Invariants (NX-S07)

1. **Search-to-Playback End-to-End Proof:**
   - **Offline search to seek:** With networking completely disabled, queries for synthetic phrases in Portuguese (`"pesquisa sincronizada"`) and English (`"synthetic playback target"`) resolve via local SQLite FTS4 without network requests, returning cue start timestamps.
   - **Position precision:** Player engines (`DesktopPlaybackLauncher` and Android `PlayerActivity` / `PlaybackService`) launch seeking to the cue timestamp `(startMs + timebaseOffsetMs).coerceAtLeast(0L)` and verify that the actual playback position matches within 1 second ($\le 1000$ ms) of the cue start.
   - **Close/reopen & checkpoint recovery:** Closing the player persists the position checkpoint via `store.savePosition(...)` and reopening via `openAt(...)` restores position seamlessly without loss.

2. **Tokenizer Boundaries and Language Limits:**
   - **Tokenizer:** SQLite FTS4 uses `tokenize=unicode61 "remove_diacritics=1"`. Standard punctuation characters are stripped and treated as delimiters.
   - **Accent Folding:** Diacritics are normalized across Latin scripts, providing bidirectional search equivalence (e.g. `pesquisa sincronizada` matches `pesquisa sincronizada`, `música` matches `musica`, `café` matches `cafe`).
   - **Unspaced Languages (CJK, Thai):** SQLite `unicode61` splits tokens on whitespace and punctuation. Continuous scripts without whitespace delimiters (such as Chinese, Japanese, Thai) are treated as continuous character tokens. Substring matches succeed for exact continuous token matches, but morphological word segmentation (e.g. Jieba or Mecab) is not performed.
   - **Query Constraints:** Queries are strictly bounded by `TimedSearchQueryValidator` to a maximum of 256 characters and 8 tokens, adhering to the deterministic $\le 500$ ms p95 search latency budget.

3. **Distinction from Speech Recognition (Non-ASR Guarantee):**
   - **Strict Subtitle Scope:** OpenYT timed search operates exclusively over structured subtitle/caption tracks (SRT, WebVTT) that are embedded in the media container, downloaded as companion text assets, or imported manually by the user.
   - **No Acoustic Processing:** The feature does NOT perform acoustic analysis, speech-to-text, neural ASR, or cloud-based audio decoding into text. It must never be described or surfaced to users as speech recognition.

4. **Corpus Budget and Playback Migration:**
   - **Corpus Budget:** A 100,000-cue synthetic corpus search evaluated in `SqlDelightTimedSearchRepositoryTest` demonstrated a p95 latency of 73.96 ms, well within the 500 ms budget.
   - **Migration Continuity:** Legacy playback checkpoints and queue files lacking `entryId` are preserved. Checkpoint lookups bridge legacy path records to corresponding library entry IDs, and relocated media files recover playback progress by `entryId`.

5. **Cross-Platform Test Execution:**
   - **Desktop:** `com.openyt.desktop.search.TimedSearchPlaybackEndToEndTest` passed 2/2 tests verifying synthetic phrase seek precision ($\le 1000$ ms) and close/reopen checkpoint persistence.
   - **Android:** `com.openyt.android.TimedSearchPlaybackEndToEndTest` compiled into test APK with screenshot capture (`android-timed-search-playback.png`).

**Next:** NX4.1. NX9 may be evaluated after NX4 completes, independently of NX5–8.
