#!/usr/bin/env bash
# Playback smoke test — the pause/resume behaviour no unit test can see.
#
# WHY THIS EXISTS
#
# 0.8.4 shipped a defect where every resume after a pause of a minute or more jumped the
# listener to the next chapter boundary instead of stepping back a few seconds (#1237's
# auto-rewind actuator aimed at #1241's chapter-scoped session player). It survived the
# release gate because the gate boots the app and stops: it never plays anything, and app
# builds are post-merge-only. A ninety-second run of this script would have caught it.
#
# It asserts the one property that matters on resume: you land slightly BEHIND where you
# paused, in the SAME chapter. A jump to a chapter boundary — in either direction — fails.
#
# USAGE
#   tools/scripts/playback-smoke.sh [pause_seconds]
#
# Start a book playing on the connected device first (any book, any position, but not
# within a minute of a chapter edge). Default pause is 70s — comfortably over the 60s
# threshold for the first rung of the auto-rewind ladder, which is what arms the actuator.
#
# EXIT CODES
#   0 pass   1 assertion failed   2 setup/environment problem

set -u -o pipefail

PKG="com.calypsan.listenup.client"
PAUSE_SECONDS="${1:-70}"
# The ladder's first rung is 5s. Allow generous slack for playback drift across the
# pause/resume round trip and for the position poll's own cadence.
MAX_EXPECTED_REWIND_MS=20000

fail() {
	echo "FAIL: $*" >&2
	exit 1
}
setup_fail() {
	echo "SETUP: $*" >&2
	exit 2
}

command -v adb >/dev/null 2>&1 || setup_fail "adb not on PATH"
[ -n "$(adb devices | sed -n '2p')" ] || setup_fail "no device connected"

# The ListenUp session block from dumpsys, up to the start of the next session's block.
session_block() {
	adb shell dumpsys media_session 2>/dev/null |
		awk -v pkg="$PKG" '
			$0 ~ pkg"/" { inblock = 1 }
			inblock && /^    [A-Za-z]/ && $0 !~ pkg { if (seen) inblock = 0 }
			inblock { print; seen = 1 }
		'
}

position_ms() { session_block | grep -o 'position=[0-9-]*' | head -1 | cut -d= -f2; }
play_state() { session_block | grep -o 'state=[A-Z_]*([0-9])' | head -1; }
# The wrapper reports the CURRENT CHAPTER as the session's media item, so its title is a
# direct read-out of which chapter the listener is in. That is the signal here: the title
# must not change across a pause/resume.
chapter() { session_block | grep -o 'description=.*' | head -1; }

[ -n "$(session_block)" ] || setup_fail "no ListenUp media session — start a book playing first"

STATE_BEFORE="$(play_state)"
case "$STATE_BEFORE" in
*PLAYING*) ;;
*) setup_fail "expected playback to be running, found ${STATE_BEFORE:-none}. Start a book playing first." ;;
esac

POS_BEFORE="$(position_ms)"
CHAPTER_BEFORE="$(chapter)"
[ -n "$POS_BEFORE" ] || setup_fail "could not read a position from the session"
echo "before pause: position=${POS_BEFORE}ms  ${CHAPTER_BEFORE}"

echo "pausing for ${PAUSE_SECONDS}s (must exceed the 60s auto-rewind threshold)..."
adb shell cmd media_session dispatch pause >/dev/null 2>&1 || setup_fail "could not dispatch pause"
sleep 3
POS_PAUSED="$(position_ms)"
sleep "$PAUSE_SECONDS"

adb shell cmd media_session dispatch play >/dev/null 2>&1 || setup_fail "could not dispatch play"
# Let the resume settle: the actuator seeks after the isPlaying transition propagates.
sleep 5

POS_AFTER="$(position_ms)"
CHAPTER_AFTER="$(chapter)"
[ -n "$POS_AFTER" ] || fail "no position after resume — playback did not come back"
echo "after resume: position=${POS_AFTER}ms  ${CHAPTER_AFTER}"

if [ "$CHAPTER_BEFORE" != "$CHAPTER_AFTER" ]; then
	fail "chapter changed across a pause/resume: '${CHAPTER_BEFORE}' -> '${CHAPTER_AFTER}'. A resume must never cross a chapter boundary (this is the 0.8.4 regression)."
fi

DELTA=$((POS_PAUSED - POS_AFTER))
if [ "$DELTA" -lt 0 ]; then
	fail "resumed AHEAD of the pause point by $((-DELTA))ms — a resume must never skip forward."
fi
if [ "$DELTA" -gt "$MAX_EXPECTED_REWIND_MS" ]; then
	fail "rewound ${DELTA}ms on resume, far more than the ladder's rungs allow (max ${MAX_EXPECTED_REWIND_MS}ms)."
fi

echo "PASS: resumed ${DELTA}ms behind the pause point, same chapter."
exit 0
