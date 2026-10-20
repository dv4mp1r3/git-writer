#!/usr/bin/env bash
#
# Builds libgit2 as a static library for the iOS targets of the shared module, a folder per Kotlin target:
#
#   third_party/libgit2/slices/<target>/include/   the headers cinterop binds
#   third_party/libgit2/slices/<target>/lib/libgit2.a
#
# shared/build.gradle.kts and shared/src/nativeInterop/cinterop/git2.def take it from there. The output is
# not in git: run this once on a new machine, and again after changing the version.
#
# Usage: tools/build-libgit2.sh [--clean]      (needs Xcode, and: brew install cmake ninja)

set -euo pipefail

LIBGIT2_VERSION="${LIBGIT2_VERSION:-1.9.7}"
IOS_DEPLOYMENT_TARGET="${IOS_DEPLOYMENT_TARGET:-13.0}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/build/libgit2-src"
OUTPUT="$ROOT/third_party/libgit2/slices"

# Kotlin target | Xcode SDK | architecture
TARGETS=(
  "iosArm64|iphoneos|arm64"
  "iosSimulatorArm64|iphonesimulator|arm64"
)

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

check_tools() {
  export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
  [[ -d "$DEVELOPER_DIR" ]] || die "no Xcode at $DEVELOPER_DIR - install it, or point DEVELOPER_DIR at it"
  for tool in cmake ninja git; do
    command -v "$tool" >/dev/null || die "$tool not found - brew install $tool"
  done
}

download_source() {
  if [[ ! -d "$SOURCE/.git" ]]; then
    log "Downloading libgit2 $LIBGIT2_VERSION"
    git clone --quiet --depth 1 --branch "v$LIBGIT2_VERSION" https://github.com/libgit2/libgit2.git "$SOURCE"
  fi

  local version
  version="$(sed -nE 's/^#define LIBGIT2_VERSION[[:space:]]+"(.*)"/\1/p' "$SOURCE/include/git2/version.h")"
  [[ "$version" == "$LIBGIT2_VERSION" ]] || die "$SOURCE is libgit2 $version, not $LIBGIT2_VERSION - run with --clean"
}

build() {
  local target="$1" sdk="$2" arch="$3"
  local build_dir="$ROOT/build/libgit2-$target"
  log "Building $target ($sdk, $arch)"

  # a new build folder each time: CMake caches what it found in the SDK it was first configured with
  rm -rf "$build_dir"

  # - CMAKE_SYSTEM_NAME=iOS turns on libgit2's iOS handling
  # - https goes through Apple's SecureTransport and zlib is bundled, so it only needs system frameworks
  # - no SSH, the app syncs over https; SHA1 has no "builtin" option, CollisionDetection is libgit2's own
  # - SHA256 object ids stay off: they change the size of git_oid
  # - CMAKE_POLICY_VERSION_MINIMUM lets CMake 4 configure libgit2's older CMakeLists
  cmake -S "$SOURCE" -B "$build_dir" -G Ninja \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$sdk" \
    -DCMAKE_OSX_ARCHITECTURES="$arch" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET="$IOS_DEPLOYMENT_TARGET" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$OUTPUT/$target" \
    -DCMAKE_MAKE_PROGRAM="$(command -v ninja)" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TESTS=OFF \
    -DBUILD_CLI=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_FUZZERS=OFF \
    -DUSE_SSH=OFF \
    -DUSE_HTTPS=SecureTransport \
    -DUSE_SHA1=CollisionDetection \
    -DUSE_SHA256=CommonCrypto \
    -DUSE_BUNDLED_ZLIB=ON \
    -DUSE_ICONV=OFF \
    -DEXPERIMENTAL_SHA256=OFF \
    -DDEPRECATE_HARD=OFF

  cmake --build "$build_dir" --target install --parallel
}

# fails loudly here rather than later with a binding that does not link
verify() {
  local target="$1" arch="$3"
  local library="$OUTPUT/$target/lib/libgit2.a"

  [[ -f "$library" ]] || die "$target: $library was not built"
  [[ "$(lipo -archs "$library")" == "$arch" ]] || die "$target: $library is not built for $arch"
  if grep -qE '^#define GIT_EXPERIMENTAL_SHA256 1' "$OUTPUT/$target/include/git2/experimental.h"; then
    die "$target: built with SHA256 object ids"
  fi

  # nm sometimes prints nothing at all in a fresh toolchain - only an output without the symbol is an error
  local symbols
  symbols="$(nm -gU "$library" 2>/dev/null || true)"
  if [[ -n "$symbols" ]] && ! grep -q ' _git_clone$' <<< "$symbols"; then
    die "$target: $library does not contain libgit2"
  fi

  log "$target: $library ($(du -h "$library" | cut -f1))"
}

if [[ "${1:-}" == "--clean" ]]; then
  log "Removing the previous build"
  rm -rf "$ROOT"/build/libgit2-* "$OUTPUT"
fi

check_tools
download_source
for entry in "${TARGETS[@]}"; do
  IFS='|' read -r target sdk arch <<< "$entry"
  build "$target" "$sdk" "$arch"
done
for entry in "${TARGETS[@]}"; do
  IFS='|' read -r target sdk arch <<< "$entry"
  verify "$target" "$sdk" "$arch"
done
log "libgit2 $LIBGIT2_VERSION is ready in $OUTPUT"
