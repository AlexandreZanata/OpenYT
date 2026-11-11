# ADR-0023: Image galleries and a second engine

- Status: accepted, alternatives rejected for this release
- Date: 2026-11-11

## Decision

Do not advertise generic image/gallery downloading as `MediaKind.IMAGE`.
yt-dlp's documented contract is audio/video extraction, subtitles and associated
thumbnails. Thumbnail export is not a general image-gallery engine. Source-specific
behavior cannot justify presenting a reliable image kind across the installed
catalog. Program D4 explicitly permits this documented rejection.

The second-engine spike considers gallery-dl, you-get, Streamlink, and aria2.
gallery-dl is designed for image galleries but adds a Python engine/runtime outside
the permitted yt-dlp runtime. you-get and Streamlink have the same extra Python
runtime conflict. aria2 transports files but does not provide the required media
inspection, format, and playlist extraction contract. Wrapping yt-dlp twice would
not constitute a second engine. None is exposed as a working engine selector.
Program G1 explicitly permits rejecting the spike with a documented decision.

Revisit this decision only with an approved runtime policy change, a concrete
source requirement, and shared contract tests. `DownloadEngine` remains replaceable.

Primary references reviewed on 2026-11-11:

- [yt-dlp options and supported behavior](https://github.com/yt-dlp/yt-dlp)
- [gallery-dl installation requirements](https://github.com/mikf/gallery-dl/wiki/Installation)
- [you-get project](https://github.com/soimort/you-get)
- [Streamlink project](https://github.com/streamlink/streamlink)
- [aria2 project](https://github.com/aria2/aria2)
