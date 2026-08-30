#!/usr/bin/env bash
# Flatten versioned Termux .so names so AGP packages them (*.so only).
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq patchelf binutils >/dev/null
JNI=/work/app/src/main/jniLibs/arm64-v8a

flatten() {
  local needed="$1"
  local dest="$2"
  local src
  src="$(ls -1 "${JNI}/${needed}"* 2>/dev/null | head -n1 || true)"
  echo "flatten ${needed} -> ${dest}  src=${src}"
  if [[ -z "${src}" || ! -f "${src}" ]]; then
    echo "missing ${needed}"
    return 0
  fi
  if [[ "$(basename "${src}")" != "${dest}" ]]; then
    cp -f "${src}" "${JNI}/${dest}"
  fi
  patchelf --set-rpath '$ORIGIN' "${JNI}/${dest}" || true
  local other
  for other in "${JNI}"/libnode.so "${JNI}"/*.so*; do
    [[ -f "${other}" ]] || continue
    patchelf --replace-needed "${needed}" "${dest}" "${other}" 2>/dev/null || true
    patchelf --set-rpath '$ORIGIN' "${other}" 2>/dev/null || true
  done
}

flatten libz.so.1 libz.so
flatten libssl.so.3 libssl.so
flatten libcrypto.so.3 libcrypto.so
flatten libicui18n.so.78 libicui18n.so
flatten libicuuc.so.78 libicuuc.so
flatten libicudata.so.78 libicudata.so
flatten libsqlite3.so libsqlite3.so
flatten libcares.so libcares.so

echo "=== NEEDED libnode.so after flatten ==="
readelf -d "${JNI}/libnode.so" | grep NEEDED
