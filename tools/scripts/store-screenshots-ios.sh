#!/usr/bin/env bash
# Captures the App Store screenshots: iPhone 6.9" and iPad 13", light mode, a 9:41 status bar.
#
# Runs on a Mac with Xcode, against a screenshot-rig server (a throwaway ListenUp server seeded with
# invented listeners; never a real one). For each device it creates a fresh simulator, runs the
# ListenUpScreenshots UI test (app/iosApp/ListenUpScreenshots), exports its attachments as PNGs into
# <output-dir>/<device>/, then deletes the simulator. Nothing is left booted or installed.
#
#   tools/scripts/store-screenshots-ios.sh [output-dir]       (default: build/store-screenshots/ios)
#
# Environment:
#   SCREENSHOTS_SERVER_URL   the rig as the simulator reaches it    (default http://<this Mac>.local:8099;
#                            not localhost, which the connect screen refuses off an emulator)
#   SCREENSHOTS_LIVE_URL     the rig as this Mac reaches it         (default: SCREENSHOTS_SERVER_URL)
#   SCREENSHOTS_CREDENTIALS  the rig's credentials file             (default ~/listenup-shots/CREDENTIALS.txt)
#   SCREENSHOTS_RIG_DB       the rig's database, read-only          (default ~/listenup-shots/home/listenup.db)
#   SCREENSHOTS_DEVICES      device types, `;`-separated            (default iPhone 17 Pro Max;iPad Pro 13-inch (M5))
#   SCREENSHOTS_DERIVED_DATA keep the build here between runs       (default: a temp dir, deleted)
#   SCREENSHOTS_NO_LIVE=1    skip the live-presence driver (no "listening now" glow)
#
# Kept out of CI on purpose: it needs a seeded rig server and takes several minutes per device. The
# ListenUp scheme never builds the ListenUpScreenshots target; only its own shared scheme does.
set -euo pipefail

# shellcheck source=tools/scripts/store-screenshots-common.sh
source "$(dirname "${BASH_SOURCE[0]}")/store-screenshots-common.sh"

OUT="$(mkdir -p "${1:-$SHOTS_REPO_ROOT/build/store-screenshots/ios}" && cd "${1:-$SHOTS_REPO_ROOT/build/store-screenshots/ios}" && pwd)"
SERVER_URL="${SCREENSHOTS_SERVER_URL:-http://$(scutil --get LocalHostName).local:8099}"
LIVE_URL="${SCREENSHOTS_LIVE_URL:-$SERVER_URL}"
IFS=';' read -r -a DEVICES <<<"${SCREENSHOTS_DEVICES:-iPhone 17 Pro Max;iPad Pro 13-inch (M5)}"
SHOTS_WORK="$(mktemp -d "${TMPDIR:-/tmp}/listenup-store-screenshots.XXXXXX")"
DERIVED_DATA="${SCREENSHOTS_DERIVED_DATA:-$SHOTS_WORK/DerivedData}"
APP_BUNDLE_ID="com.calypsan.listenup.client.ios"
CREATED_SIMS=()

cleanup() {
  local status=$?
  shots_live_stop
  for udid in "${CREATED_SIMS[@]+"${CREATED_SIMS[@]}"}"; do
    xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
    xcrun simctl delete "$udid" >/dev/null 2>&1 || true
  done
  shots_unmute
  rm -rf "$SHOTS_WORK"
  exit $status
}
trap cleanup EXIT INT TERM

shots_require_credentials
shots_write_main_account "$SHOTS_WORK/account"
shots_mute

runtime="$(xcrun simctl list runtimes --json | python3 -c '
import json, sys
ios = [r for r in json.load(sys.stdin)["runtimes"] if r["platform"] == "iOS" and r["isAvailable"]]
print(sorted(ios, key=lambda r: [int(p) for p in r["version"].split(".")])[-1]["identifier"])')"

# Sets SIM_UDID to a fresh, booted, light-mode simulator of device type $1 with a clean status bar.
create_sim() {
  local name="$1" udid
  udid="$(xcrun simctl create "ListenUp Screenshots ($name)" "$name" "$runtime")"
  CREATED_SIMS+=("$udid")
  SIM_UDID="$udid"
  xcrun simctl boot "$udid"
  xcrun simctl bootstatus "$udid" -b >/dev/null
  xcrun simctl ui "$udid" appearance light
  xcrun simctl status_bar "$udid" override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 \
    --cellularMode active --cellularBars 4 --batteryState discharging --batteryLevel 100
}

