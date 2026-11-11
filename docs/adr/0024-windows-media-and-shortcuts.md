# ADR-0024: Windows media actions and self-contained playback

- Status: accepted
- Date: 2026-11-11

## Context

Windows users reported that Play and Show in folder did nothing, and installation
did not create a desktop shortcut. The previous reveal implementation used AWT's
optional BROWSE_FILE_DIR operation and only supplied a Linux fallback. Shared UI
ignored false action results. Playback required a separately installed VLC or a
working system file association. The Windows installer configured an icon, but
did not request desktop or Start-menu shortcuts.

## Decision

- Use the existing VLCJ/JNA dependencies. Windows open uses Unicode ShellExecuteW;
  reveal uses SHParseDisplayName and SHOpenFolderAndSelectItems with the absolute
  item PIDL, count zero, initialized COM STA, and explicit memory cleanup. File
  names never enter a shell command. Opening a folder is followed by bounded COM
  confirmation of its actual selection. If needed, ShellFolderView.SelectItem
  selects/focuses the item once the view has loaded. Three consecutive observations
  must retain the selection; all COM proxies are released on the same STA.
- Keep native shell calls on a dedicated daemon STA executor. Shared file-action
  buttons dispatch blocking operations off the UI thread, prevent duplicate
  clicks while busy, and report failures with localized recovery instructions.
  Both Library and Downloads use this component. External playback reuses the
  same MediaActions adapter.
- Bundle the unmodified official **VLC 3.0.24 Windows x64** core DLLs/plugins,
  excluding DVD/Blu-ray access. Prefer those DLLs over installed VLC. Linux and
  macOS retain discovery of separately installed libvlc and system-player fallback.
  Clear inherited VLC_PLUGIN_PATH in the MSVCRT environment used by the bundled
  VLC before discovery; plugins resolve beside the trusted core DLL.
- Verify archive SHA-256 before extraction, reject archive traversal, retain
  COPYING/AUTHORS/THANKS and LGPL text, link corresponding source, and include
  libVLC's version and DLL digest in the Windows CycloneDX inventory.
- Request desktop and Start-menu shortcuts from jpackage for both MSI and EXE,
  using the existing branded ICO and the existing upgrade identity.

## Dependency review

This adds native binaries for the existing playback backend, not another product
runtime or extraction engine. Product integration remains Kotlin. The library is
LGPL-2.1-or-later; the distribution/plugins include GPL components and notices.
OpenYT remains GPL-3.0. Licenses and corresponding-source links ship with the
runtime. No codecs are reimplemented.

The 3.0.24 security release and its bulletin were reviewed on 2026-11-11.
The official OSUOSL mirror provides the unchanged VideoLAN ZIP, pinned to
`fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323`.
No TLS checks are disabled. The runtime loads only from the application resource
directory when bundled; no user-writable search directory is added.

References:

- [VideoLAN 3.0.24 security bulletin](https://images.videolan.org/security/sb-vlc3024.html)
- [VideoLAN redistribution documentation](https://docs.videolan.me/vlc-user/en/support/faq/legalconcerns.html)
- [VLC 3.0.24 corresponding source](https://download.videolan.org/pub/videolan/vlc/3.0.24/vlc-3.0.24.tar.xz)
- [SHOpenFolderAndSelectItems](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shopenfolderandselectitems)
- [Compose native distribution options](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)

## Evidence and limits

The [Windows regression contracts](../testing/WINDOWS_DESKTOP_CONTRACTS.md) exercise
both actual installers, shortcut targets/icons/launch, a controlled file handler,
Explorer selection and decoded video through the packaged JVM/jars/native DLLs.
The test harness is not included in release installers. Runner evidence does not
claim a manual session on every Windows edition, GPU or user machine.

Local Windows 11 validation exposed a further first-open regression: a new folder
containing only MP4 video returned S_OK from SHOpenFolderAndSelectItems but lost
its selection while Explorer loaded the video view. Both the zero-count form and
an explicit parent/child PIDL reproduced it; an unknown extension or TXT did not.
The COM view confirmation avoids treating an accepted shell request as completed
selection. The native contracts include a fresh, video-only folder for each
installer and inspect Explorer's actual SelectedItems.
