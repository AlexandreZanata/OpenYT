# OpenYT 4.0 documentation images

These files show the application UI, not design mockups. Captures were reviewed
on 2026-11-11. UI text is English; the product also supports Brazilian Portuguese
and follows the OS locale on new installations.

## Desktop screenshots

`desktop-library.png`, `desktop-settings.png`,
`desktop-subscription-form.png`, `desktop-sources.png` and
`desktop-media-processing.png` were captured from the locally built OpenYT 4.0.0
Linux DEB, running its packaged launcher/JVM/tools on an isolated Xvfb display.
The main window is 1,100 × 780 pixels. Separate `XDG_DATA_HOME`, `XDG_CACHE_HOME`
and `XDG_CONFIG_HOME` directories kept the user's library and preferences intact.

The library is demonstration data: two locally generated FFmpeg clips and cover
frames named **Video essentials** and **Creative workflow**, a **Learning**
collection and a smart collection. Source labels exercise the YouTube/Vimeo
identifiers; they are not claims that those demo files were downloaded from those
services. The source dialog queries the actual installed engine and shows a
YouTube search. The processing image shows the real non-destructive clip form.

`desktop-mini-player.png` was captured in the same isolated packaged-app session
while VLC decoded **Video essentials**. Its 420 × 320 pixel floating window shows
the generated demo video, player controls and the actual seek/time state.

## Android screenshot

`android-pip.png` is the unaltered **android-visual-evidence/android-pip.png**
artifact from the published-code
[CI run 43069702003](https://github.com/AlexandreZanata/OpenYT/actions/runs/43069702003),
commit `47c260c8a51cb2f9e50e4eaefdb216be42b51265`.
It shows real Media3 playback in an Android API 30 emulator's system PiP window.
The color bars are generated test media. This is not a physical-device capture.

## Source icon strips

`source-examples-light.svg` and `source-examples-dark.svg` reuse the existing
Android XML vector geometry for twelve providers. The only changes are layout,
theme color and visible brand labels. They illustrate catalog examples, not the
complete list or an affiliation with those providers. Licensing, attribution and
usage rules are in [PROVIDER_ICONS.md](../../branding/PROVIDER_ICONS.md).

The complete versioned extractor catalog is in
[SUPPORTED_SOURCES.md](../../SUPPORTED_SOURCES.md).
