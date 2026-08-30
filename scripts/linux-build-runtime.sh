#!/usr/bin/env bash
# Linux-side of fetch-runtime.ps1. Runs inside Docker (linux/amd64).
# Primary: NDK r26c Node 22.14.0 PIE executable + libnodewrap.so.
set -euo pipefail

CACHE_DIR="${CACHE_DIR:-/opt/cache}"
WORK_DIR="${WORK_DIR:-/work}"
NDK_VER="${NDK_VER:-r26c}"
NODE_VER="${NODE_VER:-v22.14.0}"
JOBS="${JOBS:-6}"
JNI_DIR="${WORK_DIR}/app/src/main/jniLibs/arm64-v8a"
NDK="${CACHE_DIR}/android-ndk-${NDK_VER}"
NODE_SRC="${CACHE_DIR}/node-${NODE_VER}"
export CACHE_DIR WORK_DIR NODE_SRC NDK JOBS
SKIP_NODE_BUILD="${SKIP_NODE_BUILD:-0}"
TERMUX_DIR="${TERMUX_DIR:-${WORK_DIR}/vendor/termux-node}"

mkdir -p "${CACHE_DIR}" "${JNI_DIR}"

log() { printf '%s\n' "$*"; }

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing command: $1" >&2
    exit 1
  }
}

install_packages() {
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq \
    python3 python3-setuptools python3-pip python3-distutils \
    gcc g++ make patch git pkg-config xz-utils \
    curl ca-certificates unzip binutils file \
    patchelf dpkg-dev >/dev/null
}

ensure_ndk() {
  if [[ -f "${NDK}/source.properties" ]]; then
    log "NDK ${NDK_VER} cached at ${NDK}"
    return
  fi
  log "Downloading Android NDK ${NDK_VER} (linux)…"
  curl -L --fail --retry 3 -o "${CACHE_DIR}/ndk.zip" \
    "https://dl.google.com/android/repository/android-ndk-${NDK_VER}-linux.zip"
  unzip -q "${CACHE_DIR}/ndk.zip" -d "${CACHE_DIR}"
  rm -f "${CACHE_DIR}/ndk.zip"
}

ensure_node_src() {
  if [[ -f "${NODE_SRC}/android-configure" ]]; then
    log "Node ${NODE_VER} source cached"
    return
  fi
  log "Cloning node ${NODE_VER}…"
  git clone --depth 1 --branch "${NODE_VER}" https://github.com/nodejs/node.git "${NODE_SRC}"
}

patch_android_configure() {
  python3 - <<'PY'
from pathlib import Path
import os
src = os.environ.get("NODE_SRC", "/opt/cache/node-v22.14.0")
p = Path(src) / "android_configure.py"
text = p.read_text()
old = 'os.system("./configure --dest-cpu=" + DEST_CPU + " --dest-os=android --openssl-no-asm --cross-compiling")'
new = 'os.system("./configure --dest-cpu=" + DEST_CPU + " --dest-os=android --openssl-no-asm --cross-compiling --with-intl=small-icu")'
if old in text:
    text = text.replace(old, new, 1)
    print("patched android_configure.py: append --with-intl=small-icu")
elif "--with-intl=small-icu" in text:
    print("android_configure.py already patched with --with-intl=small-icu")
else:
    raise SystemExit("configure line not found in android_configure.py — refuse to guess")
# Docker host is linux/amd64. Without host_arch=x64, GYP applies ARM64
# -mbranch-protection to host js2c and gcc rejects the flag.
host = 'GYP_DEFINES += " host_os=" + host_os + " OS=android"'
host_new = host + '\nGYP_DEFINES += " host_arch=x64"'
if "host_arch=x64" in text:
    print("android_configure.py already has host_arch=x64")
elif host in text:
    text = text.replace(host, host_new, 1)
    print("patched android_configure.py: host_arch=x64")
else:
    raise SystemExit("GYP_DEFINES host_os line not found")
p.write_text(text)
PY
}

build_node_ndk() {
  if [[ "${SKIP_NODE_BUILD}" == "1" ]]; then
    log "SKIP_NODE_BUILD=1"
    return
  fi
  if [[ -x "${NODE_SRC}/out/Release/node" ]]; then
    log "Reusing ${NODE_SRC}/out/Release/node"
  else
    ensure_node_src
    patch_android_configure
    cd "${NODE_SRC}"
    log "=== ./android-configure \"${NDK}\" 32 arm64 ==="
    ./android-configure "${NDK}" 32 arm64
    # x86_64 gcc rejects ARM64 PAC flags leaked into host js2c (nodejs/node#52512).
    find "${NODE_SRC}/out" -name '*.host.mk' -exec sed -i 's/-mbranch-protection=standard//g' {} +
    rm -rf "${NODE_SRC}/out/Release/obj.host"
    # NDK clang is CC/CXX; host tools need gcc (Android libc has no backtrace).
    log "=== make -j${JOBS} CC.host=gcc CXX.host=g++ ==="
    make -j"${JOBS}" CC.host=gcc CXX.host=g++ AR.host=ar LINK.host=g++
  fi
  if [[ ! -x "${NODE_SRC}/out/Release/node" ]]; then
    echo "NDK Node build did not produce out/Release/node" >&2
    return 1
  fi
  cp -f "${NODE_SRC}/out/Release/node" "${JNI_DIR}/libnode.so"
  echo "NDK" > "${JNI_DIR}/libnode.origin.txt"
  local libcxx
  libcxx="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
  if [[ ! -f "${libcxx}" ]]; then
    libcxx="$(find "${NDK}" -name 'libc++_shared.so' -path '*aarch64-linux-android*' | head -n1)"
  fi
  if [[ -n "${libcxx}" && -f "${libcxx}" ]]; then
    cp -f "${libcxx}" "${JNI_DIR}/libc++_shared.so"
    log "copied libc++_shared.so from ${libcxx}"
  else
    echo "WARNING: libc++_shared.so not found in NDK" >&2
  fi
  log "installed NDK out/Release/node -> libnode.so"
}

