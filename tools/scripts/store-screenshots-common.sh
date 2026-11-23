# shellcheck shell=bash
# Shared by store-screenshots-ios.sh and store-screenshots-android.sh. Sourced, never run.
#
# The screenshot rig is a throwaway ListenUp server seeded with invented listeners (see each script's
# header). Its credentials file is read here and only here, by Python, straight into owner-only files
# under $SHOTS_WORK; no secret ever passes through a shell variable, a command line, or a log.
#
# Credentials file layout:
#   <title line>
#     email: <main listener>
#     password: <main listener's password>
#   Other listeners (for re-scripting):
#   <First> <Last> <email> <password>      (one line each)

SHOTS_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SHOTS_CREDENTIALS="${SCREENSHOTS_CREDENTIALS:-$HOME/listenup-shots/CREDENTIALS.txt}"
SHOTS_RIG_DB="${SCREENSHOTS_RIG_DB:-$HOME/listenup-shots/home/listenup.db}"
# Listeners kept "listening now" during the capture, as `email|book title` pairs separated by `;`.
SHOTS_LIVE_LISTENERS="${SCREENSHOTS_LIVE_LISTENERS:-member@listenup.app|The Adventures of Sherlock Holmes;tomas@mylibrary.home|Jane Eyre}"
SHOTS_LIVE_PID=""

shots_log() { printf '[store-screenshots] %s\n' "$*" >&2; }

shots_require_credentials() {
  if [ ! -r "$SHOTS_CREDENTIALS" ]; then
    shots_log "No rig credentials at $SHOTS_CREDENTIALS (set SCREENSHOTS_CREDENTIALS)."
    exit 1
  fi
}

# Writes the main listener's email and password, one per line, to $1 (mode 600).
shots_write_main_account() {
  (umask 077 && python3 - "$SHOTS_CREDENTIALS" "$1" <<'PY'
import sys
fields = {}
for line in open(sys.argv[1], encoding="utf-8"):
    key, sep, value = line.strip().partition(":")
    if sep and key in ("email", "password") and key not in fields:
        fields[key] = value.strip()
if set(fields) != {"email", "password"}:
    sys.exit("The credentials file has no main listener email/password")
with open(sys.argv[2], "w", encoding="utf-8") as out:
    out.write(fields["email"] + "\n" + fields["password"] + "\n")
PY
  )
}

# Starts the live-presence driver (server/src/jvmTest/.../storeshots/LivePresenceDriver.kt) in the
# background against $1, the rig URL as seen from this Mac. Best effort: without it the screenshots
# are still taken, just without the "listening now" glow.
shots_live_start() {
  local server_url="$1" config="$SHOTS_WORK/live.json"
  if [ ! -r "$SHOTS_RIG_DB" ]; then
    shots_log "No rig database at $SHOTS_RIG_DB: skipping live presence (no glow)."
    return 0
  fi
  if ! (umask 077 && python3 - "$SHOTS_CREDENTIALS" "$SHOTS_RIG_DB" "$SHOTS_LIVE_LISTENERS" \
      "$server_url" "$SHOTS_WORK/live.stop" "$SHOTS_WORK/live-presence.log" "$config" <<'PY'
import json, sqlite3, sys
creds, db, spec, base_url, stop_file, log_file, out = sys.argv[1:]
passwords = {}
for line in open(creds, encoding="utf-8"):
    parts = line.split()
    if len(parts) >= 4 and "@" in parts[-2]:
        passwords[parts[-2]] = parts[-1]
conn = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
listeners = []
for pair in filter(None, spec.split(";")):
    email, _, title = pair.partition("|")
    row = conn.execute("select id from books where title = ? and deleted_at is null", (title,)).fetchone()
    if email not in passwords or row is None:
        sys.exit(f"Live presence: no password for {email} or no book titled {title!r}")
    listeners.append({"email": email, "password": passwords[email], "bookId": row[0]})
json.dump({"baseUrl": base_url, "stopFile": stop_file, "logFile": log_file, "maxMinutes": 150, "listeners": listeners},
          open(out, "w", encoding="utf-8"))
PY
  ); then
    shots_log "Live presence config failed: skipping (no glow)."
    return 0
  fi
  shots_log "Starting live presence (logs: $SHOTS_WORK/live.log)."
  (
    cd "$SHOTS_REPO_ROOT" &&
      # --rerun: the config arrives in the environment, which Gradle does not see as an input, so an
      # earlier run would otherwise leave the task up to date and the driver never starts.
      LISTENUP_LIVE_PRESENCE_CONFIG="$config" exec ./gradlew :server:jvmTest --rerun \
        --tests "com.calypsan.listenup.server.storeshots.LivePresenceDriver" \
        --no-daemon --no-configuration-cache
  ) >"$SHOTS_WORK/live.log" 2>&1 &
  SHOTS_LIVE_PID=$!
}

