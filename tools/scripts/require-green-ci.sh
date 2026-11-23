#!/usr/bin/env bash
# Refuses a release unless it is dispatched on main and the CI workflow's push run on this exact
# commit concluded `success`.
#
# WHY THIS EXISTS. The Release workflow used to build whatever main held, green or not: nothing
# looked at CI. A red main shipped is a red build on every tester's phone. A run still in progress
# (empty conclusion) or no run at all counts as not green: wait for it rather than guess.
#
# Usage (in Actions): GH_TOKEN=… require-green-ci.sh
#   reads GITHUB_REF, GITHUB_SHA, GITHUB_REPOSITORY; needs `gh` and `jq` on PATH.
set -euo pipefail
NAME="require-green-ci"

if [ "${GITHUB_REF}" != "refs/heads/main" ]; then
  echo "::error::Releases are cut from main (this run is on ${GITHUB_REF})."
  exit 1
fi

RUNS="$(gh run list --repo "$GITHUB_REPOSITORY" --workflow ci.yml --commit "$GITHUB_SHA" \
  --event push --limit 1 --json conclusion)"
CONCLUSION="$(jq -r '.[0].conclusion // "none" | if . == "" then "pending" else . end' <<<"$RUNS")"

if [ "$CONCLUSION" != "success" ]; then
  echo "::error::CI on $GITHUB_SHA concluded '$CONCLUSION', not 'success'. Wait for it, fix main, or re-run with skip_ci_check."
  exit 1
fi
echo "$NAME: CI passed on $GITHUB_SHA."
