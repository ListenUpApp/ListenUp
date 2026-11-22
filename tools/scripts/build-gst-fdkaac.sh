#!/usr/bin/env bash
# Builds GStreamer's fdkaacdec plugin against the host's GStreamer, for CI's xHE-AAC transcode gate.
#
# The server decodes xHE-AAC through Fraunhofer FDK because FFmpeg's own USAC decoder silently drops
# about a quarter of the audio (server/Dockerfile.native says why). Ubuntu, like Debian, ships no
# fdkaacdec — gst-plugins-bad is built without it — so this builds the one plugin the same way the
# image does: `-Dauto_features=disabled -Dfdkaac=enabled`.
#
# ⛔ The FDK library is built too, never taken from apt. The image links Debian 13's libfdk-aac
# 2.0.3; the runner's packaged 2.0.2-3~ubuntu5 refuses this fixture's USAC configuration outright
# (`aacDecoder_ConfigRaw` returns AAC_DEC_UNKNOWN, so fdkaacdec reports not-negotiated), which made
# the gate test a decoder the server never runs. Debian's own source tarball keeps the two the same.
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
  1.28.2) bad_sha256=6467e3964828f4d7d08bfe1fbb4d76287a1c8fa76674e59e101a149c020fefd7 ;;
  *)
    echo "build-gst-fdkaac.sh: no pinned gst-plugins-bad checksum for GStreamer $version;" >&2
    echo "add the published sha256 for gst-plugins-bad-$version.tar.xz to this script." >&2
    exit 1
    ;;
esac

# The image's libfdk-aac2t64 is Debian trixie's fdk-aac 2.0.3-1, built from this orig tarball.
fdk_version=2.0.3
fdk_sha256=e25671cd96b10bad896aa42ab91a695a9e573395262baed4e4a2ff178d6a3a78

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out_dir"
fdk_prefix="$(cd "$out_dir" && pwd)/fdk-aac"

curl -fsSL -o "$work/fdk.tar.gz" \
  "https://deb.debian.org/debian/pool/non-free/f/fdk-aac/fdk-aac_${fdk_version}.orig.tar.gz"
echo "${fdk_sha256}  $work/fdk.tar.gz" | sha256sum -c - >/dev/null
tar -xzf "$work/fdk.tar.gz" -C "$work"
(
  cd "$work/fdk-aac-${fdk_version}"
  ./autogen.sh >/dev/null 2>&1
  ./configure --prefix="$fdk_prefix" --disable-static >/dev/null
  make -j"$(nproc)" >/dev/null
  make install >/dev/null
)

curl -fsSL -o "$work/bad.tar.xz" \
  "https://gstreamer.freedesktop.org/src/gst-plugins-bad/gst-plugins-bad-${version}.tar.xz"
echo "${bad_sha256}  $work/bad.tar.xz" | sha256sum -c - >/dev/null
tar -xJf "$work/bad.tar.xz" -C "$work"

src="$work/gst-plugins-bad-${version}"
PKG_CONFIG_PATH="$fdk_prefix/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}" \
  LDFLAGS="-Wl,-rpath,$fdk_prefix/lib" \
  meson setup "$src/build" "$src" \
  -Dauto_features=disabled -Dfdkaac=enabled -Dexamples=disabled -Dtests=disabled >/dev/null
ninja -C "$src/build" >/dev/null

plugin="$(find "$src/build" -name 'libgstfdkaac.so' -print -quit)"
cp "$plugin" "$out_dir/"
echo "$out_dir/libgstfdkaac.so"
