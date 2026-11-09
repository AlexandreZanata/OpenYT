# ADR-0008: Library availability and duplicate detection

- Status: accepted
- Date: 2026-11-09

## Context

The library can outlive the files it references, and repeated downloads can produce
duplicate records. Detection must be fast, testable, and must never delete user files.

## Decision

- Define `FileAvailability` as a core port. Hosts implement it with `File.isFile`;
  the UI never touches the filesystem directly.
- Keep search, filters, and duplicate rules in a pure `LibraryQuery` object. This is
  unit-tested with 10,000 entries and reused by Android and desktop.
- Mark entries as missing when the host reports the path absent. Recompute when the
  library changes and when the user presses "Verify files".
- Mark entries as possible duplicates when they share an exact file path or the same
  normalized title and size. The label is advisory; the UI offers removal from the
  library only and states that the file remains on disk.
- Do not probe codecs or delete files in this phase.

## Consequences

- Detection is deterministic and responsive for thousands of entries.
- False positives are possible for the title+size rule; the wording says "possible
  duplicate" rather than asserting equivalence.
- Collections, thumbnails, sorting, and duplicate cleanup actions remain future work.
