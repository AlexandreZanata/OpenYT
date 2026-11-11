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
sudo sed -Ei "s|^URIs:.*|URIs: $selected_mirror|" /etc/apt/sources.list.d/ubuntu.sources
options=(-o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 -o Acquire::ForceIPv4=true)
sudo apt-get "${options[@]}" -o APT::Update::Error-Mode=any update
if ! sudo apt-get "${options[@]}" install --no-install-recommends -y "$@"; then
    dpkg --print-architecture
    cat /etc/apt/sources.list.d/ubuntu.sources
    apt-cache policy "$@"
    find /var/lib/apt/lists -maxdepth 1 -type f -printf '%f %s bytes\n'
    exit 1
fi
