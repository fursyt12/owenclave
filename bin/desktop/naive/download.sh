#!/usr/bin/env bash
# Download the upstream NaiveProxy binary for desktop platforms.
#
# NaiveProxy is not part of the Go core: the Android app ships it as an external
# plugin (`libnaive.so`), and the desktop client runs it the same way, as a child
# process that exposes a local SOCKS listener which the core dials into.
# klzgrad/naiveproxy publishes prebuilt binaries, so no Chromium toolchain is
# needed here.
#
# Usage:
#   ./run desktop naive download            # host platform only
#   TARGETS="darwin/arm64" ./run desktop naive download
#   ./run desktop naive download all        # linux + macos + windows
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

VERSION="${NAIVE_VERSION:-$(grep -E '^NAIVE_VERSION_NAME=' "$PROJECT/version.properties" | cut -d= -f2 | tr -d '[:space:]')}"
BASE_URL="https://github.com/klzgrad/naiveproxy/releases/download/v$VERSION"
OUT_ROOT="${OUT_ROOT:-$PROJECT/desktop/naive/dist}"
CACHE_DIR="${CACHE_DIR:-$PROJECT/desktop/naive/cache}"

host_os="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$host_os" in
  darwin) host_os="darwin" ;;
  linux) host_os="linux" ;;
  mingw*|msys*|cygwin*) host_os="windows" ;;
esac
host_arch="$(uname -m)"
case "$host_arch" in
  x86_64|amd64) host_arch="amd64" ;;
  aarch64|arm64) host_arch="arm64" ;;
esac
default_targets="$host_os/$host_arch"

if [ "${1:-}" = "all" ]; then
  default_targets="linux/amd64 linux/arm64 darwin/amd64 darwin/arm64 windows/amd64 windows/arm64 windows/386"
fi

TARGETS="${TARGETS:-$default_targets}"

asset_for() {
  case "$1" in
    linux/amd64)   echo "linux-x64.tar.xz" ;;
    linux/arm64)   echo "linux-arm64.tar.xz" ;;
    darwin/amd64)  echo "mac-x64-x64.tar.xz" ;;
    darwin/arm64)  echo "mac-arm64-arm64.tar.xz" ;;
    windows/amd64) echo "win-x64.zip" ;;
    windows/arm64) echo "win-arm64.zip" ;;
    windows/386)   echo "win-x86.zip" ;;
    *) return 1 ;;
  esac
}

sha_for() {
  case "$1" in
    linux/amd64)   echo "0c4f506ce66a7881892fd6932b542c53fc06ac2351987756096c61e753c687bf" ;;
    linux/arm64)   echo "f28046ae6adb3f9ca13b410f5bba0c4229994860626a83090c2bba0dc85fda13" ;;
    darwin/amd64)  echo "92e7fe5f3cfca5e0cca49798e5c435a5bd7ef05b543c0aa614a5623ad39a50d4" ;;
    darwin/arm64)  echo "315f946fa91a65a30b25b69bba88836a117838f58b616c6a58c970c9ff1d9bbf" ;;
    windows/amd64) echo "d09e35f9fde6206a775a1b930d7d8252053bee1408ee1c910b5681346c68d1a1" ;;
    windows/arm64) echo "d31098b248c41ea54921101f362ce074d59ceb92f9bd29a9e5fca7284b030e22" ;;
    windows/386)   echo "8059c126fd98a117d89d9d5b54a07756fe944c3257013866c302b9999c02c530" ;;
    *) return 1 ;;
  esac
}

# `sha256sum` is not guaranteed to exist in Git Bash, while python3 is available on
# every platform we build for.
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    python3 -c 'import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$1"
  fi
}

mkdir -p "$CACHE_DIR"

for target in $TARGETS; do
  target_os="${target%%/*}"
  target_arch="${target##*/}"
  suffix="$(asset_for "$target")"
  expected="$(sha_for "$target")"
  asset="naiveproxy-v$VERSION-$suffix"
  archive="$CACHE_DIR/$asset"

  if [ ! -s "$archive" ] || [ "$(sha256_of "$archive")" != "$expected" ]; then
    echo ">>> downloading $asset"
    curl -fL --retry 3 "$BASE_URL/$asset" -o "$archive"
  fi
  actual="$(sha256_of "$archive")"
  if [ "$actual" != "$expected" ]; then
    echo "!!! checksum mismatch for $asset: expected $expected, got $actual" >&2
    exit 1
  fi

  out_dir="$OUT_ROOT/$target_os-$target_arch"
  rm -rf "$out_dir"
  mkdir -p "$out_dir"

  python3 - "$archive" "$out_dir" "$target_os" <<'PY'
import os, shutil, sys, tarfile, tempfile, zipfile

archive, out_dir, target_os = sys.argv[1], sys.argv[2], sys.argv[3]
wanted = "naive.exe" if target_os == "windows" else "naive"

with tempfile.TemporaryDirectory() as tmp:
    if archive.endswith(".zip"):
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(tmp)
    else:
        with tarfile.open(archive) as tf:
            tf.extractall(tmp)

    found = None
    for root, _dirs, files in os.walk(tmp):
        for name in files:
            if name == wanted:
                found = os.path.join(root, name)
                break
        if found:
            break
    if not found:
        sys.exit(f"{wanted} not found inside {archive}")

    destination = os.path.join(out_dir, wanted)
    shutil.copy2(found, destination)
    os.chmod(destination, 0o755 if target_os != "windows" else 0o644)
    print(f"    extracted -> {destination}")
PY

  ls -la "$out_dir"
done

echo "=== desktop naive binaries in $OUT_ROOT ==="
