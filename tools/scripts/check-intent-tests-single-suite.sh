#!/usr/bin/env bash
# Fails if any iOS test file other than AppIntentsTests.swift installs an App Intents dependency.
#
# WHY THIS EXISTS. `@Dependency` resolves through the process-wide `AppDependencyManager`, keyed by
# the protocol type, and its setter is `nonmutating` — `intent.playback = fake` on a `let` writes to
# a slot every `any PlaybackControlling` in the process shares, not to that instance.
# `AppIntentsTests.dependencySlotIsProcessWideNotPerInstance` proves it.
#
# So two tests that each install their own fake must never overlap. `@Suite(.serialized)` orders
# tests *within* one suite and does nothing between suites, so splitting these across files puts
# them back in a race that xcodebuild's parallel clones lose intermittently: one suite's fake
# replaces the other's between `intent.playback = fake` and `perform()`, and the call lands on the
# wrong fake. It surfaced once as `skipBackwardIntentRoutesToSkipBackward()` failing in 0.135s on a
# CI PR that touched no iOS code at all — the kind of failure that costs an afternoon to attribute.
#
# One suite is the fix, and this is what keeps it one suite.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TESTS_DIR="$REPO_ROOT/app/iosApp/ListenUpTests"
HOME_FILE="AppIntentsTests.swift"

if [ ! -d "$TESTS_DIR" ]; then
  echo "check-intent-tests-single-suite: FAIL — $TESTS_DIR not found (did the iOS layout move?)" >&2
  exit 1
fi

# What an installation looks like: `intent.playback = fake` / `intent.lastPlayed = fake`. Matching
# the assignment rather than the protocol name is deliberate — a file may legitimately *mention*
# PlaybackControlling without ever writing the shared slot, and it is the write that races.
INSTALL_RE='\.(playback|lastPlayed)[[:space:]]*='

# Anchor: the home file must exist and must itself install a dependency, or this check is vacuous.
if ! grep -qE "$INSTALL_RE" "$TESTS_DIR/$HOME_FILE" 2>/dev/null; then
  echo "check-intent-tests-single-suite: FAIL — $HOME_FILE no longer installs an App Intents" >&2
  echo "  dependency. Either it moved (update this script) or the guard is now checking nothing." >&2
  exit 1
fi

offenders="$(grep -rlE "$INSTALL_RE" "$TESTS_DIR" \
  --include='*.swift' 2>/dev/null | grep -v "/$HOME_FILE\$" || true)"

if [ -n "$offenders" ]; then
  echo "check-intent-tests-single-suite: FAIL — App Intents dependencies installed outside $HOME_FILE:" >&2
  echo "$offenders" | sed 's|^|  |' >&2
  echo "" >&2
  echo "  The dependency slot is process-wide, so every test that installs one must live in the" >&2
  echo "  single serialized suite in $HOME_FILE. A second suite races with the first." >&2
  exit 1
fi

echo "check-intent-tests-single-suite: clean (App Intents dependencies confined to $HOME_FILE)."
