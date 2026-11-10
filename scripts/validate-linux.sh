#!/usr/bin/env bash
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(sed -n 's/^openyt.version=//p' "$ROOT/gradle.properties" | head -1)"
SCENARIO="all"
BUILD=1
OFFLINE=0
DEB="$ROOT/desktopApp/build/compose/binaries/main/deb/openyt_${VERSION}_amd64.deb"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --scenario) SCENARIO="${2:?missing scenario}"; shift 2 ;;
        --deb) DEB="${2:?missing deb path}"; shift 2 ;;
        --no-build) BUILD=0; shift ;;
        --offline) OFFLINE=1; shift ;;
        -h|--help)
            echo "usage: $0 [--scenario all|tests|tools|inspect|download|playlist|pause|metadata|cookies|library|archive|watch|gui]"
            echo "          [--deb path] [--no-build] [--offline]"
            exit 0
            ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/openyt-validate.XXXXXX")"
PREFIX="$WORK/prefix"
APP="$PREFIX/opt/openyt/bin/OpenYT"
export XDG_DATA_HOME="$WORK/data"
export XDG_CACHE_HOME="$WORK/cache"
export XDG_CONFIG_HOME="$WORK/config"
mkdir -p "$XDG_DATA_HOME" "$XDG_CACHE_HOME" "$XDG_CONFIG_HOME" "$ROOT/build/validation"
trap 'rm -rf "$WORK"' EXIT

FAILED=0
SKIPPED=0

pass() { echo "  PASS $*"; }
fail() { echo "  FAIL $*" >&2; return 1; }
skip() { echo "  SKIP $*"; SKIPPED=$((SKIPPED + 1)); return 0; }

run() {
    local name="$1"; shift
    echo "== $name"
    if "$@"; then
        pass "$name"
    else
        fail "$name"
        FAILED=$((FAILED + 1))
        return 1
    fi
}

run_after_prepare() {
    local name="$1"; shift
    run "prepare" prepare
    if [[ "$FAILED" -eq 0 ]]; then
        run "$name" "$@"
    fi
}

prepare() {
    if [[ "$BUILD" == "1" ]]; then
        (cd "$ROOT" && ./gradlew :desktopApp:packageDeb --console=plain) || return 1
    fi
    [[ -f "$DEB" ]] || { fail "missing deb: $DEB"; return 1; }
    rm -rf "$PREFIX"
    mkdir -p "$PREFIX"
    dpkg-deb -x "$DEB" "$PREFIX" || return 1
    [[ -x "$APP" ]] || chmod +x "$APP" 2>/dev/null || true
    [[ -x "$APP" ]] || { fail "missing executable: $APP"; return 1; }
    pass "extracted $DEB"
}

scenario_tests() {
    (cd "$ROOT" && ./gradlew \
        :core:allTests \
        :engine-ytdlp:allTests \
        :data:desktopTest \
        :desktopApp:test \
        --console=plain)
}

scenario_tools() {
    local resources="$PREFIX/opt/openyt/lib/app/resources"
    local tool
    for tool in yt-dlp ffmpeg ffprobe deno; do
        [[ -s "$resources/$tool" ]] || { fail "missing bundled $tool"; return 1; }
    done
    local reported
    reported="$("$APP" --cli version)" || { fail "CLI version failed"; return 1; }
    [[ "$reported" == "$VERSION" ]] || { fail "version mismatch: $reported != $VERSION"; return 1; }
    pass "bundled tools present and CLI reports $reported"
}

scenario_inspect() {
    if [[ "$OFFLINE" == "1" ]]; then skip "inspect (offline)"; return 0; fi
    local output
    output="$("$APP" --cli inspect --url "https://archive.org/details/BigBuckBunny_124" --json)" \
        || { fail "inspect command failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data.get("ok") is True, data
inspection = data["inspection"]
assert inspection["type"] == "media", inspection
assert inspection["media"]["title"], inspection
print("  title:", inspection["media"]["title"])
' || { fail "inspect payload invalid"; return 1; }
    pass "real inspect via packaged image"
}

scenario_download() {
    if [[ "$OFFLINE" == "1" ]]; then skip "download (offline)"; return 0; fi
    local directory="$WORK/downloads"
    mkdir -p "$directory"
    local output
    output="$("$APP" --cli download \
        --url "https://www.youtube.com/watch?v=jNQXAC9IVRw" \
        --kind video --priority high --dir "$directory" --json)" \
        || { fail "download command failed"; return 1; }
    local file
    file="$(echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data.get("ok") is True, data
print(data["task"]["filePath"])
')" || { fail "download payload invalid"; return 1; }
    [[ -f "$file" && -s "$file" ]] || { fail "missing downloaded file: $file"; return 1; }
    pass "downloaded $(basename "$file")"

    local limited="$WORK/downloads-limited"
    mkdir -p "$limited"
    output="$("$APP" --cli download \
        --url "https://www.youtube.com/watch?v=jNQXAC9IVRw" \
        --kind video --limit-bytes-per-second 200000 --dir "$limited" --json)" \
        || { fail "limited download command failed"; return 1; }
    file="$(echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data.get("ok") is True, data
