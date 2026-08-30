# Grok Desktop for Quest 3 (PR 1 spike)

Sideload APK: standalone Node 22 PIE executable + grok CLI in `nativeLibraryDir`.
This branch is the **feasibility spike** — no chat UI.

Package: `dev.grokdesktop.quest`. ABI: `arm64-v8a` only.

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

- NDK **r26c**, API **32**, `./android-configure "$NDK_PATH" 32 arm64` executed (not sourced)
- Node **v22.14.0** `out/Release/node` → `app/src/main/jniLibs/arm64-v8a/libnode.so`
- `libnodewrap.so` (`setsid` + `execv`) with `-fPIE -pie -Wl,-z,max-page-size=16384`
- Official `linux-aarch64` grok → `libgrok.so`
- Fails if `libnode.so` has no program interpreter (`/system/bin/linker64`) — JNI nodejs-mobile is not a fallback

Optional: drop Termux aarch64 `.deb`s in `vendor/termux-node/` as a developer shortcut if the NDK build fails.

Bionic grok-build (same `libgrok.so` slot) is gated: `$env:GROK_BIONIC=1`. Do not run it unless musl DNS fails on device.

## On-device spike

Dashboard buttons: Start runtime, Stop runtime, Re-run W^X, Copy results.

Results: `$HOME/.grok-desktop/spike-results.json` with `$HOME` = `filesDir/home`.

FGS doff: start runtime, take the headset off ≥ 60s, confirm the Node pid in the dashboard / `node.pid` is still alive. See `SPIKE.md`.
