# Grok Desktop for Quest 3 (PR 1 spike)

This APK vendors **Termux nodejs-lts 24.18.0** (`node` renamed `libnode.so`): ET_DYN PIE with `/system/bin/linker64`. NDK r26c Node **v22.14.0** is still the primary recipe in `fetch-runtime.ps1` (attempted; host-tool flags blocked the link — see `SPIKE.md`).

Package: `dev.grokdesktop.quest`. ABI: `arm64-v8a` only. No chat UI.

## Build

Native binaries are **not** in git. Fetch them first (Docker Linux engine required):

```powershell
cd E:\Dev\GrokDesktopAndroid
.\scripts\fetch-runtime.ps1
.\gradlew.bat :app:assembleDebug
adb -d install -r app\build\outputs\apk\debug\app-debug.apk
adb -d shell am start -n dev.grokdesktop.quest/.MainActivity
```

`fetch-runtime.ps1` (primary):

- NDK **r26c**, API **32**, `./android-configure "$NDK_PATH" 32 arm64`
- Node **v22.14.0** `out/Release/node` → `app/src/main/jniLibs/arm64-v8a/libnode.so`
- `libnodewrap.so` (`setsid` + `execv`) with `-fPIE -pie -Wl,-z,max-page-size=16384`
- Official `linux-aarch64` grok → `libgrok.so`
- Fails if `libnode.so` has no program interpreter (`/system/bin/linker64`) — JNI nodejs-mobile is not a fallback
- Fails if `libgrok.so` is missing

If NDK Node fails, the script unpacks Termux aarch64 `.deb`s from `vendor/termux-node/` (gitignored). Packages used for this APK (https://packages.termux.dev/apt/termux-main/):

- `pool/main/n/nodejs-lts/nodejs-lts_24.18.0-1_aarch64.deb`
- `pool/main/libc/libc++/libc++_29_aarch64.deb`
- `pool/main/libi/libicu/libicu_78.3_aarch64.deb`
- `pool/main/o/openssl/openssl_1:3.6.3_aarch64.deb`
- `pool/main/z/zlib/zlib_1.3.2_aarch64.deb`
- `pool/main/c/c-ares/c-ares_1.34.8_aarch64.deb`
- `pool/main/libs/libsqlite/libsqlite_3.53.4_aarch64.deb`

Bionic grok-build (same `libgrok.so` slot) is gated: `$env:GROK_BIONIC=1`. Do not run it unless musl DNS fails on device.

## On-device spike

Dashboard buttons: Start runtime, Stop runtime, Re-run W^X, Copy results.

Results: `$HOME/.grok-desktop/spike-results.json` with `$HOME` = `filesDir/home`. Node DNS/TLS are `checks.nodeDns` / `checks.nodeTls` — not the grok musl getaddrinfo/rustls rows.

FGS doff: start runtime, take the headset off ≥ 60s, confirm the Node pid in the dashboard / `node.pid` is still alive. See `SPIKE.md`.