print(data["task"]["filePath"])
')" || { fail "limited download payload invalid"; return 1; }
    [[ -f "$file" && -s "$file" ]] || { fail "missing limited file: $file"; return 1; }
    pass "downloaded $(basename "$file") with speed limit"
}

scenario_playlist() {
    if [[ "$OFFLINE" == "1" ]]; then skip "playlist (offline)"; return 0; fi
    local directory="$WORK/playlist"
    mkdir -p "$directory"
    local output
    output="$("$APP" --cli playlist \
        --url "https://www.youtube.com/@jawed" \
        --kind video --limit 1 --dir "$directory" --json)" \
        || { fail "playlist command failed"; return 1; }
    local file
    file="$(echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data.get("ok") is True, data
tasks = data["tasks"]
assert tasks, data
print(tasks[0]["filePath"])
')" || { fail "playlist payload invalid"; return 1; }
    [[ -f "$file" && -s "$file" ]] || { fail "missing playlist file: $file"; return 1; }
    [[ "$file" == *"/jawed - Videos/01 - "* ]] \
        || { fail "unexpected playlist layout: $file"; return 1; }

    local channel="$WORK/playlist-channel"
    mkdir -p "$channel"
    output="$("$APP" --cli playlist \
        --url "https://www.youtube.com/@jawed" \
        --kind video --limit 1 --preset channel --dir "$channel" --json)" \
        || { fail "channel preset command failed"; return 1; }
    file="$(echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data.get("ok") is True, data
print(data["tasks"][0]["filePath"])
')" || { fail "channel preset payload invalid"; return 1; }
    [[ -f "$file" && -s "$file" ]] || { fail "missing channel preset file: $file"; return 1; }
    [[ "$file" == *"/jawed/01 - "* ]] \
        || { fail "unexpected channel preset layout: $file"; return 1; }
    pass "playlist layout $(basename "$file") and channel preset"
}

scenario_pause() {
    if [[ "$OFFLINE" == "1" ]]; then skip "pause/resume (offline)"; return 0; fi
    local resources="$PREFIX/opt/openyt/lib/app/resources"
    local tools="$WORK/tools"
    mkdir -p "$tools"
    local tool
    for tool in yt-dlp ffmpeg ffprobe deno; do
        cp "$resources/$tool" "$tools/$tool" || { fail "missing $tool"; return 1; }
        chmod +x "$tools/$tool"
    done
    (cd "$ROOT" && \
        OPENYT_RUN_NETWORK_TESTS=1 \
        OPENYT_YTDLP="$tools/yt-dlp" \
        OPENYT_FFMPEG="$tools/ffmpeg" \
        OPENYT_FFPROBE="$tools/ffprobe" \
        OPENYT_JS_RUNTIME="$tools/deno" \
        ./gradlew :desktopApp:test \
            --tests '*DesktopYtDlpEngineIntegrationTest.pausesAndResumesRealDownload' \
            --rerun-tasks --no-daemon --console=plain)
}

