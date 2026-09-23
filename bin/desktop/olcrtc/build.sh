#!/usr/bin/env bash
# Build the olcrtc WebRTC proxy plugin for desktop platforms.
#
# olcrtc is not part of the Go core (nor of the Android app's in-process engine):
# the Android app ships it as `libolcrtc.so` and runs it as an external engine,
# and the desktop client runs it the same way, as a child process that exposes a
# local SOCKS listener the core dials into. The upstream project does not publish
# prebuilt binaries, so it is built from a pinned commit.
#
# Usage:
#   ./run desktop olcrtc build               # host platform only
#   TARGETS="darwin/arm64" ./run desktop olcrtc build
#   ./run desktop olcrtc build all           # linux + macos + windows
#
# This script intentionally does not source bin/init/env.sh: the desktop plugin
# uses the pure Go build (CGO_ENABLED=0) and needs no Android NDK.
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

OUT_ROOT="${OUT_ROOT:-$PROJECT/desktop/olcrtc/dist}"
OLCRTC_COMMIT="${OLCRTC_COMMIT:-f7068190fe340749a13a0e0fa578c3c67a898c39}"
OLCRTC_SRC="${OLCRTC_SRC:-$PROJECT/desktop/olcrtc/cache/src}"

host_os="$(go env GOOS)"
host_arch="$(go env GOARCH)"
default_targets="$host_os/$host_arch"

if [ "${1:-}" = "all" ]; then
  default_targets="linux/amd64 darwin/amd64 darwin/arm64 windows/amd64 windows/arm64"
fi

TARGETS="${TARGETS:-$default_targets}"

if [ ! -d "$OLCRTC_SRC/.git" ]; then
  rm -rf "$OLCRTC_SRC"
  git clone https://github.com/openlibrecommunity/olcrtc.git "$OLCRTC_SRC"
fi
git -C "$OLCRTC_SRC" fetch --depth 1 origin "$OLCRTC_COMMIT"
git -C "$OLCRTC_SRC" checkout --detach "$OLCRTC_COMMIT"

echo "olcrtc src: $OLCRTC_SRC"

for target in $TARGETS; do
  target_os="${target%%/*}"
  target_arch="${target##*/}"

  out_dir="$OUT_ROOT/$target_os-$target_arch"
  mkdir -p "$out_dir"

  bin_name="olcrtc"
  if [ "$target_os" = "windows" ]; then
    bin_name="olcrtc.exe"
  fi

  echo ">>> building olcrtc for $target_os/$target_arch"
  ( cd "$OLCRTC_SRC" && \
    CGO_ENABLED=0 GOOS="$target_os" GOARCH="$target_arch" \
    go build -trimpath -ldflags "-s -w" -o "$out_dir/$bin_name" ./cmd/olcrtc )

  ls -la "$out_dir/$bin_name"
done

echo "=== desktop olcrtc binaries in $OUT_ROOT ==="
