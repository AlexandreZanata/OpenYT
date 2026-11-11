#!/usr/bin/env bash
# CI tooling only. Use signed Ubuntu repositories directly after mirror-list DNS failures.
set -euo pipefail
if [[ -f /etc/apt/sources.list.d/ubuntu.sources ]]; then
    sudo sed -Ei \
        -e 's|mirror\+file:/etc/apt/apt-mirrors.txt|https://archive.ubuntu.com/ubuntu|g' \
        -e 's|mirror\+file:/etc/apt/apt-mirrors-security.txt|https://security.ubuntu.com/ubuntu|g' \
        -e 's|https?://azure.archive.ubuntu.com/ubuntu|https://archive.ubuntu.com/ubuntu|g' \
        /etc/apt/sources.list.d/ubuntu.sources
fi
options=(-o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 -o Acquire::ForceIPv4=true)
sudo apt-get "${options[@]}" -o APT::Update::Error-Mode=any update
sudo apt-get "${options[@]}" install --no-install-recommends -y "$@"
