# Using OpenYT

Paste a media URL, choose **Analyze**, review the available formats, and download.
The installed engine determines availability; authenticated or restricted sources
can require your authorized local cookies file. An unavailable source is reported
as an error with a recovery action, not as a successful download.

## Language and appearance

New installations follow the OS language. Portuguese OS locales use Brazilian
Portuguese; other languages currently fall back to English. Settings can override
this choice and select system, light or dark appearance. Existing saved choices
are preserved. The [brand guidelines](branding/README.md) define logo, palette,
clear space, minimum sizes and platform icon usage. Source-logo rules are in
[PROVIDER_ICONS.md](branding/PROVIDER_ICONS.md).

## Download and processing options

Expand Download options after analyzing a link. Audio presets include M4A, MP3,
Opus, FLAC and WAV. Normalization changes loudness through FFmpeg. Select a
reported subtitle language to save a sidecar or embed it in a video. Automatic
captions are identified separately. SponsorBlock marking is optional and contacts
that service only when enabled.

Live sources default to a maximum 60-second capture. Choose a duration from
1 to 86,400 seconds before starting. For an ordinary video, a start/end interval
can be previewed before downloading. In Library, Media processing creates a new
clip or audio conversion. The original is kept; lossless cuts can align to a
nearby keyframe rather than an exact frame.

## Playback

Play an available Library item or choose Continue playback to restore the saved
session. Desktop: Space pauses/plays, Left/Right jump 10 seconds, M toggles the
floating window, Escape expands it. VLC/libvlc supplies embedded desktop playback;
without it, OpenYT hands the file to the OS player. Android uses Media3, background
audio and system PiP for video. Finished items restart from the beginning.

## Local automation and handoff

Settings → Local automation adds a playlist or RSS/Atom feed. By default the first
check records existing entries without downloading them; enable Include existing
only if you want that backlog. Checks run while OpenYT is open. A failed episode
remains in Downloads for an explicit retry.

`openyt://download?url=<percent-encoded-https-url>` opens a reviewed URL in the app.
The optional browser extension only forwards the active tab. Load its unpacked
release folder through your browser's extension developer screen; no browser
store publication is implied. Suggestions for copied links and download notices
are opt-in in Settings. Clipboard reads stop when the app loses foreground focus.

## Updates, data and privacy

Application installers are opened only after signed-manifest/checksum verification
and still require OS confirmation. Settings → Update engine explicitly fetches
official stable yt-dlp and checks its SHA-256 before selecting it. FFmpeg and other
tools update with application releases.

There is no account, telemetry or remote download server. Media requests go to the
chosen source; thumbnails use their source URLs. Update checks use GitHub, and
SponsorBlock is a separately labeled opt-in request. Imported cookies are local
and encrypted at rest. Diagnostics redact URL secrets and private path roots.

Desktop data lives under the OS application-data directory (`XDG_DATA_HOME/OpenYT`
on Linux); tool caches use the OS cache directory. Android uses app-private
database/settings/cookies and the selected downloads destination. Removing a
Library record keeps its media file. Use the OS file manager to remove media
explicitly, and OS app-data controls to reset settings. Do not delete data while
downloads or media processing are running.

Flatpak limits file access to Downloads by default; grant additional folders
explicitly through your Flatpak permissions if needed. Local schedules do not
continue after the app process exits.

Desktop opens incoming links in the existing window. Close the GUI before running
CLI operations that use the engine or stored state; an exclusive lock prevents
concurrent coordinators from overwriting queue records. `--cli version` and
`--cli link` remain available while the GUI is running. CLI downloads, playlist
items and processed outputs appear in Library on the next app launch.
