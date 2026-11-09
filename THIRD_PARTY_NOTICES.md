# Third-party notices

OpenYT invokes or links the following third-party components. Versions are pinned
in `gradle/libs.versions.toml` and documented in the relevant ADRs.

| Component | Version | License | Usage |
|-----------|---------|---------|-------|
| yt-dlp | 2025.11.12 (Android bundle), user-provided (desktop) | Unlicense | Media extraction/download engine |
| youtubedl-android | 0.18.1 | GPL-3.0 | Android wrapper bundling Python, yt-dlp, FFmpeg, QuickJS |
| Python for Android | 3.12.11 (bundled) | PSF-2.0 | Runtime required by yt-dlp |
| FFmpeg | 7.1.1 (Android bundle) | LGPL-2.1+/GPL | Merge/convert media |
| QuickJS | bundled with wrapper | MIT | JavaScript runtime required by yt-dlp EJS |
| AndroidX / Compose | see version catalog | Apache-2.0 | UI and platform integration |
| Kotlin / kotlinx | see version catalog | Apache-2.0 | Language and libraries |
| SQLDelight | 2.4.1 | Apache-2.0 | Local SQLite schema, queries, and drivers |
| Coil | 3.4.0 | Apache-2.0 | Compose image loading for thumbnails |
| OkHttp | via Coil | Apache-2.0 | Desktop/Android HTTP image loading |

Because the Android build links the GPL-3.0 `youtubedl-android` library, OpenYT as a
whole is distributed under GPL-3.0. Preserve upstream notices when packaging.

No third-party binary is downloaded and executed silently at runtime. Desktop
executables must be provisioned or explicitly configured by the user.
