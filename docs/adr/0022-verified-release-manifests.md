# ADR-0022: Verified release manifests and available signatures

- Status: accepted
- Date: 2026-11-11

## Decision

Every new release contains a CycloneDX dependency inventory, SHA-256 manifest,
and detached RSA signature over the exact manifest bytes. The existing Android
production key signs the manifest. Only its public X.509 certificate is embedded
in the application. Private credentials remain local or in GitHub Actions secrets.

Assisted installers require the manifest signature and an exact, unique checksum
entry before opening the OS installer. Downloads use a temporary file and reject
unsafe names, duplicate manifest entries, missing signatures, and mismatched
checksums. OS confirmation is still required. The signature authenticates the
manifest; it does not replace Windows Authenticode or Apple notarization.

The user explicitly selected publication with the currently available signatures.
Android has a stable production signature. Windows and macOS installers currently
have no organization certificate/notarization credentials. Release notes must
state that limitation. Native CI installs the MSI and launches the mounted/copied
macOS bundle; this does not prove a certificate or a manual GUI session exists.

The explicit engine-update action downloads only official stable yt-dlp assets,
checks the official SHA2-256SUMS entry before replacement, and keeps other bundled
tools pinned. Desktop checks its cached executable against the stored digest before
selecting it. Explicit environment overrides retain precedence for diagnostics.
Engine updates are scoped to the application version so an old runtime cache cannot
override a newer bundled engine after an app upgrade. An HTTPS checksum provides transport/file integrity; it is not a substitute for
an independently signed upstream update channel.

Android downloader and local FFmpeg ports share one locked runtime initializer.
It applies the application-version cache policy before either wrapper singleton
starts, preventing a later engine initialization from deleting a runtime already
loaded by local processing. Instrumentation executes yt-dlp after processing in
the same process without network access.

## Validation

Deterministic tests reject tampered installers and signatures. A synthetic
manifest signed with the production key proves the pinned public certificate
matches the available signing key. Real-network tool-update tests are kept
separate from deterministic tests. Release assets are published only after native
packaging jobs succeed.

Linux FFmpeg is updated to the checksum-pinned retained monthly GPL 8.1.3 build
from FFmpeg-Builds, after the previous static 6.1.1 binary crashed on the real HLS
fixture. The replacement passed the same capture and conversion tests. Windows
retains its existing pinned build. macOS uses checksum-pinned GPL 9.0.2 static
builds from Martin Riedl, after the previous Apple Silicon binary failed DASH
section downloads. The published configuration enables libxml2/DASH and contains
no nonfree switch; notices record the source and GPL compatibility check.
All three hosts run the same native media contracts in release CI. Per-host
CycloneDX inventories probe actual executable versions rather than assuming that
a distribution tag matches each platform's binary.

Flatpak packages preserve the prebuilt executables without ELF debug splitting or
stripping. Packaging compares every bundled media/runtime tool byte-for-byte and
executes the source catalog in the build sandbox; release CI also checks the
installed bundle in its runtime sandbox. This avoids transforming self-contained
tool archives during distribution.
