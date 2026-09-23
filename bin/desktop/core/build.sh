#!/usr/bin/env bash
# Build the owenclave desktop proxy core (exclave-core CLI) for desktop platforms.
#
# The Android app links the same engine through gomobile (`libowenclavecore.aar`).
# On desktop we ship it as a standalone CLI which consumes the very same
# V2Ray-format JSON that `ConfigBuilder` produces for Android.
#
# Usage:
#   ./run desktop core build                 # host platform only
#   TARGETS="darwin/arm64" ./run desktop core build
#   ./run desktop core build all             # every supported target
#
# This script intentionally does not source bin/init/env.sh: it needs no Android
# NDK, and it has to work on plain CI runners where ANDROID_NDK_HOME is unset.
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

OUT_ROOT="${OUT_ROOT:-$PROJECT/desktop/core/dist}"
CORE_PKG="github.com/exclavenetwork/exclave-core/v5/main"
LDFLAGS="-s -w -buildid="

host_os="$(go env GOOS)"
host_arch="$(go env GOARCH)"
default_targets="$host_os/$host_arch"

if [ "${1:-}" = "all" ]; then
  default_targets="linux/amd64 linux/arm64 darwin/amd64 darwin/arm64 windows/amd64 windows/arm64"
fi

TARGETS="${TARGETS:-$default_targets}"

cd "$PROJECT/library/core"

for target in $TARGETS; do
  target_os="${target%%/*}"
  target_arch="${target##*/}"

  out_dir="$OUT_ROOT/$target_os-$target_arch"
  mkdir -p "$out_dir"

  bin_name="owenclave-core"
  if [ "$target_os" = "windows" ]; then
    bin_name="owenclave-core.exe"
  fi

  echo ">>> building core for $target_os/$target_arch"
  CGO_ENABLED=0 GOOS="$target_os" GOARCH="$target_arch" \
    go build -trimpath -tags "with_clash" -ldflags "$LDFLAGS" \
    -o "$out_dir/$bin_name" "$CORE_PKG"

  ls -la "$out_dir/$bin_name"
done

echo "=== desktop core built into $OUT_ROOT ==="
