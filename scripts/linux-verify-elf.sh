#!/usr/bin/env bash
# Host-side ELF checks for vendored PIE executables. JNI-only libs fail hard.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq binutils file >/dev/null
JNI=/work/app/src/main/jniLibs/arm64-v8a
fail=0
for so in libnode.so libnodewrap.so libgrok.so; do
  p="$JNI/$so"
  echo "======== $so ========"
  if [ ! -f "$p" ]; then
    echo "MISSING $p"
    fail=1
    continue
  fi
  file "$p" || true
  readelf -l "$p" || true
  interp=$(readelf -l "$p" | grep -F "Requesting program interpreter:" || true)
  static=$(file "$p" | grep -c "statically linked" || true)
  if [ "$so" = "libgrok.so" ]; then
    # Official musl grok is a static ET_EXEC (no bionic interpreter). That is
    # not a JNI lib. Bionic grok-build is ET_DYN + linker64.
    if echo "$interp" | grep -q "/system/bin/linker64"; then
      echo "PASS: $so INTERP /system/bin/linker64 (bionic PIE)"
    elif [ "$static" != "0" ]; then
      echo "PASS: $so statically linked musl ET_EXEC (no bionic interpreter; expected for official linux-aarch64)"
    else
      echo "FAIL: $so is neither musl-static nor bionic PIE with linker64"
      fail=1
    fi
  elif echo "$interp" | grep -q "/system/bin/linker64"; then
    echo "PASS: $so INTERP /system/bin/linker64"
  else
    echo "FAIL: $so has no program interpreter /system/bin/linker64 (JNI-only shared object is the wrong artifact)"
    fail=1
  fi
  if readelf -l "$p" | grep -q "DYN"; then
    echo "NOTE: Type DYN (expected for PIE executable / ET_DYN)"
  fi
  align=$(readelf -l "$p" | awk '/Align/ {a=$NF} /LOAD/ && $NF ~ /^0x/ {a=$NF} END {print a}')
  echo "LOAD Align (recorded): $align"
  case "$align" in
    0x4000|16384) echo "NOTE: 16 KiB LOAD alignment present" ;;
    *) echo "NOTE: LOAD alignment is $align (16 KiB / 0x4000 is a future API 35 note, not a v1 blocker)" ;;
  esac
done
if [ -f "$JNI/libnode.origin.txt" ]; then
  echo "libnode origin: $(cat "$JNI/libnode.origin.txt")"
fi
if [ -f "$JNI/libgrok.origin.txt" ]; then
  echo "libgrok origin: $(cat "$JNI/libgrok.origin.txt")"
fi
exit $fail
