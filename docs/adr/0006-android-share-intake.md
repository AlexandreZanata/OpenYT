# ADR-0006: Android share-sheet intake

- Status: accepted
- Date: 2026-11-09

## Context

Users should be able to share a media link from another Android app without pasting.
The app must not become a browser replacement, and MIUI/OEM share resolvers behave
differently from AOSP.

## Decision

- Register `MainActivity` for `ACTION_SEND` with MIME type `text/plain` and
  `launchMode="singleTop"`.
- Read `Intent.EXTRA_TEXT`, extract the first http(s) URL with `ShareTextParser`,
  validate it with `UrlValidator`, then prefill the Home field and inspect it
  automatically.
- Do not register for `ACTION_VIEW`/http(s); that would make OpenYT a browser target.
- `onNewIntent` handles shares while the activity is already open; a `StateFlow`
  carries the pending URL into Compose and is cleared after inspection.

## Consequences

- Sharing a link reaches a confirmation screen with real metadata/formats.
- Only plain-text shares are supported in this phase; file/URI shares and
  `SEND_MULTIPLE` are out of scope.
- OEM resolvers may route `ACTION_SEND` through a chooser or a default target. The
  intent filter is present and verified; automated device validation targets the
  package explicitly.
