#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(sed -n 's/^openyt.version=//p' "$ROOT/gradle.properties")"
DEB="${1:?Pass the tested deb package}"
OUTPUT="${2:-$ROOT/build/release-linux}"
mkdir -p "$OUTPUT"
OUTPUT="$(realpath "$OUTPUT")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
dpkg-deb -x "$DEB" "$WORK/AppDir"
cp "$ROOT/desktopApp/packaging/icons/openyt.png" "$WORK/AppDir/openyt.png"
cat > "$WORK/AppDir/AppRun" <<'EOF'
#!/bin/sh
APPDIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec "$APPDIR/opt/openyt/bin/OpenYT" "$@"
EOF
chmod +x "$WORK/AppDir/AppRun"
cat > "$WORK/AppDir/openyt.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=OpenYT
Exec=AppRun %u
Icon=openyt
Categories=AudioVideo;
MimeType=x-scheme-handler/openyt;
EOF
if [[ -n "${APPIMAGETOOL:-}" ]]; then
    ARCH=x86_64 "$APPIMAGETOOL" --appimage-extract-and-run "$WORK/AppDir" "$OUTPUT/OpenYT-$VERSION-x86_64.AppImage"
    chmod +x "$OUTPUT/OpenYT-$VERSION-x86_64.AppImage"
    "$OUTPUT/OpenYT-$VERSION-x86_64.AppImage" --appimage-extract-and-run --cli version
fi
if [[ "${BUILD_FLATPAK:-0}" == 1 ]]; then
    mkdir -p "$WORK/flatpak"
    cp -a "$WORK/AppDir/opt/openyt" "$WORK/flatpak/openyt"
    cp "$ROOT/desktopApp/packaging/flatpak/com.openyt.OpenYT.yml" "$WORK/flatpak/"
    cp "$WORK/AppDir/openyt.png" "$WORK/flatpak/"
    (cd "$WORK/flatpak" && flatpak-builder --force-clean --user --install-deps-from=flathub --repo=repo build com.openyt.OpenYT.yml)
    # Preserve the verified, prebuilt tools (including self-contained archives).
    for tool in yt-dlp ffmpeg ffprobe deno; do
        cmp "$WORK/AppDir/opt/openyt/lib/app/resources/$tool" "$WORK/flatpak/build/files/lib/openyt/lib/app/resources/$tool"
    done
    (cd "$WORK/flatpak" && flatpak-builder --run build com.openyt.OpenYT.yml \
        env XDG_DATA_HOME=/tmp/openyt-package-contract/data XDG_CACHE_HOME=/tmp/openyt-package-contract/cache XDG_CONFIG_HOME=/tmp/openyt-package-contract/config \
        /app/lib/openyt/bin/OpenYT --cli sources --json) > "$WORK/flatpak-sources.json"
    python3 - "$WORK/flatpak-sources.json" <<'PY'
import json, sys
with open(sys.argv[1]) as source:
    assert len(json.load(source)) > 100, "Packaged Flatpak engine catalog is missing"
PY
    echo 'PASS Flatpak tool byte preservation and sandboxed engine catalog'
    flatpak build-bundle "$WORK/flatpak/repo" "$OUTPUT/OpenYT-$VERSION.flatpak" com.openyt.OpenYT --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo
fi
DEB_NAME="$(basename "$DEB")"
DEB_SHA="$(sha256sum "$DEB" | cut -d' ' -f1)"
sed -e "s/@VERSION@/$VERSION/g" -e "s/@DEB@/$DEB_NAME/g" -e "s/@SHA256@/$DEB_SHA/g" \
    "$ROOT/desktopApp/packaging/aur/PKGBUILD.in" > "$OUTPUT/PKGBUILD"
