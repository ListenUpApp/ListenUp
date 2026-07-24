#!/usr/bin/env bash
# Export-surface inventory + regression gate for the iOS Swift Export surface.
#
# The native iOS app consumes the shared Kotlin core through Swift Export, not the
# Objective-C framework. The caller-facing public surface is the flat-typealias
# layer the Swift-Export patcher (build-logic SwiftExportSourcePatcher) appends onto
# the generated `Shared.swift` — one `public typealias <Name> = ExportedKotlinPackages.…`
# per exported type, for both the `:app:sharedLogic` and `:contract` modules. Those flat
# names are the stable `import Shared; <Name>` surface (the mangled
# `_ExportedKotlinPackages_…` class names shift across tool bumps and are not what
# callers reference), so the inventory measures the alias names.
#
# This is a hard gate. It:
#   1. extracts the sorted set of public exported names from the generated surface,
#   2. diffs it against the committed baseline (scripts/export-surface-baseline.txt) —
#      additions are candidate leaks to review, removals are surface shrink,
#   3. asserts no banned name (server-only / internal-plumbing types that the export
#      trim removed, plus generic infra types that must never reach the client) is
#      present,
#   4. exits 1 on a banned-name hit or an unreviewed addition; exits 0 when the live
#      surface matches the baseline.
#
# When the public surface legitimately changes, regenerate the baseline:
#   bash scripts/export-surface-inventory.sh <Shared.swift> --update-baseline
# and commit the diff — that diff is the review signal.
#
# Usage: export-surface-inventory.sh [path-to-Shared.swift] [--update-baseline]
#   If no path is given (or it's missing), searches app/sharedLogic/build for the
#   patched Shared.swift. The surface only exists after a Swift-Export build on a Mac.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASELINE="${SCRIPT_DIR}/export-surface-baseline.txt"

UPDATE_BASELINE=0
SHARED=""
for arg in "$@"; do
  case "$arg" in
    --update-baseline) UPDATE_BASELINE=1 ;;
    *) SHARED="$arg" ;;
  esac
done

if [[ -z "$SHARED" || ! -f "$SHARED" ]]; then
  echo "-> Shared.swift not at '${SHARED:-<unset>}'; searching app/sharedLogic/build ..."
  # Prefer the patched SPMPackage copy (it carries the flat-typealias layer); fall back to any.
  SHARED="$(find app/sharedLogic/build -name 'Shared.swift' -path '*Shared*' 2>/dev/null \
    | grep -E 'SPMPackage' | head -1)"
  [[ -z "$SHARED" ]] && SHARED="$(find app/sharedLogic/build -name 'Shared.swift' -path '*Shared*' 2>/dev/null | head -1)"
fi
if [[ -z "$SHARED" || ! -f "$SHARED" ]]; then
  echo "export-surface-inventory: Shared.swift not found (run :app:sharedLogic:embedSwiftExportForXcode first)." >&2
  # Fail closed, matching check-no-appresult-await.sh. The previous `exit 0` here meant a
  # Swift-Export/Gradle change that RELOCATED the SPMPackage output would silently disarm this
  # gate forever — leaving a WARN line in a green log. "The framework build step gates separately"
  # does not hold: the same relocation breaks both, so neither would fail.
  exit 2
fi

echo "== Export-surface inventory: $SHARED =="

# The flat-typealias layer is delimited by the patcher's markers. Extract the alias
# names declared in it (one public exported type each). If the markers are absent the
# Shared.swift wasn't patched — that's a build problem, fail loudly.
FLAT_MARKER='swift-export flat typealias layer'
SEALED_MARKER='swift-export sealed-enum support'
if ! grep -qF "$FLAT_MARKER" "$SHARED"; then
  echo "ERROR: '$FLAT_MARKER' marker not found in $SHARED — the Swift-Export patcher did not run."
  echo "       (SwiftExportSourcePatcher.appendFlatTypealiases appends it; see build-logic.)"
  exit 1
fi

live_surface() {
  awk -v start="$FLAT_MARKER" -v stop="$SEALED_MARKER" '
    index($0, start) { f = 1; next }
    index($0, stop)  { f = 0 }
    f
  ' "$SHARED" \
    | grep -E '^public typealias [A-Za-z_][A-Za-z0-9_]* =' \
    | sed -E 's/^public typealias ([A-Za-z_][A-Za-z0-9_]*) =.*/\1/' \
    | sort -u
}

SURFACE="$(live_surface)"
count="$(printf '%s\n' "$SURFACE" | grep -c . || true)"
echo "exported public types (flat-typealias surface): ${count}"

if [[ "$UPDATE_BASELINE" == "1" ]]; then
  printf '%s\n' "$SURFACE" > "$BASELINE"
  echo "-> baseline written to $BASELINE (${count} names). Review + commit the diff."
  exit 0
fi

# ── Regression check: banned names ──────────────────────────────────────────────
# These must never appear on the client export surface:
#   • Server-only @Resource / sync-control types the export trim (PR #682) removed —
#     they live in :server now. Swift flat-alias spelling is the bare Kotlin name.
#   • Generic infra types that would signal a plumbing leak back onto the surface.
banned=(
  BookResources AdminUserResources ScannerResources
  BackupRoutePaths ImportRoutePaths
  SyncControl DomainDigest DomainList
  HttpClient Module RpcClient Koin Resource
)
echo "-- regression check (banned names must be ABSENT) --"
leaked=0
for sym in "${banned[@]}"; do
  if printf '%s\n' "$SURFACE" | grep -qxF "$sym"; then
    echo "    PRESENT: ${sym}  <- export-surface regression"
    leaked=$((leaked + 1))
  fi
done
[[ "$leaked" -eq 0 ]] && echo "    (all banned names absent)"

# ── Diff against the committed baseline ─────────────────────────────────────────
if [[ ! -f "$BASELINE" ]]; then
  echo "ERROR: baseline $BASELINE missing — generate it with --update-baseline on a Mac build."
  exit 1
fi

additions="$(comm -13 "$BASELINE" <(printf '%s\n' "$SURFACE"))"
removals="$(comm -23 "$BASELINE" <(printf '%s\n' "$SURFACE"))"

drift=0
if [[ -n "$additions" ]]; then
  echo "-- ADDITIONS vs baseline (new public types — candidate leaks, review then --update-baseline) --"
  printf '%s\n' "$additions" | sed 's/^/    + /'
  drift=1
fi
if [[ -n "$removals" ]]; then
  echo "-- REMOVALS vs baseline (surface shrank — update the baseline if intended) --"
  printf '%s\n' "$removals" | sed 's/^/    - /'
  drift=1
fi
[[ "$drift" -eq 0 ]] && echo "-- surface matches baseline (no additions/removals) --"

if [[ "$leaked" -gt 0 ]]; then
  echo "FAIL: ${leaked} banned name(s) leaked onto the export surface."
  exit 1
fi
if [[ "$drift" -ne 0 ]]; then
  echo "FAIL: export surface drifted from the baseline. Review the diff above; if intended, rerun with --update-baseline and commit."
  exit 1
fi

echo "OK: export surface matches the approved baseline."
exit 0
