#!/usr/bin/env python3
"""Release validation tooling: verify pinned trust and GitHub asset digest metadata."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    repository = "AlexandreZanata/OpenYT"
    root = Path(__file__).resolve().parents[1]

    def api(path):
        return json.loads(subprocess.check_output(["gh", "api", f"repos/{repository}/{path}"], text=True))

    release = api(f"releases/tags/{args.tag}")
    assert not release["draft"] and not release["prerelease"], "Release must be public and stable"
    assert api(f"commits/{args.tag}")["sha"] == args.commit, "Tag does not identify the tested commit"
    assets = {asset["name"]: asset for asset in release["assets"]}
    assert len(assets) == len(release["assets"]), "Duplicate asset names"
    assert all(asset["size"] > 0 and asset["state"] == "uploaded" for asset in assets.values())
    for suffix in (".apk", ".deb", ".rpm", ".msi", ".exe", ".dmg", ".AppImage", ".flatpak", ".pkg.tar.zst"):
        assert any(name.endswith(suffix) for name in assets), f"Missing installer {suffix}"
    assert "OpenYT-browser-handoff.zip" in assets
    for host in ("Linux", "Windows", "macOS"):
        assert f"openyt-sbom-{host}.cdx.json" in assets

    with tempfile.TemporaryDirectory(prefix="openyt-release-verification-") as directory:
        work = Path(directory)
        subprocess.run(["gh", "release", "download", args.tag, "--repo", repository,
                        "--pattern", "SHA256SUMS.txt*", "--dir", str(work)], check=True)
        certificate = root / "docs/releases/release-signing-public-key.pem"
        public_key = subprocess.check_output(["openssl", "x509", "-in", str(certificate), "-pubkey", "-noout"])
        (work / "trusted-public-key.pem").write_bytes(public_key)
        subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(work / "trusted-public-key.pem"),
                        "-signature", str(work / "SHA256SUMS.txt.sig"), str(work / "SHA256SUMS.txt")], check=True)
        checksums = {}
        for line in (work / "SHA256SUMS.txt").read_text().splitlines():
            match = re.fullmatch(r"([a-f0-9]{64}) [ *]([^/\\]+)", line)
            assert match, f"Invalid checksum entry: {line}"
            digest, name = match.groups()
            assert name not in checksums and name not in (".", ".."), "Duplicate or unsafe checksum entry"
            checksums[name] = digest
        assert checksums.keys() == assets.keys() - {"SHA256SUMS.txt", "SHA256SUMS.txt.sig"}, "Manifest asset coverage differs"
        assert checksums[certificate.name] == hashlib.sha256(certificate.read_bytes()).hexdigest(), "Published trust certificate differs"
        for name, digest in checksums.items():
            assert assets[name].get("digest") == f"sha256:{digest}", f"GitHub asset digest mismatch: {name}"
    print(f"PASS {args.tag}: tested commit, pinned RSA signature, {len(checksums)} asset digests, complete native package inventory")
    print(release["html_url"])


if __name__ == "__main__":
    main()
