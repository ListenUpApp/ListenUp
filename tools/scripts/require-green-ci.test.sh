#!/usr/bin/env bash
# Self-test for require-green-ci.sh: a release gate that never refuses is worse than none, so prove it
# lets a green main commit through and stops every other case, against a stubbed `gh`.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$SCRIPT_DIR/require-green-ci.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SHA="0123456789abcdef0123456789abcdef01234567"

# The stub answers `gh run list … --json conclusion` with $FAKE_RUNS and records its arguments, so the
# test also proves the gate asks about this commit's push-triggered CI run and no other.
mkdir -p "$WORK/bin"
cat >"$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >"$FAKE_ARGS"
printf '%s\n' "$FAKE_RUNS"
STUB
chmod +x "$WORK/bin/gh"

failures=0

expect() {
  local want="$1" label="$2" ref="$3" runs="$4"
  rm -f "$WORK/args"
  PATH="$WORK/bin:$PATH" FAKE_ARGS="$WORK/args" FAKE_RUNS="$runs" \
    GITHUB_REF="$ref" GITHUB_SHA="$SHA" GITHUB_REPOSITORY="ListenUpApp/ListenUp" \
    bash "$CHECK" >"$WORK/out" 2>&1
  local got=$?
  if { [ "$want" = pass ] && [ "$got" -eq 0 ]; } || { [ "$want" = fail ] && [ "$got" -ne 0 ]; }; then
    echo "ok   — $label"
  else
    echo "FAIL — $label: expected $want, exit $got" >&2
    sed 's/^/       /' "$WORK/out" >&2
    failures=$((failures + 1))
  fi
}

expect pass "main, CI succeeded" refs/heads/main '[{"conclusion":"success"}]'
for flag in "--commit $SHA" "--workflow ci.yml" "--event push" "--repo ListenUpApp/ListenUp"; do
  if ! grep -q -- "$flag" "$WORK/args" 2>/dev/null; then
    echo "FAIL — the gate did not pass '$flag' to gh run list" >&2
    failures=$((failures + 1))
  fi
done
expect fail "main, CI failed" refs/heads/main '[{"conclusion":"failure"}]'
expect fail "main, CI cancelled" refs/heads/main '[{"conclusion":"cancelled"}]'
expect fail "main, CI still running (empty conclusion)" refs/heads/main '[{"conclusion":""}]'
expect fail "main, no CI run for the commit" refs/heads/main '[]'
expect fail "a feature branch, even with green CI" refs/heads/feature/x '[{"conclusion":"success"}]'
expect fail "a tag ref, even with green CI" refs/tags/v1.0.0 '[{"conclusion":"success"}]'

if [ "$failures" -gt 0 ]; then
  echo "require-green-ci.test: $failures case(s) wrong" >&2
  exit 1
fi
echo "require-green-ci.test: all cases behave."
