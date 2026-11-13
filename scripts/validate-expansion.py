#!/usr/bin/env python3
"""Test tooling only: exercise the extracted Linux package against local fixtures."""
import functools
import hashlib
import http.server
import json
import os
import signal
import time
import urllib.parse
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading


def expected_schema_version() -> int:
    db_dir = Path("data/src/commonMain/sqldelight/com/openyt/data/db")
    versions = [int(p.stem) for p in db_dir.glob("*.sqm") if p.stem.isdigit()]
    return (max(versions) + 1) if versions else 24


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
            assert connection.execute("PRAGMA user_version").fetchone()[0] == expected_schema_version()
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
        requested = []
        class RecordingHandler(http.server.SimpleHTTPRequestHandler):
            def do_GET(self):
                requested.append(self.path)
                super().do_GET()
            def do_HEAD(self):
                requested.append(self.path)
                super().do_HEAD()
        handler = functools.partial(RecordingHandler, directory=str(fixture))
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
            cli("download", "--url", base + "/Original%20a%C3%A7%C3%A3o.mp4", "--dir", str(root / "direct"), "--json")
            with sqlite3.connect(db) as connection:
                assert connection.execute("SELECT COUNT(*) FROM library_entry").fetchone()[0] == 5
                first_entry_id = connection.execute("SELECT id FROM library_entry ORDER BY rowid ASC LIMIT 1").fetchone()[0]
            print("PASS direct CLI downloads persist their Library record before exit")
            link_proc = subprocess.run([str(app), "--cli", "link", "--entry", first_entry_id, "--time-ms", "1500"], env=env, text=True, capture_output=True, timeout=180)
            assert link_proc.returncode == 0, (link_proc.returncode, link_proc.stdout, link_proc.stderr)
            entry_link = link_proc.stdout.strip()
            expected_prefix = f"openyt://library/entry/{urllib.parse.quote(first_entry_id, safe='')}"
            assert entry_link == f"{expected_prefix}?t=1500", entry_link
            print("PASS CLI generates percent-encoded local-entry links")
            if os.environ.get("DISPLAY"):
                log = (root / "gui.log").open("w")
                gui = subprocess.Popen([str(app)], env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    owner = root / "data/OpenYT/instance.owner"
                    owner_deadline = time.monotonic() + 60
                    while (not owner.exists() or owner.read_text() != "gui") and time.monotonic() < owner_deadline:
                        assert gui.poll() is None, "GUI exited before acquiring state ownership"
                        time.sleep(.1)
                    assert owner.read_text() == "gui"
                    desktop_handler = root / "data/applications/openyt-link.desktop"
                    handler_deadline = time.monotonic() + 60
                    while (not desktop_handler.is_file() or "x-scheme-handler/openyt" not in desktop_handler.read_text()) and time.monotonic() < handler_deadline:
                        assert gui.poll() is None, "GUI exited while registering desktop deep links"
                        time.sleep(.1)
                    assert desktop_handler.is_file() and "x-scheme-handler/openyt" in desktop_handler.read_text(), (
                        f"Missing {desktop_handler}; gui.log:\n" + (root / "gui.log").read_text(errors="replace")
                    )
                    cli("library", "--list", "--json", success=False)
                    url = base + "/Original%20a%C3%A7%C3%A3o.mp4?handoff=1"
                    link = "openyt://download?url=" + urllib.parse.quote(url, safe="")
                    handoff = subprocess.run([str(app), link], env=env, capture_output=True, text=True, timeout=30)
                    assert handoff.returncode == 0, handoff.stderr
                    handoff_deadline = time.monotonic() + 60
                    while not any("handoff=1" in request for request in requested) and time.monotonic() < handoff_deadline:
                        assert gui.poll() is None, "Existing GUI exited during handoff"
                        time.sleep(.1)
                    assert any("handoff=1" in request for request in requested), (
                        f"Existing GUI did not analyze the handed-off URL; requested={requested}; gui.log:\n"
                        + (root / "gui.log").read_text(errors="replace")
                    )
                    entry_handoff = subprocess.run([str(app), entry_link], env=env, capture_output=True, text=True, timeout=30)
                    assert entry_handoff.returncode == 0, entry_handoff.stderr
                    time.sleep(.3)
                    assert gui.poll() is None, "Existing GUI exited during local-entry link handoff"
                    print("PASS GUI handoff reuses the existing coordinator for download and local-entry links")
                finally:
                    if gui.poll() is None:
                        os.killpg(gui.pid, signal.SIGTERM)
                        gui.wait(timeout=15)
                    log.close()

        finally:
            server.shutdown()


if __name__ == "__main__":
    main()