scenario_cookies() {
    local fixture="$WORK/cookies.txt"
    cat > "$fixture" <<'COOKIES'
# Netscape HTTP Cookie File
.openyt.invalid	TRUE	/	FALSE	0	OPENYT_CANARY	canary-value
COOKIES

    local output
    output="$("$APP" --cli cookies --import "$fixture" --json)" \
        || { fail "cookie import failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data.get("ok") is True and data.get("configured") is True, data
' || { fail "cookie import payload invalid"; return 1; }

    local encrypted="$XDG_DATA_HOME/OpenYT/cookies/cookies.enc"
    [[ -f "$encrypted" ]] || { fail "encrypted cookie file missing"; return 1; }
    if grep -a -q 'canary-value' "$encrypted"; then
        fail "cookies were stored in plaintext"
        return 1
    fi

    output="$("$APP" --cli cookies --check --json)" \
        || { fail "cookie check failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data.get("configured") is True, data
' || { fail "cookie check payload invalid"; return 1; }

    output="$("$APP" --cli cookies --clear --json)" \
        || { fail "cookie clear failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data.get("configured") is False, data
' || { fail "cookie clear payload invalid"; return 1; }
    [[ ! -f "$encrypted" ]] || { fail "encrypted cookies were not removed"; return 1; }
    pass "cookies encrypted at rest, checked and cleared"
}

scenario_metadata() {
    local resources="$PREFIX/opt/openyt/lib/app/resources"
    local tools="$WORK/tools"
    mkdir -p "$tools"
    local tool
    for tool in ffmpeg ffprobe; do
        cp "$resources/$tool" "$tools/$tool" || { fail "missing $tool"; return 1; }
        chmod +x "$tools/$tool"
    done
    (cd "$ROOT" && \
        OPENYT_RUN_MEDIA_TESTS=1 \
        OPENYT_FFMPEG="$tools/ffmpeg" \
        OPENYT_FFPROBE="$tools/ffprobe" \
        ./gradlew :desktopApp:test \
            --tests '*DesktopMediaTaggerTest' \
            --rerun-tasks --no-daemon --console=plain)
}

scenario_library() {
    local output
    output="$("$APP" --cli library --list --json)" \
        || { fail "library command failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data.get("ok") is True, data
assert isinstance(data.get("entries"), list), data
' || { fail "library payload invalid"; return 1; }

    local db="$XDG_DATA_HOME/OpenYT/openyt.db"
    [[ -f "$db" ]] || { fail "library database missing"; return 1; }
    python3 - "$db" <<'PY' || { fail "seeding library entry failed"; return 1; }
import sqlite3, sys
connection = sqlite3.connect(sys.argv[1])
connection.execute(
    """
    INSERT OR REPLACE INTO library_entry(
        id, title, author, kind, file_path, size_bytes, created_at, thumbnail_url, source
    ) VALUES ('tag-entry', 'Tag entry', 'Jawed Karim', 'AUDIO', '/tmp/tag-entry.m4a', 1, 0, NULL, 'soundcloud')
    """
)
connection.commit()
PY

    output="$("$APP" --cli library --entry tag-entry --add-tag music --json)" \
        || { fail "adding tag failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
entry = next(item for item in data["entries"] if item["id"] == "tag-entry")
assert entry["tags"] == ["music"], entry
' || { fail "tag add payload invalid"; return 1; }

    output="$("$APP" --cli library --entry tag-entry --remove-tag music --json)" \
        || { fail "removing tag failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
entry = next(item for item in data["entries"] if item["id"] == "tag-entry")
assert entry["tags"] == [], entry
' || { fail "tag remove payload invalid"; return 1; }

    "$APP" --cli library --entry tag-entry --add-tag music --json >/dev/null \
        || { fail "re-adding tag failed"; return 1; }
    python3 - "$db" <<'PY' || { fail "seeding second library entry failed"; return 1; }
import sqlite3, sys
connection = sqlite3.connect(sys.argv[1])
connection.execute(
    """
    INSERT OR REPLACE INTO library_entry(
        id, title, author, kind, file_path, size_bytes, created_at, thumbnail_url, source
    ) VALUES ('other-entry', 'Other entry', 'Someone', 'VIDEO', '/tmp/other.mp4', 1, 0, NULL, 'vimeo')
    """
)
connection.commit()
PY

    output="$("$APP" --cli library --create-smart Music --tag music --json)" \
        || { fail "creating smart collection failed"; return 1; }
    local smart_id
    smart_id="$(echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data.get("ok") is True, data
print(data["createdSmartCollection"])
')" || { fail "smart collection payload invalid"; return 1; }

    output="$("$APP" --cli library --smart "$smart_id" --json)" \
        || { fail "evaluating smart collection failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
ids = [entry["id"] for entry in data["entries"]]
assert ids == ["tag-entry"], data
' || { fail "smart collection membership invalid"; return 1; }

    output="$("$APP" --cli library --delete-smart "$smart_id" --json)" \
        || { fail "deleting smart collection failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data["smartCollections"] == [], data
' || { fail "smart collection was not deleted"; return 1; }

    output="$("$APP" --cli library --search jawed --json)" \
        || { fail "metadata search failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
ids = [entry["id"] for entry in data["entries"]]
assert ids == ["tag-entry"], data
' || { fail "metadata search result invalid"; return 1; }

    output="$("$APP" --cli library --search zzz-none --json)" \
        || { fail "empty search failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data["entries"] == [], data
' || { fail "empty search returned results"; return 1; }
    pass "library tags, smart collection and metadata search"
}

scenario_archive() {
    local db="$XDG_DATA_HOME/OpenYT/openyt.db"
    "$APP" --cli library --list --json >/dev/null 2>&1 || true

    python3 - "$db" <<'PY' || { fail "seeding archive data failed"; return 1; }
import sqlite3, sys
connection = sqlite3.connect(sys.argv[1])
connection.execute("DELETE FROM library_entry")
connection.execute("DELETE FROM library_tag")
connection.execute("DELETE FROM collection_entry")
connection.execute("DELETE FROM collection")
connection.execute("DELETE FROM smart_collection")
connection.execute(
    """
    INSERT INTO library_entry(
        id, title, author, kind, file_path, size_bytes, created_at, thumbnail_url, source
    ) VALUES ('archive-1', 'Archive one', 'Artist', 'AUDIO', '/tmp/archive-1.m4a', 10, 0, NULL, 'soundcloud')
    """
)
connection.execute(
    """
    INSERT INTO library_entry(
        id, title, author, kind, file_path, size_bytes, created_at, thumbnail_url, source
    ) VALUES ('archive-2', 'Archive two', NULL, 'VIDEO', '/tmp/archive-2.mp4', 20, 0, NULL, 'vimeo')
    """
)
connection.execute("INSERT INTO library_tag(entry_id, tag) VALUES ('archive-1', 'music')")
connection.execute("INSERT INTO collection(id, name) VALUES ('collection-archive', 'Archive collection')")
connection.execute("INSERT INTO collection_entry(collection_id, entry_id) VALUES ('collection-archive', 'archive-1')")
connection.execute(
    """
    INSERT INTO smart_collection(id, name, query, tag)
    VALUES ('smart-archive', 'Archive smart', '', 'music')
    """
)
connection.commit()
PY

    local archive="$WORK/library.json"
    output="$("$APP" --cli library --export "$archive" --json)" \
        || { fail "exporting library failed"; return 1; }
    [[ -s "$archive" ]] || { fail "exported archive is empty"; return 1; }

    python3 - "$db" <<'PY' || { fail "wiping library failed"; return 1; }
import sqlite3, sys
connection = sqlite3.connect(sys.argv[1])
connection.execute("DELETE FROM library_entry")
connection.execute("DELETE FROM library_tag")
connection.execute("DELETE FROM collection_entry")
connection.execute("DELETE FROM collection")
connection.execute("DELETE FROM smart_collection")
connection.commit()
PY

    output="$("$APP" --cli library --import "$archive" --policy replace --json)" \
        || { fail "importing library failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
result = data["importResult"]
assert result["entriesImported"] == 2, result
assert result["collectionsImported"] == 1, result
assert result["smartCollectionsImported"] == 1, result
' || { fail "import result invalid"; return 1; }

    output="$("$APP" --cli library --list --json)" \
        || { fail "listing imported library failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
entries = {entry["id"]: entry for entry in data["entries"]}
assert set(entries) == {"archive-1", "archive-2"}, entries
assert entries["archive-1"]["tags"] == ["music"], entries["archive-1"]
assert len(data["smartCollections"]) == 1, data["smartCollections"]
assert len(data["collections"]) == 1, data["collections"]
assert data["membership"]["collection-archive"] == ["archive-1"], data["membership"]
' || { fail "imported library content invalid"; return 1; }
    pass "library archive exported, wiped and imported"
}

scenario_watch() {
    local folder="$WORK/watch"
    mkdir -p "$folder/sub"
    printf 'video-bytes' > "$folder/movie.mp4"
    printf 'audio-bytes' > "$folder/song.m4a"
    printf 'ignored' > "$folder/notes.txt"
    local before after
    before="$(find "$folder" -type f -exec sha256sum {} \; | sort)"

    local output
    output="$("$APP" --cli library --scan "$folder" --json)" \
        || { fail "folder scan failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
assert data["scanResult"]["added"] == 2, data
' || { fail "folder scan add count invalid"; return 1; }

    output="$("$APP" --cli library --list --json)" \
        || { fail "listing scanned library failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
paths = {entry["filePath"] for entry in data["entries"]}
assert any(path.endswith("movie.mp4") for path in paths), paths
assert any(path.endswith("song.m4a") for path in paths), paths
assert not any(path.endswith("notes.txt") for path in paths), paths
' || { fail "scanned entries invalid"; return 1; }

    output="$("$APP" --cli library --scan "$folder" --json)" \
        || { fail "second folder scan failed"; return 1; }
    echo "$output" | python3 -c '
import json, sys
data = json.load(sys.stdin)
result = data["scanResult"]
assert result["added"] == 0 and result["skipped"] == 2, result
' || { fail "folder scan is not idempotent"; return 1; }

    after="$(find "$folder" -type f -exec sha256sum {} \; | sort)"
    [[ "$before" == "$after" ]] || { fail "source files were mutated"; return 1; }
    pass "watch folder imported in place and idempotently"
}

scenario_gui() {
    if [[ -z "${DISPLAY:-}" ]]; then skip "gui (no DISPLAY)"; return 0; fi
    command -v xwininfo >/dev/null 2>&1 || { skip "gui (no xwininfo)"; return 0; }
    command -v import >/dev/null 2>&1 || { skip "gui (no ImageMagick import)"; return 0; }

    setsid "$APP" >"$WORK/gui.log" 2>&1 &
    local pid=$!
    local window=""
    for _ in $(seq 1 40); do
        sleep 1
        window="$(DISPLAY="$DISPLAY" xwininfo -root -tree 2>/dev/null \
            | grep '"OpenYT"' \
            | grep -E '[0-9]{3,}x[0-9]{3,}' \
            | head -1 | awk '{print $1}')"
        if [[ -n "$window" ]] && DISPLAY="$DISPLAY" xwininfo -id "$window" 2>/dev/null \
            | grep -q 'Map State: IsViewable'; then
            break
        fi
        window=""
    done
    if [[ -z "$window" ]]; then
        kill -- -"$pid" 2>/dev/null || true
        fail "app window did not appear"
        return 1
    fi
    sleep 3
    local screenshot="$ROOT/build/validation/openyt-gui.png"
    if ! DISPLAY="$DISPLAY" import -window "$window" "$screenshot" 2>/dev/null; then
        local geometry width height x y
        geometry="$(DISPLAY="$DISPLAY" xwininfo -id "$window" 2>/dev/null \
            | awk '/Absolute upper-left X/ {x=$4} /Absolute upper-left Y/ {y=$4} /Width/ {w=$2} /Height/ {h=$2} END {print w"x"h"+"x"+"y}')"
        width="${geometry%%x*}"
        height="${geometry#*x}"; height="${height%%+*}"
        x="${geometry#*+}"; x="${x%%+*}"
        y="${geometry##*+}"
        DISPLAY="$DISPLAY" import -window root "$WORK/root.png" 2>/dev/null \
            && convert "$WORK/root.png" -crop "${width}x${height}+${x}+${y}" +repage "$screenshot" 2>/dev/null \
            || { kill -- -"$pid" 2>/dev/null || true; fail "screenshot failed"; return 1; }
    fi
    local db="$XDG_DATA_HOME/OpenYT/openyt.db"
    [[ -f "$db" ]] || { kill -- -"$pid" 2>/dev/null || true; fail "database was not created"; return 1; }
    python3 - "$db" <<'PY' || { kill -- -"$pid" 2>/dev/null || true; fail "database schema not migrated"; return 1; }
import sqlite3, sys
connection = sqlite3.connect(sys.argv[1])
version = connection.execute("PRAGMA user_version").fetchone()[0]
assert version == 13, f"user_version={version}"
PY
    kill -- -"$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    pass "app started, screenshot saved to build/validation/openyt-gui.png"
}

case "$SCENARIO" in
    all)
        run "prepare" prepare
        run "tests" scenario_tests
        run "tools" scenario_tools
        run "inspect" scenario_inspect
        run "download" scenario_download
        run "playlist" scenario_playlist
        run "pause" scenario_pause
        run "metadata" scenario_metadata
        run "cookies" scenario_cookies
        run "library" scenario_library
        run "archive" scenario_archive
        run "watch" scenario_watch
        run "gui" scenario_gui
        ;;
    tests) scenario_tests ;;
    tools) run_after_prepare "tools" scenario_tools ;;
    inspect) run_after_prepare "inspect" scenario_inspect ;;
    download) run_after_prepare "download" scenario_download ;;
    playlist) run_after_prepare "playlist" scenario_playlist ;;
    pause) run_after_prepare "pause" scenario_pause ;;
    metadata) run_after_prepare "metadata" scenario_metadata ;;
    cookies) run_after_prepare "cookies" scenario_cookies ;;
    library) run_after_prepare "library" scenario_library ;;
    archive) run_after_prepare "archive" scenario_archive ;;
    watch) run_after_prepare "watch" scenario_watch ;;
    gui) run_after_prepare "gui" scenario_gui ;;
    *) echo "unknown scenario: $SCENARIO" >&2; exit 2 ;;
esac

if [[ "$FAILED" -gt 0 ]]; then
    echo "validation failed: $FAILED scenario(s), $SKIPPED skipped" >&2
    exit 1
fi
echo "validation passed ($SKIPPED skipped)"
