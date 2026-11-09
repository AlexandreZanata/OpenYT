# OpenYT brand guidelines

OpenYT is a local-first media downloader and library manager. The symbol combines
a playback ring, a play triangle, and a download arrow landing in a library tray.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="openyt-lockup-dark.svg">
  <img src="openyt-lockup-light.svg" width="360" alt="OpenYT logo">
</picture>

## Choosing an asset

- Use the [light-background lockup](openyt-lockup-light.svg) on paper or white.
  Use the [dark-background lockup](openyt-lockup-dark.svg) on ink or a comparably
  dark solid color. Both pair the symbol with the OpenYT wordmark.
- Use the [standalone app icon](openyt-app-icon.svg) for launchers, window icons,
  avatars, or small spaces where the product name already appears.
- Use the [monochrome symbol](openyt-symbol-mono.svg) for single-color production.
  Set all visible fills and strokes to the same ink or paper color and keep the spaces
  between them transparent.
- Use the supplied proportions, letter shapes, colors, and orientation. Place the
  artwork over a solid, quiet field. Keep additional effects and decorative
  elements outside the clear space.
- Leave clear space equal to one quarter of the icon tile's width on all sides,
  measured from the outermost visible artwork. The operating system handles
  padding and masking for its launcher and window slots.
- Use the full lockup at 160 px wide or larger for digital layouts. Use the app
  icon at 24 px or larger when you control the layout. The desktop icon export
  also includes 16 px for system-controlled slots.

## Color and typography

- **Ink** `#101010` grounds the icon and supplies text on light surfaces.
- **Paper** `#F7F7F5` is the light canvas and the wordmark color on dark surfaces.
- **Teal** `#0F766E` is the wordmark and interface accent on light surfaces.
- **Mint** `#5EEAD4` is the wordmark and interface accent on dark surfaces.

Use the gradient inside the app icon as supplied. Use teal on paper (5.10:1),
mint on ink (12.86:1), and ink on paper (17.74:1) for readable text combinations.
Use theme roles in Compose so controls follow the active light or dark theme.

Write the product name as **OpenYT**. The SVG wordmark uses vector outlines.
The application header and navigation rail render the name as text using
Material typography so it follows the device's font scale. Give standalone
branding images the accessible name “OpenYT” and mark them decorative when the
same name is already visible beside them.

## Platform files

- **Android** uses the adaptive launcher resource
  [`openyt_launcher`](../../androidApp/src/main/res/mipmap-anydpi-v26/openyt_launcher.xml)
  with separate background, full-color foreground, and monochrome layers.
  Keep the symbol centered inside the 66 dp safe region of the 108 dp layers.
  The launcher applies its own mask and colors the monochrome layer when
  themed icons are supported. [Android adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)
- **Desktop** uses [PNG for Linux](../../desktopApp/packaging/icons/openyt.png),
  [ICO for Windows](../../desktopApp/packaging/icons/openyt.ico), and
  [ICNS for macOS](../../desktopApp/packaging/icons/openyt.icns).
  These files are wired into the Compose native distributions. The running
  window uses the same shared Compose painter as the in-app brand.
  [Compose packaging icon guidance](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html#application-icon)
- **In-app** use [`BrandMark`](../../ui/src/commonMain/kotlin/com/openyt/ui/components/BrandMark.kt)
  for the logo with accessible text and
  [`BrandIconPainter`](../../ui/src/commonMain/kotlin/com/openyt/ui/components/BrandIcon.kt)
  for the standalone icon. Keep navigation and media actions on the existing
  Material icon set so their symbols describe the action.

## Updating the artwork

Start with [`openyt-app-icon.svg`](openyt-app-icon.svg) as the geometry and
color master. Update its four symbol paths in the lockups, monochrome SVG,
shared Compose vector, and Android vectors. Keep the launcher foreground inset
and apply solid white to the Android monochrome paths so the launcher can tint them.

Export a transparent 512 × 512 PNG for Linux. Export the Windows ICO with
16, 24, 32, 48, 64, 128, and 256 px images. Export a macOS ICNS with standard
and Retina representations up to 1024 px. Preserve the complete square canvas
and transparency when rendering the master. Check both lockup variants and
inspect the icon at 16, 24, and 48 px before packaging.

From the repository root, verify the Android build and Linux packaging with
`./gradlew :androidApp:assembleDebug :desktopApp:packageDeb`. Check the Windows
and macOS packages on their native hosts.
