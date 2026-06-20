#!/usr/bin/env bash
# Export-surface inventory for the iOS `Shared.framework` Objective-C header.
#
# Informational (always exits 0): records how many Objective-C declarations the
# framework exports, dumps the ones matching our domain types (so the real
# Kotlin->ObjC mangled names are visible for calibration), and flags whether
# known server-only / internal types — which the export-surface trim removed —
# have crept back in.
#
# This is the measurement step. Once a real CI run shows the actual mangled
# names, the regression list below can be turned into a hard gate (drop the
# `exit 0` / wire it to fail). Nothing here can run on a non-Apple host — the
# header only exists after a Kotlin/Native framework link.
#
# Usage: export-surface-inventory.sh [path-to-Shared.h]
#   If no path is given (or it's missing), searches sharedLogic/build/bin.
set -uo pipefail

HEADER="${1:-}"
if [[ -z "$HEADER" || ! -f "$HEADER" ]]; then
  echo "-> header not at '${HEADER:-<unset>}'; searching sharedLogic/build/bin ..."
  HEADER="$(find sharedLogic/build/bin -name 'Shared.h' -path '*Headers*' 2>/dev/null | head -1)"
fi
if [[ -z "$HEADER" || ! -f "$HEADER" ]]; then
  echo "WARN: Shared.h not found — skipping export-surface inventory (link the framework first)."
  exit 0
fi

echo "== Export-surface inventory: $HEADER =="

ifaces=$(grep -cE '^@interface ' "$HEADER" || true)
protos=$(grep -cE '^@protocol ' "$HEADER" || true)
echo "exported @interface: ${ifaces}    @protocol: ${protos}"

echo "-- exported decls matching our domain (calibration: shows real mangled names) --"
grep -E '^@(interface|protocol) ' "$HEADER" \
  | grep -iE 'Resource|Sync|RoutePath|Domain|DebouncedSearch|PlatformUtils' \
  | sed 's/^/    /' \
  | head -60 \
  || echo "    (none matched)"

# These were removed from the client export by the trim (PR #682). ObjC names
# are framework-prefixed ("Shared" + the Kotlin name); best-effort until a real
# run confirms the exact spellings.
absent_expected=(
  SharedBookResources SharedAdminUserResources SharedScannerResources
  SharedBackupRoutePaths SharedImportRoutePaths
  SharedSyncControl SharedDomainDigest SharedDomainList
)
echo "-- regression check (these should be ABSENT from the framework) --"
leaked=0
for sym in "${absent_expected[@]}"; do
  if grep -qE "^@(interface|protocol) ${sym}\b" "$HEADER"; then
    echo "    PRESENT: ${sym}  <- export-surface regression"
    leaked=$((leaked + 1))
  else
    echo "    absent:  ${sym}"
  fi
done
echo "-- leaked: ${leaked} (informational; not yet gating) --"

exit 0
