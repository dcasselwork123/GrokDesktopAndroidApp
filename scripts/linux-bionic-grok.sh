#!/usr/bin/env bash
# Gated by GROK_BIONIC=1. Do not run unless musl grok cannot be used.
# Pin: xai-org/grok-build bc7f02eddd3d84085849dc19ed216f11c23b0571
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
GROK_BUILD_REV="${GROK_BUILD_REV:-bc7f02eddd3d84085849dc19ed216f11c23b0571}"
CACHE_DIR="${CACHE_DIR:-/opt/cache}"
apt-get update -qq
apt-get install -y -qq curl ca-certificates git build-essential pkg-config unzip \
  protobuf-compiler cmake ninja-build python3 >/dev/null
export PATH="${CARGO_HOME:-/usr/local/cargo}/bin:${HOME}/.cargo/bin:${PATH}"
if ! command -v rustup >/dev/null 2>&1; then
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
fi
if [ -f "$HOME/.cargo/env" ]; then
  # shellcheck disable=SC1091
  . "$HOME/.cargo/env"
fi
NDK="${CACHE_DIR}/android-ndk-r26c"
if [ ! -f "${NDK}/source.properties" ]; then
  echo "Downloading Android NDK r26c (linux)…"
  curl -L --fail --retry 3 -o "${CACHE_DIR}/ndk.zip" \
    "https://dl.google.com/android/repository/android-ndk-r26c-linux.zip"
  unzip -q "${CACHE_DIR}/ndk.zip" -d "${CACHE_DIR}"
  rm -f "${CACHE_DIR}/ndk.zip"
fi
export ANDROID_NDK_HOME="$NDK"
LINKER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android32-clang"
if [ ! -x "$LINKER" ]; then
  echo "FATAL: NDK clang missing at $LINKER" >&2
  exit 1
fi
BINDIR="$(dirname "$LINKER")"
export PATH="${BINDIR}:${PATH}"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LINKER"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="${BINDIR}/llvm-ar"
export CC_aarch64_linux_android="$LINKER"
export CXX_aarch64_linux_android="${BINDIR}/aarch64-linux-android32-clang++"
export AR_aarch64_linux_android="${BINDIR}/llvm-ar"
export TARGET_CC="$LINKER"
export TARGET_AR="${BINDIR}/llvm-ar"
# cc-rs looks for unversioned triples (not android32).
ln -sfn llvm-ar "${BINDIR}/aarch64-linux-android-ar"
ln -sfn llvm-ranlib "${BINDIR}/aarch64-linux-android-ranlib"
export RUSTFLAGS="-C link-arg=-pie -C link-arg=-Wl,-z,max-page-size=16384"
SRC=/opt/cache/grok-build
if [ ! -d "$SRC/.git" ]; then
  git clone https://github.com/xai-org/grok-build.git "$SRC"
fi
cd "$SRC"
git fetch --depth 1 origin "$GROK_BUILD_REV" || git fetch origin
git checkout "$GROK_BUILD_REV"
# rust-toolchain.toml may pin a different rustc than the image default.
rustup show
rustup target add aarch64-linux-android
if ! rustup target list --installed | grep -q 'aarch64-linux-android'; then
  echo "FATAL: aarch64-linux-android rust-std not installed" >&2
  rustup target list --installed >&2
  exit 1
fi
# Official installs ship this crate as `grok`.
export CARGO_TARGET_DIR="${CACHE_DIR}/grok-build-target"
cargo build --release --target aarch64-linux-android -p xai-grok-pager-bin
BIN="${CARGO_TARGET_DIR}/aarch64-linux-android/release/xai-grok-pager"
if [ ! -f "$BIN" ]; then
  BIN="$(find "$SRC/target/aarch64-linux-android/release" -maxdepth 1 -type f -executable | head -n1)"
fi
cp -f "$BIN" /work/app/src/main/jniLibs/arm64-v8a/libgrok.so
echo "BIONIC $GROK_BUILD_REV" > /work/app/src/main/jniLibs/arm64-v8a/libgrok.origin.txt
