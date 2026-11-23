#!/usr/bin/env bash
# Captures the Play listing screenshots: phone, 7" tablet and 10" tablet, brand colours, demo-mode status bar.
#
# Runs on macOS or Linux with the Android SDK and an arm64 or x86_64 system image, against a screenshot-rig
# server (a throwaway ListenUp server seeded with invented listeners; never a real one). It boots a
# headless, silent emulator, installs the debug app and the :app:storeshots UiAutomator suite, and runs
# the suite once per listing size, writing alpha-free PNGs into <output-dir>/<size>/. The emulator is
# shut down at the end, and an AVD the script created is deleted unless SCREENSHOTS_KEEP_AVD=1. On Linux
# the PNGs are flattened with ImageMagick (`magick`, or version 6's `mogrify`), which must be installed;
# macOS uses its own CoreGraphics.
#
#   tools/scripts/store-screenshots-android.sh [output-dir]    (default: build/store-screenshots/android)
#
# Sizes (Play's limits): phone 1080x1920 at density 370 (a taller screen breaks Play's 2:1 ratio cap);
# 7" the same display at density 288, which is 600dp wide and gets the tablet layout; 10" 2560x1440 at 320.
#
# Environment:
#   ANDROID_HOME             the SDK                  (default ~/Library/Android/sdk, ~/Android/Sdk on Linux)
#   SCREENSHOTS_SERVER_URL   the rig as the emulator reaches it     (default http://10.0.2.2:8099, the host)
#   SCREENSHOTS_LIVE_URL     the rig as this machine reaches it     (default http://localhost:8099)
#   SCREENSHOTS_CREDENTIALS  the rig's credentials file             (default ~/listenup-shots/CREDENTIALS.txt)
#   SCREENSHOTS_RIG_DB       the rig's database, read-only          (default ~/listenup-shots/home/listenup.db)
#   SCREENSHOTS_AVD          the AVD to boot (created if missing)   (default ListenUp_Screenshots)
#   SCREENSHOTS_SIZES        which sizes, space-separated           (default "phone tablet-7 tablet-10")
#   SCREENSHOTS_NO_LIVE=1    skip the live-presence driver (no "listening now" glow)
#
# Kept out of CI on purpose: it needs a seeded rig server and an emulator, and no CI job runs the
# :app:storeshots instrumentation.
set -euo pipefail

# shellcheck source=tools/scripts/store-screenshots-common.sh
source "$(dirname "${BASH_SOURCE[0]}")/store-screenshots-common.sh"

OUT="$(mkdir -p "${1:-$SHOTS_REPO_ROOT/build/store-screenshots/android}" && cd "${1:-$SHOTS_REPO_ROOT/build/store-screenshots/android}" && pwd)"
if [ "$(uname -s)" = Darwin ]; then DEFAULT_ANDROID_HOME="$HOME/Library/Android/sdk"; else DEFAULT_ANDROID_HOME="$HOME/Android/Sdk"; fi
export ANDROID_HOME="${ANDROID_HOME:-$DEFAULT_ANDROID_HOME}"
SERVER_URL="${SCREENSHOTS_SERVER_URL:-http://10.0.2.2:8099}"
LIVE_URL="${SCREENSHOTS_LIVE_URL:-http://localhost:8099}"
AVD="${SCREENSHOTS_AVD:-ListenUp_Screenshots}"
read -r -a SIZES <<<"${SCREENSHOTS_SIZES:-phone tablet-7 tablet-10}"
SHOTS_WORK="$(mktemp -d "${TMPDIR:-/tmp}/listenup-store-screenshots.XXXXXX")"
PORT=5590
SERIAL="emulator-$PORT"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
APP=com.calypsan.listenup.client
SUITE=com.calypsan.listenup.storeshots
DEVICE_DIR=/data/local/tmp/storeshots
CREATED_AVD=""
EMULATOR_PID=""
# The icon_blacklist setting as found, restored on exit; empty until hide_status_icons has run.
ORIGINAL_ICON_HIDE_LIST=""

