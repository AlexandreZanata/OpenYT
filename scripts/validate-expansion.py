#!/usr/bin/env python3
"""Test tooling only: exercise the extracted Linux package against local fixtures."""
import functools
import hashlib
import http.server
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading


def main():
    package = Path(sys.argv[1]).resolve()
    previous = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else None
    with tempfile.TemporaryDirectory(prefix="openyt-expansion-") as work:
        root = Path(work)
        prefix = root / "prefix"
        env = os.environ | {f"XDG_{key}_HOME": str(root / key.lower()) for key in ("DATA", "CACHE", "CONFIG")}
        subprocess.run(["dpkg-deb", "-x", str(previous or package), str(prefix)], check=True)
        app = prefix / "opt/openyt/bin/OpenYT"
        fixture = root / "fixtures"
        fixture.mkdir()
        ffmpeg = prefix / "opt/openyt/lib/app/resources/ffmpeg"
        ffmpeg.chmod(0o755)
        media = fixture / "Original ação.mp4"
        subprocess.run([str(ffmpeg), "-v", "error", "-f", "lavfi", "-i", "testsrc=duration=4:size=160x90:rate=5", "-f", "lavfi", "-i", "sine=duration=4", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", str(media)], check=True)
        original = hashlib.sha256(media.read_bytes()).hexdigest()

        def cli(*args, success=True):
            result = subprocess.run([str(app), "--cli", *args], env=env, text=True, capture_output=True, timeout=180)
            assert (result.returncode == 0) == success, (args, result.returncode, result.stdout, result.stderr)
            return json.loads(result.stdout) if result.stdout.strip() else None

        cli("library", "--scan", str(fixture), "--json")
        db = root / "data/OpenYT/openyt.db"
        with sqlite3.connect(db) as connection:
            assert connection.execute("SELECT COUNT(*) FROM library_entry").fetchone()[0] == 1
        if previous:
            subprocess.run(["dpkg-deb", "-x", str(package), str(prefix)], check=True)
            cli("library", "--list", "--json")
            print("PASS in-place package upgrade preserves the library")
        with sqlite3.connect(db) as connection:
            assert connection.execute("PRAGMA user_version").fetchone()[0] == 18
            assert connection.execute("SELECT COUNT(*) FROM library_entry").fetchone()[0] == 1
        visible = [p for p in prefix.glob("opt/openyt/lib/*.desktop") if "NoDisplay=true" not in p.read_text()]
        assert len(visible) == 1, visible
        print("PASS migrations and single launcher")
        converted = cli("media", "--path", str(media), "--operation", "audio", "--audio-preset", "opus", "--normalize", "--json")
        assert Path(converted["path"]).is_file()
        trimmed = cli("media", "--path", str(media), "--operation", "trim", "--start", "1", "--end", "3", "--json")
        assert Path(trimmed["path"]).is_file()
        assert hashlib.sha256(media.read_bytes()).hexdigest() == original
        print("PASS packaged FFmpeg conversion and non-destructive trim")
        handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(fixture))
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            base = f"http://127.0.0.1:{server.server_port}"
            def feed(ids):
                (fixture / "feed.xml").write_text("<rss><channel>" + "".join(f'<item><guid>{i}</guid><title>Episode {i}</title><enclosure url="{base}/Original%20a%C3%A7%C3%A3o.mp4"/></item>' for i in ids) + "</channel></rss>")
            feed(["old"])
            cli("watch", "--add", "--kind", "feed", "--name", "Fixture feed", "--url", base + "/feed.xml")
            cli("watch", "--check", "--dir", str(root / "downloads"))
            with sqlite3.connect(db) as connection:
                assert connection.execute("SELECT COUNT(*) FROM download_task").fetchone()[0] == 0
            feed(["old", "new"])
            cli("watch", "--check", "--dir", str(root / "downloads"))
            cli("watch", "--check", "--dir", str(root / "downloads"))
            with sqlite3.connect(db) as connection:
                tasks = connection.execute("SELECT status FROM download_task").fetchall()
                assert tasks == [("COMPLETED",)], tasks
            print("PASS RSS baseline, new episode download, persisted deduplication")
            (fixture / "feed.xml").write_text(f'<rss><channel><item><guid>broken</guid><title>Broken</title><enclosure url="{base}/missing.mp4"/></item></channel></rss>')
            cli("watch", "--check", "--dir", str(root / "downloads"), success=False)
            print("PASS failed automated download returns nonzero")
        finally:
            server.shutdown()


if __name__ == "__main__":
    main()
