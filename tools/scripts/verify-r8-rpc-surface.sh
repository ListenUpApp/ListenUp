#!/usr/bin/env bash
# Assert every @Rpc service interface survives R8 in a built Android artifact.
#
# WHY THIS EXISTS
# kotlinx.rpc ships NO consumer ProGuard/R8 rules of its own (verified against the
# 0.11.0 artifacts), unlike Compose, Room, Ktor, Koin and kotlinx.serialization. Its
# client resolves each @Rpc service by interface name to build the proxy, so if R8
# renames them the app cannot open a single RPC call — and because the whole app
# surface is RPC, it cannot reach any server at all.
#
# That failure is invisible to every other gate: it only affects `release` (where
# isMinifyEnabled = true), it throws before a socket is opened, it is caught and
# mapped to a generic AppError, and release builds strip logging. ListenUp 0.8.0
# (versionCode 2756) shipped to Play in exactly that state — no tester could get
# past the "Connect to Server" screen.
#
# Usage: verify-r8-rpc-surface.sh <path-to-.apk-or-.aab>
set -euo pipefail

ARTIFACT="${1:-}"
if [[ -z "$ARTIFACT" || ! -f "$ARTIFACT" ]]; then
    echo "usage: $(basename "$0") <path-to-.apk-or-.aab>" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
API_DIR="$REPO_ROOT/contract/src/commonMain/kotlin/com/calypsan/listenup/api"

# Source of truth: the interfaces actually annotated @Rpc, so the gate tracks the
# contract instead of a list that rots.
mapfile -t SERVICES < <(grep -rl '^@Rpc' "$API_DIR" --include='*.kt' | xargs -r -n1 basename | sed 's/\.kt$//' | sort -u)

if [[ ${#SERVICES[@]} -eq 0 ]]; then
    echo "FAIL: found no @Rpc interfaces under $API_DIR — has the contract moved?" >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q -o "$ARTIFACT" '*.dex' -d "$WORK" 2>/dev/null || true

shopt -s globstar nullglob
DEX_FILES=("$WORK"/**/*.dex)
if [[ ${#DEX_FILES[@]} -eq 0 ]]; then
    echo "FAIL: no .dex found in $ARTIFACT" >&2
    exit 1
fi

DEX_STRINGS="$WORK/strings.txt"
strings "${DEX_FILES[@]}" > "$DEX_STRINGS"

missing=()
for svc in "${SERVICES[@]}"; do
    # DEX stores types as descriptors: Lcom/calypsan/listenup/api/BookService;
    if ! grep -q "com/calypsan/listenup/api/${svc}" "$DEX_STRINGS"; then
        missing+=("$svc")
    fi
done

if [[ ${#missing[@]} -gt 0 ]]; then
    cat >&2 <<EOF
FAIL: R8 stripped or renamed ${#missing[@]} of ${#SERVICES[@]} @Rpc service interfaces.

Missing from $(basename "$ARTIFACT"):
$(printf '  - %s\n' "${missing[@]}")

The app will fail EVERY server connection with a generic error and no network
activity. Check that app/androidApp/proguard-rules.pro still contains:

    -keep @kotlinx.rpc.annotations.Rpc interface * { *; }
EOF
    exit 1
fi

# Keeping the interfaces is NOT sufficient, and checking only for them gave a FALSE
# GREEN on a build that still could not open a socket. R8 had also stripped the
# reflective runtime that builds a proxy FROM those interfaces. Measured on real
# artifacts: a broken build carried 1 kotlinx/rpc class path and no serviceDescriptorOf;
# a verified-working one carried 103 and had it. The floor is deliberately well below
# 103 so ordinary dependency churn doesn't trip it, but far above the broken value.
MIN_RPC_RUNTIME_CLASSES=20
runtime_classes=$(grep -oE "kotlinx/rpc/[a-zA-Z0-9/_]+" "$DEX_STRINGS" | sort -u | wc -l)
has_descriptor=$(grep -c "serviceDescriptorOf" "$DEX_STRINGS" || true)

if [[ "$runtime_classes" -lt "$MIN_RPC_RUNTIME_CLASSES" || "$has_descriptor" -eq 0 ]]; then
    cat >&2 <<EOF
FAIL: the @Rpc interfaces survived but the kotlinx.rpc RUNTIME did not.

  kotlinx/rpc class paths : $runtime_classes (need >= $MIN_RPC_RUNTIME_CLASSES)
  serviceDescriptorOf     : $([[ "$has_descriptor" -gt 0 ]] && echo present || echo MISSING)

An interface R8 cannot build a proxy from is as useless as one it renamed — the app
still fails every connection with no network activity. Check that
app/androidApp/proguard-rules.pro still contains:

    -keep class kotlinx.rpc.** { *; }
    -keep class com.calypsan.listenup.api.** { *; }
EOF
    exit 1
fi

echo "OK: ${#SERVICES[@]} @Rpc interfaces + kotlinx.rpc runtime ($runtime_classes classes, serviceDescriptorOf present) in $(basename "$ARTIFACT")"
