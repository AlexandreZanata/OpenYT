# Third-party notices

OpenYT invokes or links the following third-party components. Versions are pinned
in `gradle/libs.versions.toml` and documented in the relevant ADRs.

| Component | Version | License | Usage |
|-----------|---------|---------|-------|
| yt-dlp | 2026.10.26 (desktop bundle), 2025.11.12 (Android bundle) | Unlicense | Media extraction/download engine |
| youtubedl-android | 0.18.1 | GPL-3.0 | Android wrapper bundling Python, yt-dlp, FFmpeg, QuickJS |
| Python for Android | 3.12.11 (bundled) | PSF-2.0 | Runtime required by yt-dlp |
| FFmpeg / FFprobe | 7.1.1 (Android bundle), b6.1.1 static builds (desktop bundle) | LGPL-2.1+/GPL | Merge/convert and probe media |
| Deno | 2.9.8 (desktop bundle) | MIT | JavaScript runtime required by yt-dlp EJS |
| QuickJS | bundled with wrapper | MIT | JavaScript runtime required by yt-dlp EJS |
| AndroidX / Compose | see version catalog | Apache-2.0 | UI and platform integration |
| Kotlin / kotlinx | see version catalog | Apache-2.0 | Language and libraries |
| SQLDelight | 2.4.1 | Apache-2.0 | Local SQLite schema, queries, and drivers |
| Coil | 3.4.0 | Apache-2.0 | Compose image loading for thumbnails |
| OkHttp | via Coil | Apache-2.0 | Desktop/Android HTTP image loading |
| VLCJ / JNA | 4.12.1 / transitive | GPL-3.0 / LGPL-2.1+ or Apache-2.0 | Desktop playback bridge to separately installed libvlc |
| Simple Icons | pinned cd796c98565fc466923fc1128b962e133fa3c612 | CC0-1.0 | Provider logos converted to shared XML vectors |

Because the Android build links the GPL-3.0 `youtubedl-android` library, OpenYT as a
whole is distributed under GPL-3.0. Preserve upstream notices when packaging.

Provider logos come from [Simple Icons](https://simpleicons.org) (CC0-1.0). Brand
names and logos are trademarks of their respective owners and are used only to
identify the source of the requested media.

Desktop packages embed yt-dlp, FFmpeg/FFprobe (static builds from
`eugeneware/ffmpeg-static`), and Deno at build time. The Gradle packaging task
downloads the pinned versions and includes their license files and this notice in
the application resources. An explicit Settings action can update yt-dlp from the official stable release after SHA-256 verification. Other tools stay pinned until an application release.

Sources:
- yt-dlp: https://github.com/yt-dlp/yt-dlp
- FFmpeg static builds: https://github.com/eugeneware/ffmpeg-static
- Deno: https://github.com/denoland/deno
