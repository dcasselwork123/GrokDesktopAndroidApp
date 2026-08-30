#!/usr/bin/env bash
# Gated by GROK_BIONIC=1. Do not run unless musl grok cannot be used.
# Pin: xai-org/grok-build bc7f02eddd3d84085849dc19ed216f11c23b0571
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
GROK_BUILD_REV="${GROK_BUILD_REV:-bc7f02eddd3d84085849dc19ed216f11c23b0571}"
apt-get update -qq
apt-get install -y -qq curl ca-certificates git build-essential pkg-config >/dev/null
if ! command -v rustup >/dev/null 2>&1; then
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
fi
# shellcheck disable=SC1091
. "$HOME/.cargo/env"
rustup target add aarch64-linux-android
NDK=/opt/cache/android-ndk-r26c
export ANDROID_NDK_HOME="$NDK"
LINKER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android32-clang"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LINKER"
export TARGET_CC="$LINKER"
export RUSTFLAGS="-C link-arg=-pie -C link-arg=-Wl,-z,max-page-size=16384"
SRC=/opt/cache/grok-build
if [ ! -d "$SRC/.git" ]; then
  git clone https://github.com/xai-org/grok-build.git "$SRC"
fi
cd "$SRC"
git fetch --depth 1 origin "$GROK_BUILD_REV" || git fetch origin
git checkout "$GROK_BUILD_REV"
# Official installs ship this crate as `grok`.
cargo build --release --target aarch64-linux-android -p xai-grok-pager-bin
BIN="$SRC/target/aarch64-linux-android/release/xai-grok-pager"
if [ ! -f "$BIN" ]; then
  BIN="$(find "$SRC/target/aarch64-linux-android/release" -maxdepth 1 -type f -executable | head -n1)"
fi
cp -f "$BIN" /work/app/src/main/jniLibs/arm64-v8a/libgrok.so
echo "BIONIC $GROK_BUILD_REV" > /work/app/src/main/jniLibs/arm64-v8a/libgrok.origin.txt