unpack_termux() {
  if [[ ! -d "${TERMUX_DIR}" ]]; then
    return 1
  fi
  local debs
  debs="$(find "${TERMUX_DIR}" -name '*.deb' | wc -l | tr -d ' ')"
  if [[ "${debs}" == "0" ]]; then
    return 1
  fi
  log "Termux shortcut: unpacking ${debs} .deb(s) from ${TERMUX_DIR}"
  local root="${CACHE_DIR}/termux-root"
  rm -rf "${root}"
  mkdir -p "${root}"
  local deb
  for deb in "${TERMUX_DIR}"/*.deb; do
    [[ -f "${deb}" ]] || continue
    dpkg-deb -x "${deb}" "${root}"
  done
  local nodebin
  nodebin="$(find "${root}" -type f -name node | head -n1)"
  if [[ -z "${nodebin}" ]]; then
    echo "Termux unpack: no node binary" >&2
    return 1
  fi
  mkdir -p "${JNI_DIR}"
  cp -f "${nodebin}" "${JNI_DIR}/libnode.so"
  echo "TERMUX" > "${JNI_DIR}/libnode.origin.txt"
  find "${root}" -name '*.so*' -type f | while read -r so; do
    cp -f "${so}" "${JNI_DIR}/$(basename "${so}")"
  done
  need_cmd patchelf
  local f
  for f in "${JNI_DIR}"/libnode.so "${JNI_DIR}"/*.so*; do
    [[ -f "${f}" ]] || continue
    patchelf --set-rpath '$ORIGIN' "${f}" 2>/dev/null || true
  done
  # AGP only packages files named *.so (not libssl.so.3). Flatten SONAMEs.
  flatten_soname() {
    local needed="$1"
    local dest="$2"
    local src
    src="$(ls -1 "${JNI_DIR}/${needed}"* 2>/dev/null | head -n1 || true)"
    if [[ -z "${src}" || ! -f "${src}" ]]; then
      echo "WARNING: no file for ${needed}" >&2
      return
    fi
    if [[ "$(basename "${src}")" != "${dest}" ]]; then
      cp -f "${src}" "${JNI_DIR}/${dest}"
    fi
    patchelf --set-rpath '$ORIGIN' "${JNI_DIR}/${dest}" 2>/dev/null || true
    patchelf --replace-needed "${needed}" "${dest}" "${JNI_DIR}/libnode.so" 2>/dev/null || true
    local other
    for other in "${JNI_DIR}"/*.so*; do
      patchelf --replace-needed "${needed}" "${dest}" "${other}" 2>/dev/null || true
    done
  }
  flatten_soname libz.so.1 libz.so
  flatten_soname libssl.so.3 libssl.so
  flatten_soname libcrypto.so.3 libcrypto.so
  flatten_soname libicui18n.so.78 libicui18n.so
  flatten_soname libicuuc.so.78 libicuuc.so
  flatten_soname libicudata.so.78 libicudata.so
  flatten_soname libsqlite3.so libsqlite3.so
  flatten_soname libcares.so libcares.so
  log "Termux node -> libnode.so (developer shortcut)"
}

build_nodewrap() {
  local clang="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android32-clang"
  if [[ ! -x "${clang}" ]]; then
    echo "NDK clang not found: ${clang}" >&2
    exit 1
  fi
  "${clang}" -fPIE -pie -Wl,-z,max-page-size=16384 -O2 -s \
    -o "${JNI_DIR}/libnodewrap.so" \
    "${WORK_DIR}/native/nodewrap.c"
  log "built libnodewrap.so"
}

# --- main ---
install_packages
ensure_ndk
NODE_FAIL=0
if ! build_node_ndk; then
  NODE_FAIL=1
  log "NDK Node build failed; trying Termux shortcut if present"
  if ! unpack_termux; then
    echo "Neither NDK Node nor Termux prebuilt produced libnode.so" >&2
  fi
fi
build_nodewrap
if [[ ! -f "${JNI_DIR}/libnode.so" ]]; then
  echo "FATAL: libnode.so was not produced" >&2
  exit 1
fi
log "linux-build-runtime.sh done"
exit 0