cleanup() {
  local status=$?
  shots_live_stop
  restore_status_icons
  if [ -n "$EMULATOR_PID" ]; then
    "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do kill -0 "$EMULATOR_PID" 2>/dev/null || break; sleep 2; done
    kill "$EMULATOR_PID" 2>/dev/null || true
  fi
  if [ -n "$CREATED_AVD" ] && [ -z "${SCREENSHOTS_KEEP_AVD:-}" ]; then
    "$AVDMANAGER" delete avd -n "$CREATED_AVD" >/dev/null 2>&1 || true
  fi
  shots_unmute
  rm -rf "$SHOTS_WORK"
  exit $status
}
trap cleanup EXIT INT TERM

adb_shell() { "$ADB" -s "$SERIAL" shell "$@"; }

# Hides the status bar's satellite and location icons, which demo mode cannot: with mobile hidden there is
# no connection in service, so SystemUI shows its satellite glyph, and its demo parser only acts on
# `satellite show`, never on a hide. SystemUI's own icon hide list drops a slot whatever its state. The
# list replaces the default rather than adding to it, so the default's rotate and headset stay listed.
hide_status_icons() {
  if [ -z "$ORIGINAL_ICON_HIDE_LIST" ]; then
    ORIGINAL_ICON_HIDE_LIST="$(adb_shell settings get secure icon_blacklist | tr -d '\r')"
  fi
  adb_shell settings put secure icon_blacklist rotate,headset,satellite,location
}

# Puts the icon hide list back as hide_status_icons found it ("null" means it was never set).
restore_status_icons() {
  [ -n "$ORIGINAL_ICON_HIDE_LIST" ] || return 0
  if [ "$ORIGINAL_ICON_HIDE_LIST" = "null" ]; then
    adb_shell settings delete secure icon_blacklist >/dev/null 2>&1 || true
  else
    adb_shell settings put secure icon_blacklist "$ORIGINAL_ICON_HIDE_LIST" >/dev/null 2>&1 || true
  fi
}

ensure_avd() {
  if "$EMULATOR" -list-avds | grep -qx "$AVD"; then return; fi
  local image
  image="$(find "$ANDROID_HOME/system-images" -mindepth 3 -maxdepth 3 -type d | sort -V | tail -1)"
  [ -n "$image" ] || { shots_log "No system image under $ANDROID_HOME/system-images."; exit 1; }
  image="${image#"$ANDROID_HOME/"}"
  shots_log "Creating AVD $AVD from ${image//\//;}."
  echo no | "$AVDMANAGER" create avd -n "$AVD" -k "${image//\//;}" -d pixel_7 >/dev/null
  CREATED_AVD="$AVD"
  # The emulator refuses to boot without free disk for the whole data partition; the app and its
  # three short downloads need a fraction of the default. `-i.bak` is the one in-place form BSD and GNU
  # sed both read the same way.
  local config="$HOME/.android/avd/$AVD.avd/config.ini"
  sed -i.bak 's/^disk.dataPartition.size=.*/disk.dataPartition.size=4G/' "$config" && rm -f "$config.bak"
}

boot_emulator() {
  shots_log "Booting $AVD headless and silent."
  "$EMULATOR" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -no-snapshot -read-only \
    >"$SHOTS_WORK/emulator.log" 2>&1 &
  EMULATOR_PID=$!
  for _ in $(seq 1 200); do
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
      shots_log "The emulator exited:"
      grep -E "FATAL|ERROR" "$SHOTS_WORK/emulator.log" >&2 || tail -5 "$SHOTS_WORK/emulator.log" >&2
      exit 1
    fi
    [ "$(adb_shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && return
    sleep 3
  done
  shots_log "The emulator never finished booting; see $SHOTS_WORK/emulator.log."
  exit 1
}

build_and_install() {
  shots_log "Building the debug app and the screenshot suite (log: $SHOTS_WORK/build.log)."
  (cd "$SHOTS_REPO_ROOT" && ./gradlew :app:androidApp:assembleDebug :app:storeshots:assembleDebug --no-daemon) \
    >"$SHOTS_WORK/build.log" 2>&1 || { tail -40 "$SHOTS_WORK/build.log" >&2; exit 1; }
  "$ADB" -s "$SERIAL" install -r -g "$SHOTS_REPO_ROOT/app/androidApp/build/outputs/apk/debug/androidApp-debug.apk" >/dev/null
  "$ADB" -s "$SERIAL" install -r -g "$SHOTS_REPO_ROOT/app/storeshots/build/outputs/apk/debug/storeshots-debug.apk" >/dev/null
}

# Light mode, no animations, and SystemUI demo mode: 9:41, full battery, full Wi-Fi, no notifications.
prepare_device() {
  adb_shell cmd uimode night no >/dev/null
  for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb_shell settings put global "$scale" 0
  done
  adb_shell settings put global sysui_demo_allowed 1
  demo() { adb_shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
  # Re-entered per size: a display change can leave SystemUI's demo state half applied.
  demo exit
  demo enter
  demo clock -e hhmm 0941
  demo battery -e level 100 -e plugged false
  demo network -e wifi show -e level 4 -e fully true
  # No SIM icons; the satellite glyph that leaves behind is hidden by hide_status_icons.
  demo network -e mobile hide
  demo network -e airplane hide
  demo notifications -e visible false
  hide_status_icons
}

size_spec() {
  case "$1" in
    phone) echo "1080x1920 370" ;;
    tablet-7) echo "1080x1920 288" ;;
    tablet-10) echo "2560x1440 320" ;;
    *) shots_log "Unknown size $1"; exit 1 ;;
  esac
}