build_for() {
  local udid="$1"
  shots_log "Building the app and the screenshot runner (log: $SHOTS_WORK/build.log)."
  (
    cd "$SHOTS_REPO_ROOT/app/iosApp"
    xcodebuild build -scheme ListenUp -destination "id=$udid" -derivedDataPath "$DERIVED_DATA" -quiet
    xcodebuild build-for-testing -scheme ListenUpScreenshots -destination "id=$udid" \
      -derivedDataPath "$DERIVED_DATA" -quiet
  ) >"$SHOTS_WORK/build.log" 2>&1 || {
    tail -40 "$SHOTS_WORK/build.log" >&2
    exit 1
  }
}

capture_on() {
  local device="$1" udid="$2" slug result
  slug="$(echo "$device" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9\n' '-' | sed 's/--*/-/g; s/-$//')"
  result="$SHOTS_WORK/$slug.xcresult"
  xcrun simctl install "$udid" "$DERIVED_DATA/Build/Products/Debug-iphonesimulator/ListenUp.app"
  shots_log "Capturing on $device."
  # TEST_RUNNER_ variables reach the test process with the prefix stripped. The password is read from
  # the owner-only account file into the environment only, never onto a command line.
  local status=0
  (
    cd "$SHOTS_REPO_ROOT/app/iosApp"
    TEST_RUNNER_SCREENSHOTS_SERVER_URL="$SERVER_URL" \
      TEST_RUNNER_SCREENSHOTS_APP_BUNDLE_ID="$APP_BUNDLE_ID" \
      TEST_RUNNER_SCREENSHOTS_EMAIL="$(sed -n 1p "$SHOTS_WORK/account")" \
      TEST_RUNNER_SCREENSHOTS_PASSWORD="$(sed -n 2p "$SHOTS_WORK/account")" \
      xcodebuild test-without-building -scheme ListenUpScreenshots -destination "id=$udid" \
      -derivedDataPath "$DERIVED_DATA" -resultBundlePath "$result" \
      -test-timeouts-enabled YES -maximum-test-execution-time-allowance 1800
  ) >"$SHOTS_WORK/$slug-test.log" 2>&1 || status=$?

  mkdir -p "$OUT/$slug"
  xcrun xcresulttool export attachments --path "$result" --output-path "$SHOTS_WORK/$slug-attachments" >/dev/null
  python3 - "$SHOTS_WORK/$slug-attachments" "$OUT/$slug" <<'PY'
import json, os, re, shutil, sys
src, dst = sys.argv[1:]
# Only the test's own attachments: "01-home_0_<uuid>.png" -> "01-home.png", and the trees of missed
# screens into trees/. XCTest's automatic failure recordings and snapshots stay behind.
for test in json.load(open(os.path.join(src, "manifest.json"))):
    for a in test.get("attachments", []):
        name = a.get("suggestedHumanReadableName", a["exportedFileName"])
        stem, ext = os.path.splitext(name)
        stem = stem.split("_")[0]
        if re.fullmatch(r"\d\d[a-z]?-[a-z-]+", stem) and ext == ".png":
            target = os.path.join(dst, stem + ext)
        elif stem.startswith("missed-"):
            os.makedirs(os.path.join(dst, "trees"), exist_ok=True)
            target = os.path.join(dst, "trees", stem + ".txt")
        else:
            continue
        shutil.copy(os.path.join(src, a["exportedFileName"]), target)
PY
  shots_flatten_pngs "$OUT/$slug"
  if [ $status -ne 0 ]; then
    shots_log "The capture on $device failed; the last of its log:"
    grep -E "error:|Store screenshots" "$SHOTS_WORK/$slug-test.log" | tail -20 >&2 || true
    return $status
  fi
  shots_log "$device: $(find "$OUT/$slug" -name '*.png' | wc -l | tr -d ' ') screenshots in $OUT/$slug"
}

failed=0
for device in "${DEVICES[@]}"; do
  create_sim "$device"
  if [ "$device" = "${DEVICES[0]}" ]; then
    build_for "$SIM_UDID"
    # After the build: two Gradle builds in one checkout would only queue behind each other's locks.
    if [ -z "${SCREENSHOTS_NO_LIVE:-}" ]; then
      shots_live_start "$LIVE_URL"
      shots_live_wait 900
    fi
  fi
  capture_on "$device" "$SIM_UDID" || failed=1
  xcrun simctl shutdown "$SIM_UDID" >/dev/null 2>&1 || true
done
exit $failed