# Blocks until the driver has made its first round of writes (or $1 seconds pass).
shots_live_wait() {
  local deadline=$((SECONDS + ${1:-900}))
  [ -n "$SHOTS_LIVE_PID" ] || return 0
  while [ $SECONDS -lt $deadline ]; do
    if grep -q '^\[live\] .*: live$' "$SHOTS_WORK/live-presence.log" 2>/dev/null; then
      shots_log "Live presence is up."
      return 0
    fi
    if ! kill -0 "$SHOTS_LIVE_PID" 2>/dev/null; then
      shots_log "Live presence exited early; see $SHOTS_WORK/live.log. Continuing without the glow."
      return 0
    fi
    sleep 10
  done
  shots_log "Live presence not up after waiting; continuing without the glow."
}

shots_live_stop() {
  [ -n "$SHOTS_LIVE_PID" ] || return 0
  touch "$SHOTS_WORK/live.stop"
  local waited=0
  while kill -0 "$SHOTS_LIVE_PID" 2>/dev/null && [ $waited -lt 60 ]; do
    sleep 2
    waited=$((waited + 2))
  done
  kill "$SHOTS_LIVE_PID" 2>/dev/null || true
  wait "$SHOTS_LIVE_PID" 2>/dev/null || true
  local rounds
  rounds=$(grep -c '^\[live\] .*: live$' "$SHOTS_WORK/live-presence.log" 2>/dev/null || true)
  shots_log "Live presence stopped after ${rounds:-0} position writes."
  SHOTS_LIVE_PID=""
}

# Mutes this Mac's output for the run (the simulator and emulator play through it) and restores the
# previous state on exit. Elsewhere there is no osascript and nothing to mute: the emulator runs with
# -no-audio, so it is skipped.
SHOTS_WAS_MUTED=""
shots_mute() {
  if ! command -v osascript >/dev/null 2>&1; then
    shots_log "Not a Mac: leaving the output volume alone (the emulator runs with -no-audio)."
    return 0
  fi
  SHOTS_WAS_MUTED=$(osascript -e 'output muted of (get volume settings)' 2>/dev/null || echo "unknown")
  osascript -e 'set volume output muted true' >/dev/null 2>&1 || shots_log "Could not mute the Mac's output."
}
shots_unmute() {
  if [ "$SHOTS_WAS_MUTED" = "false" ]; then
    osascript -e 'set volume output muted false' >/dev/null 2>&1 || true
  fi
}

# Rewrites every PNG under $1 in place as 24-bit RGB on white: Play rejects screenshots with an alpha
# channel, and a store upload has no use for one. On macOS, CoreGraphics via a throwaway Swift script,
# since macOS ships no image tool that drops alpha losslessly; elsewhere, ImageMagick.
shots_flatten_pngs() {
  local dir="$1"
  if command -v xcrun >/dev/null 2>&1; then
    shots_flatten_pngs_coregraphics "$dir"
  elif command -v magick >/dev/null 2>&1; then
    shots_flatten_pngs_imagemagick "$dir" magick mogrify
  elif command -v mogrify >/dev/null 2>&1; then
    shots_flatten_pngs_imagemagick "$dir" mogrify
  else
    shots_require_png_flattener
  fi
}

# Fails before anything boots when shots_flatten_pngs would have no tool to run.
shots_require_png_flattener() {
  command -v xcrun >/dev/null 2>&1 || command -v magick >/dev/null 2>&1 || command -v mogrify >/dev/null 2>&1 ||
    { shots_log "Cannot flatten the PNGs: no xcrun (macOS) and no ImageMagick (magick or mogrify). Install ImageMagick."; return 1; }
}

# $2… is the ImageMagick mogrify command: `magick mogrify` on version 7, `mogrify` on 6.
shots_flatten_pngs_imagemagick() {
  local dir="$1"
  shift
  find "$dir" -name '*.png' -exec "$@" -background white -alpha remove -alpha off \
    -define png:color-type=2 -define png:bit-depth=8 {} +
}

shots_flatten_pngs_coregraphics() {
  local dir="$1" script="$SHOTS_WORK/flatten.swift"
  cat >"$script" <<'SWIFT'
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

for path in CommandLine.arguments.dropFirst() {
    let url = URL(fileURLWithPath: path) as CFURL
    guard let source = CGImageSourceCreateWithURL(url, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
          let context = CGContext(
              data: nil, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: 0,
              space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
    else { fatalError("Cannot read \(path)") }
    context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: image.width, height: image.height))
    context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    guard let flat = context.makeImage(),
          let destination = CGImageDestinationCreateWithURL(url, UTType.png.identifier as CFString, 1, nil)
    else { fatalError("Cannot write \(path)") }
    CGImageDestinationAddImage(destination, flat, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("Cannot write \(path)") }
}
SWIFT
  find "$dir" -name '*.png' -print0 | xargs -0 xcrun swift "$script"
}
