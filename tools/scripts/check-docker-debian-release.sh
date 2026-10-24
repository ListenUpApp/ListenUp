#!/usr/bin/env bash
# Fails if the server image's builder stages and its distroless runtime base are on different
# Debian releases.
#
# WHY THIS EXISTS. server/Dockerfile.native compiles and stages shared libraries (our SQLite,
# libcurl and its dependency closure, GStreamer, FFmpeg) in `debian:N` builder stages and copies
# them into a `gcr.io/distroless/*-debianN` runtime. Every one of those .so files is linked against
# the builder's glibc. Dependabot bumps each FROM line on its own: #1630 moved the builders to
# debian:13 (glibc 2.41) while the runtime stayed on cc-debian12 (glibc 2.36), so the image would
# have shipped a libcurl that needs symbols its glibc does not have, and the server could not load
# it. Nothing in CI builds that image, so the split was invisible until a release.
#
# Usage: check-docker-debian-release.sh [Dockerfile]   (defaults to server/Dockerfile.native)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DOCKERFILE="${1:-$REPO_ROOT/server/Dockerfile.native}"
NAME="check-docker-debian-release"

if [ ! -f "$DOCKERFILE" ]; then
  echo "$NAME: FAIL — $DOCKERFILE not found (did the server image move?)" >&2
  exit 1
fi

# Debian codenames Dependabot may write instead of a version number.
release_of_codename() {
  case "$1" in
    bookworm) echo 12 ;;
    trixie) echo 13 ;;
    forky) echo 14 ;;
    duke) echo 15 ;;
    *) echo "" ;;
  esac
}

builder_releases=()
runtime_releases=()
unparsed=()

# `FROM [--platform=…] <image>[@digest] [AS name]` — the image is the first argument that is not a flag.
while read -r image; do
  image="${image%%@*}"
  case "$image" in
    debian:* | docker.io/debian:* | docker.io/library/debian:* | library/debian:*)
      tag="${image##*:}"
      major="${tag%%[.-]*}"
      if ! [[ "$major" =~ ^[0-9]+$ ]]; then
        major="$(release_of_codename "$major")"
      fi
      if [ -n "$major" ]; then builder_releases+=("$major"); else unparsed+=("$image"); fi
      ;;
    gcr.io/distroless/*)
      if [[ "$image" =~ -debian([0-9]+)(:|$) ]]; then
        runtime_releases+=("${BASH_REMATCH[1]}")
      else
        unparsed+=("$image")
      fi
      ;;
  esac
done < <(awk 'toupper($1) == "FROM" { for (i = 2; i <= NF; i++) if ($i !~ /^--/) { print $i; break } }' "$DOCKERFILE")

if [ "${#unparsed[@]}" -gt 0 ]; then
  echo "$NAME: FAIL — cannot read a Debian release from: ${unparsed[*]}" >&2
  echo "  Use a numeric tag (debian:13-slim) or a known codename, and a distroless *-debianN base." >&2
  exit 1
fi

# Anchors: with no builder or no runtime stage found, the comparison below would pass vacuously.
if [ "${#builder_releases[@]}" -eq 0 ] || [ "${#runtime_releases[@]}" -eq 0 ]; then
  echo "$NAME: FAIL — found ${#builder_releases[@]} debian builder stage(s) and ${#runtime_releases[@]}" >&2
  echo "  distroless runtime stage(s) in $DOCKERFILE. The image layout changed: update this script," >&2
  echo "  or it is checking nothing." >&2
  exit 1
fi

distinct="$(printf '%s\n' "${builder_releases[@]}" "${runtime_releases[@]}" | sort -u)"
if [ "$(printf '%s\n' "$distinct" | wc -l)" -ne 1 ]; then
  echo "$NAME: FAIL — $DOCKERFILE mixes Debian releases:" >&2
  echo "  builder stages: debian ${builder_releases[*]}" >&2
  echo "  runtime base:   debian ${runtime_releases[*]}" >&2
  echo "" >&2
  echo "  Every library staged into the runtime is linked against the builder's glibc. Move the" >&2
  echo "  debian:N builders and the distroless *-debianN base together, in one change." >&2
  exit 1
fi

echo "$NAME: clean (builders and runtime all on Debian $distinct)."