capture_size() {
  local size="$1" resolution density status=0
  read -r resolution density <<<"$(size_spec "$size")"
  shots_log "Capturing $size ($resolution at density $density)."
  adb_shell wm size "$resolution"
  adb_shell wm density "$density"
  prepare_device
  adb_shell pm clear "$APP" >/dev/null
  for permission in POST_NOTIFICATIONS NEARBY_WIFI_DEVICES ACCESS_LOCAL_NETWORK; do
    adb_shell pm grant "$APP" "android.permission.$permission" >/dev/null 2>&1 || true
  done
  adb_shell rm -rf "$DEVICE_DIR" "/sdcard/Android/data/$SUITE/files/*"
  adb_shell mkdir -p "$DEVICE_DIR"
  # The suite reads this file, then deletes it; the password never rides on a command line.
  "$ADB" -s "$SERIAL" push "$SHOTS_WORK/account" "$DEVICE_DIR-account" >/dev/null 2>&1
  adb_shell am instrument -w \
    -e screenshotsServerUrl "$SERVER_URL" \
    -e screenshotsAccountFile "$DEVICE_DIR-account" \
    -e screenshotsOutputDir "$DEVICE_DIR" \
    "$SUITE/androidx.test.runner.AndroidJUnitRunner" >"$SHOTS_WORK/$size-instrument.log" 2>&1 || status=$?
  adb_shell rm -f "$DEVICE_DIR-account"
  if grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed" "$SHOTS_WORK/$size-instrument.log"; then status=1; fi

  mkdir -p "$OUT/$size"
  "$ADB" -s "$SERIAL" pull "$DEVICE_DIR/." "$OUT/$size" >/dev/null 2>&1 || true
  if [ $status -ne 0 ]; then
    # The suite's view hierarchies, to see what moved.
    mkdir -p "$OUT/$size/trees"
    "$ADB" -s "$SERIAL" pull "/sdcard/Android/data/$SUITE/files/." "$OUT/$size/trees" >/dev/null 2>&1 || true
    shots_log "The capture at $size failed:"
    grep -vE "^INSTRUMENTATION_STATUS: (stream|current|numtests|id|class|test)=" "$SHOTS_WORK/$size-instrument.log" \
      | head -25 >&2 || true
    "$ADB" -s "$SERIAL" logcat -d -b crash | tail -40 >&2 || true
  fi
  shots_flatten_pngs "$OUT/$size"
  shots_log "$size: $(find "$OUT/$size" -name '[0-9]*.png' | wc -l | tr -d ' ') screenshots in $OUT/$size"
  adb_shell am force-stop "$APP"
  return $status
}

shots_require_credentials
shots_require_png_flattener
shots_write_main_account "$SHOTS_WORK/account"
shots_mute
ensure_avd
boot_emulator
build_and_install
if [ -z "${SCREENSHOTS_NO_LIVE:-}" ]; then
  shots_live_start "$LIVE_URL"
  shots_live_wait 900
fi

failed=0
for size in "${SIZES[@]}"; do
  capture_size "$size" || failed=1
done
adb_shell wm size reset
adb_shell wm density reset
exit $failed
