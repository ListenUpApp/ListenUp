#!/usr/bin/env bash
# Boots the given image and proves the server reaches a healthy /healthz — which transitively proves
# migration V9 (FTS5 contentless_delete, SQLite >= 3.43) applied, since migrations run at boot before
# /healthz serves. A too-old SQLite dies at V9 and /healthz never returns 200.
# Usage: serve-smoke.sh <image-ref> [docker-run-platform-flag]
set -euo pipefail
IMAGE="${1:?usage: serve-smoke.sh <image> [--platform linux/arm64]}"
PLATFORM_FLAG="${2:-}"
NAME="lu-smoke-$$"
HOST_PORT=18080

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# --tmpfs /data gives a world-writable dir so the non-root (uid 65532) server can mkdir LISTENUP_HOME.
docker run -d --name "$NAME" $PLATFORM_FLAG \
    --tmpfs /data \
    -e LISTENUP_HOME=/data \
    -e PORT=8080 \
    -p "${HOST_PORT}:8080" \
    "$IMAGE" >/dev/null

ok=""
for _ in $(seq 1 60); do
    if curl -fsS "http://localhost:${HOST_PORT}/healthz" 2>/dev/null | grep -q '"status":"ok"'; then
        ok=1; break
    fi
    sleep 2
done

echo "----- container logs -----"
docker logs "$NAME" 2>&1 | tail -40

if [ -z "$ok" ]; then
    echo "SMOKE FAILED: /healthz never returned 200 within 120s" >&2
    exit 1
fi
# Defensive: assert migration V9 did not fail (the exact failure 050 §4 documented).
if docker logs "$NAME" 2>&1 | grep -qiE 'contentless_delete|Migration V9.*fail'; then
    echo "SMOKE FAILED: migration V9 error in logs (SQLite too old?)" >&2
    exit 1
fi
echo "SMOKE OK: /healthz=200 and no V9 migration failure"
