#!/usr/bin/env bash
# CI tooling only. Select a reachable Ubuntu mirror; APT still checks Ubuntu signatures.
set -euo pipefail
codename=$(awk -F= '$1 == "VERSION_CODENAME" {gsub(/"/, "", $2); print $2}' /etc/os-release)
selected_mirror=
for mirror in https://mirrors.mit.edu/ubuntu https://mirror.math.princeton.edu/pub/ubuntu https://mirrors.ocf.berkeley.edu/ubuntu https://archive.ubuntu.com/ubuntu; do
    if curl -fsSL --connect-timeout 5 --max-time 10 "$mirror/dists/$codename/InRelease" -o /dev/null; then
        selected_mirror=$mirror
        break
    fi
done
[[ -n "$selected_mirror" ]] || { echo 'No Ubuntu package mirror is reachable' >&2; exit 1; }
echo "Using Ubuntu mirror: $selected_mirror ($codename)"
# The runner restores its global sources after update. Keep update/install on the
# same isolated source/index configuration instead of editing runner-owned files.
apt_work=$(mktemp -d "${RUNNER_TEMP:-/tmp}/openyt-apt.XXXXXX")
trap 'sudo rm -rf -- "$apt_work"' EXIT
chmod 755 "$apt_work"
mkdir -p "$apt_work/lists/partial"
for suite in "$codename" "$codename-updates" "$codename-security" "$codename-backports"; do
    printf 'deb [signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] %s %s main universe restricted multiverse\n' "$selected_mirror" "$suite"
done > "$apt_work/sources.list"
options=(-o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 -o Acquire::ForceIPv4=true
    -o "Dir::Etc::sourcelist=$apt_work/sources.list" -o Dir::Etc::sourceparts=-
    -o "Dir::State::lists=$apt_work/lists" -o "Dir::Cache::pkgcache=$apt_work/pkgcache.bin"
    -o "Dir::Cache::srcpkgcache=$apt_work/srcpkgcache.bin")
sudo apt-get "${options[@]}" -o APT::Update::Error-Mode=any update
if ! sudo apt-get "${options[@]}" install --no-install-recommends -y "$@"; then
    dpkg --print-architecture
    cat "$apt_work/sources.list"
    sudo apt-cache "${options[@]}" policy "$@"
    find "$apt_work/lists" -maxdepth 1 -type f -printf '%f %s bytes\n'
    exit 1
fi
