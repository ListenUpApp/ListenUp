#!/usr/bin/env bash
# Self-test for check-docker-debian-release.sh: a guard that never fails is worse than none, so prove
# it passes the real Dockerfile and fails each way a Dependabot bump could split the releases.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CHECK="$SCRIPT_DIR/check-docker-debian-release.sh"
REAL="$REPO_ROOT/server/Dockerfile.native"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0

expect() {
  local want="$1" label="$2" file="$3"
  bash "$CHECK" "$file" >"$WORK/out" 2>&1
  local got=$?
  if { [ "$want" = pass ] && [ "$got" -eq 0 ]; } || { [ "$want" = fail ] && [ "$got" -ne 0 ]; }; then
    echo "ok   — $label"
  else
    echo "FAIL — $label: expected $want, exit $got" >&2
    sed 's/^/       /' "$WORK/out" >&2
    failures=$((failures + 1))
  fi
}

# Rewrites the real Dockerfile so each case starts from what actually ships.
variant() {
  local name="$1" expr="$2"
  sed -E "$expr" "$REAL" >"$WORK/$name"
  if cmp -s "$REAL" "$WORK/$name"; then
    echo "FAIL — variant '$name' changed nothing; the fixture no longer matches the Dockerfile" >&2
    failures=$((failures + 1))
  fi
  echo "$WORK/$name"
}

expect pass "the committed server/Dockerfile.native" "$REAL"
expect fail "runtime on an older distroless debian" \
  "$(variant runtime-older 's#distroless/cc-debian[0-9]+#distroless/cc-debian12#')"
expect fail "runtime on a newer distroless debian" \
  "$(variant runtime-newer 's#distroless/cc-debian[0-9]+#distroless/cc-debian14#')"
expect fail "one builder stage left behind" \
  "$(variant one-builder-behind '0,/^FROM debian:[0-9]+/s//FROM debian:12/')"
expect fail "a builder named by codename on another release" \
  "$(variant codename '0,/^FROM debian:[0-9]+-slim/s//FROM debian:bookworm-slim/')"
expect fail "no distroless runtime stage at all" \
  "$(variant no-runtime 's#^FROM gcr.io/distroless/.*#FROM scratch#')"
expect fail "a missing Dockerfile" "$WORK/does-not-exist"

if [ "$failures" -gt 0 ]; then
  echo "check-docker-debian-release.test: $failures case(s) wrong" >&2
  exit 1
fi
echo "check-docker-debian-release.test: all cases behave."
