# ADR-0009: Media thumbnails with Coil

- Status: accepted
- Date: 2026-11-09

## Context

Users need to confirm they are downloading the intended media. yt-dlp already
returns a thumbnail URL in its metadata, but the UI did not display it.

## Decision

- Use Coil 3.4.0 with `coil-compose` and `coil-network-okhttp` on Android and JVM
  desktop. Network URLs are supported by adding the OkHttp artifact.
- Add a shared `MediaThumbnail` composable with a neutral placeholder icon. Images
  are decorative (`contentDescription = null`) because the title is adjacent.
- Show thumbnails in the inspect result, Downloads cards, and Library cards.
- Persist `library_entry.thumbnail_url` (schema v4). The migration backfills it from
  `download_task.thumbnail_url`, so existing entries gain thumbnails.
- Never proxy thumbnails through a backend. They are fetched directly from the
  source URL and may be cached by Coil.

## Consequences

- Visual identification works on both platforms with one shared implementation.
- Offline/uncached entries show the placeholder; no permanent thumbnail store exists.
- Thumbnail requests go to the media source and can reveal the media ID to that
  source, consistent with downloading the media itself.
- Coil and OkHttp are Apache-2.0 and are listed in `THIRD_PARTY_NOTICES.md`.
