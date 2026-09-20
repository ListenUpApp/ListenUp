#!/usr/bin/env bash
# Boots the given image and proves the server inside it reaches a healthy /healthz.
#
# Reaching /healthz is a whole-boot proof, not a liveness ping: MigrationRunner applies every
# migration before the server serves, so a healthy body means the runtime SQLite understood every
# statement under db/migration — including the fts5 virtual tables V1__baseline.sql still creates
# and the `ALTER TABLE ... DROP COLUMN` in V53__drop_blurhash_columns.sql that sets the 3.35 floor
# Dockerfile.native enforces at build time. A too-old or FTS5-less SQLite dies during migration and
# /healthz never answers.
#
# Deliberately VERSION-AGNOSTIC. This script's first version asserted on a migration called "V9";
# V9 was squashed away and the assertion quietly rotted into a no-op. Anything migration-specific
# belongs in /healthz's own schemaVersion field, which this script reads, not in a grep of log text.
#
# Usage: serve-smoke.sh <image-ref> [docker-run-platform-flag] [expected-version]
#   serve-smoke.sh listenup-smoke:local
#   serve-smoke.sh ghcr.io/listenupapp/listenup-server:0.9.3 "" 0.9.3
#   serve-smoke.sh ghcr.io/listenupapp/listenup-server:0.9.3 "--platform linux/arm64"
set -euo pipefail
IMAGE="${1:?usage: serve-smoke.sh <image> [--platform linux/arm64] [expected-version]}"
PLATFORM_FLAG="${2:-}"
EXPECTED_VERSION="${3:-}"
NAME="lu-smoke-$$"
HOST_PORT=18080
ATTEMPTS=60
INTERVAL_SECONDS=2
# Unquoted on the RHS of =~ so bash reads it as a regex: the key must be present AND carry a
# non-empty STRING, which `"schemaVersion":null` (an unmigrated database) is not.
SCHEMA_VERSION_PATTERN='"schemaVersion":"[^"]+"'

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# --tmpfs /data gives a world-writable dir so the non-root (uid 65532) server can mkdir LISTENUP_HOME.
# $PLATFORM_FLAG is unquoted on purpose: empty it must vanish entirely, and non-empty it is two argv
# words ("--platform linux/arm64"). Quoting would hand docker run one glued argument or an empty one.
# shellcheck disable=SC2086
docker run -d --name "$NAME" $PLATFORM_FLAG \
    --tmpfs /data \
    -e LISTENUP_HOME=/data \
    -e PORT=8080 \
    -p "${HOST_PORT}:8080" \
    "$IMAGE" >/dev/null

BODY=""
for _ in $(seq 1 "$ATTEMPTS"); do
    if response=$(curl -fsS "http://localhost:${HOST_PORT}/healthz" 2>/dev/null); then
        if [[ "$response" == *'"status":"ok"'* ]]; then
            BODY="$response"
            break
        fi
    fi
    sleep "$INTERVAL_SECONDS"
done

# Captured once into a variable rather than re-run through a pipe per check: `docker logs | grep -q`
# under `set -o pipefail` can report the pipeline as failed when grep exits early and docker logs
# takes SIGPIPE, which would silently turn a real migration failure into "no match".
LOGS="$(docker logs "$NAME" 2>&1 || true)"
echo "----- container logs -----"
# Diagnostic dump, not an assertion — never let it decide the exit status.
printf '%s\n' "$LOGS" | tail -40 || true

# Checked BEFORE the timeout verdict: a failed migration is the specific cause behind the generic
# "it never came up", and naming it is the difference between a diagnosable CI failure and a shrug.
# The pattern matches MigrationRunner.applyOne's wrapper text for any version.
if grep -qE 'Migration V[0-9]+ .* failed' <<<"$LOGS"; then
    echo "SMOKE FAILED: a migration failed at boot (see the container logs above)" >&2
    exit 1
fi

if [ -z "$BODY" ]; then
    echo "SMOKE FAILED: /healthz never reported status=ok within $((ATTEMPTS * INTERVAL_SECONDS))s" >&2
    exit 1
fi

if [[ ! "$BODY" =~ $SCHEMA_VERSION_PATTERN ]]; then
    echo "SMOKE FAILED: /healthz reports no applied schema version — body: $BODY" >&2
    exit 1
fi

# Proves the image that booted is the build under release, not a stale layer or a mis-tagged cache.
if [ -n "$EXPECTED_VERSION" ] && [[ "$BODY" != *"\"version\":\"${EXPECTED_VERSION}\""* ]]; then
    echo "SMOKE FAILED: /healthz does not report version ${EXPECTED_VERSION} — body: $BODY" >&2
    exit 1
fi

# The web client the image now ships. A healthy /healthz says nothing about it: the bundle is
# COPYed in a later layer and mounted from LISTENUP_WEB_ROOT, so a missing COPY, a mistyped path or
# a knob dead on native all leave /healthz green and / a 404 — which is exactly the state the whole
# platform sat in, built and CI-tested, through every release before this one. Fail closed here.
INDEX=$(curl -fsS "http://localhost:${HOST_PORT}/" 2>/dev/null || true)
if [ -z "$INDEX" ]; then
    echo "SMOKE FAILED: / served nothing — the image carries no web client" >&2
    exit 1
fi
if [[ "$INDEX" != *"<!doctype html>"* && "$INDEX" != *"<!DOCTYPE html>"* ]]; then
    echo "SMOKE FAILED: / did not serve an HTML document — body starts: ${INDEX:0:120}" >&2
    exit 1
fi
# An index that references no bundle is a shell with no app in it — the "partially-served shell"
# WebRootResolutionTest fails closed to avoid, arriving by a different route.
if [[ "$INDEX" != *"/assets/"* ]]; then
    echo "SMOKE FAILED: / served HTML referencing no assets — body starts: ${INDEX:0:200}" >&2
    exit 1
fi
# And the asset it names must actually be reachable, or the page loads to a blank screen.
ASSET=$(grep -oE '/assets/[A-Za-z0-9_.-]+\.js' <<<"$INDEX" | head -1)
if [ -z "$ASSET" ] || ! curl -fsS -o /dev/null "http://localhost:${HOST_PORT}${ASSET}"; then
    echo "SMOKE FAILED: the web client's script ${ASSET:-<none found>} is not served" >&2
    exit 1
fi
echo "SMOKE OK: web client served at / (script ${ASSET})"

echo "SMOKE OK: /healthz healthy after migrations — $BODY"
