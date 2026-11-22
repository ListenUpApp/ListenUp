#!/usr/bin/env bash
# Builds GStreamer's fdkaacdec plugin against the host's GStreamer, for CI's xHE-AAC transcode gate.
#
# The server decodes xHE-AAC through Fraunhofer FDK because FFmpeg's own USAC decoder silently drops
# about a quarter of the audio (server/Dockerfile.native says why). Ubuntu, like Debian, ships no
# fdkaacdec — libfdk-aac is in multiverse and gst-plugins-bad is built without it — so this builds
# the one plugin the same way the image does: `-Dauto_features=disabled -Dfdkaac=enabled`.
#
# Usage: build-gst-fdkaac.sh <output-dir>
# Prints nothing on success but the plugin's path. Fails if the host GStreamer is a version whose
# plugins-bad checksum is not pinned below: a plugins-bad must match its core's major.minor, and an
# unpinned download is not one this gate should build from.
set -euo pipefail

out_dir="${1:?usage: build-gst-fdkaac.sh <output-dir>}"
version="$(pkg-config --modversion gstreamer-1.0)"

# Published by gstreamer.freedesktop.org as gst-plugins-bad-<version>.tar.xz.sha256sum.
case "$version" in
  1.24.2) sha256=448e32787bc82b586c6cb2f81c9a8ef404fea4f77f25566fe06e597a3f59136b ;;
  *)
    echo "build-gst-fdkaac.sh: no pinned gst-plugins-bad checksum for GStreamer $version;" >&2
    echo "add the published sha256 for gst-plugins-bad-$version.tar.xz to this script." >&2
    exit 1
    ;;
esac

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

curl -fsSL -o "$work/bad.tar.xz" \
  "https://gstreamer.freedesktop.org/src/gst-plugins-bad/gst-plugins-bad-${version}.tar.xz"
echo "${sha256}  $work/bad.tar.xz" | sha256sum -c - >/dev/null
tar -xJf "$work/bad.tar.xz" -C "$work"

src="$work/gst-plugins-bad-${version}"
meson setup "$src/build" "$src" \
  -Dauto_features=disabled -Dfdkaac=enabled -Dexamples=disabled -Dtests=disabled >/dev/null
ninja -C "$src/build" >/dev/null

mkdir -p "$out_dir"
plugin="$(find "$src/build" -name 'libgstfdkaac.so' -print -quit)"
cp "$plugin" "$out_dir/"
echo "$out_dir/libgstfdkaac.so"
