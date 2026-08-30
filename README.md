# Grok Desktop for Quest 3

Sideloadable **arm64-v8a** APK: a 2D floating panel. Kotlin hosts a WebView on `http://127.0.0.1:<port>/` and a `specialUse` foreground service starts standalone Node (`libnodewrap.so` → `libnode.so` → `questEntry.js`).

Package: `dev.grokdesktop.quest`. Default APK is `MAIN`+`LAUNCHER` only (never `category.VR`). The debug variant also merges `com.oculus.intent.category.2D` onto that same intent-filter.

This APK vendors **Termux nodejs-lts 24.18.0** (`node` renamed `libnode.so`): ET_DYN PIE with `/system/bin/linker64`. NDK r26c Node **v22.14.0** is still the primary recipe in `fetch-runtime.ps1` (attempted; host-tool flags blocked the link — see `SPIKE.md`).

## Build / sideload

Native binaries are **not** in git. Fetch them first (Docker Linux engine required):

```powershell
cd E:\Dev\GrokDesktopAndroid
.\scripts\fetch-runtime.ps1
.\scripts\sync-desktop.ps1
.\gradlew.bat :app:assembleDebug
.\scripts\adb-install.ps1
```

`sync-desktop.ps1` copies `server/` + `renderer/` from `E:\Dev\GrokDesktop` (override with `-DesktopRoot`), writes `SOURCE_REV`, and applies `overlay/patches`. Assemble fails if `httpApi.js` is missing from assets.

`adb-install.ps1` runs:

```
adb -d install -r app\build\outputs\apk\debug\app-debug.apk
adb -d shell am start -n dev.grokdesktop.quest/.MainActivity
```

and assembles the debug APK first if it is missing. It fails if `adb` or a USB device is missing. Updates are sideload-only — the app does not `git pull`.

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

## Panel

Grant `POST_NOTIFICATIONS` when prompted; the runtime FGS starts automatically. Mic (`RECORD_AUDIO`) is primed the same way and does **not** gate Node. Closing the panel does not stop the FGS.

Until Node answers `/api/health`, the WebView shows a local placeholder (Retry on failure). When the handshake port is healthy, the panel loads `http://127.0.0.1:<port>/`.

Image attach uses the existing composer picker (`#file-attach`, max 8 JPEG re-encode in JS). The WebView implements `onShowFileChooser` so that `<input type="file">` opens the system picker. Generated session images/videos load from `GET /api/sessions/:id/media/…`. `/export` opens the Android share sheet (blob `<a download>` is not used on Quest).

Dictation uses the existing `#btn-mic` (click to start/stop; does not auto-send). The WebView grants `RESOURCE_AUDIO_CAPTURE` only to the loopback origin after `RECORD_AUDIO`. If `getUserMedia` fails, native `AudioRecord` (16 kHz mono PCM16, ~100 ms frames) posts to `POST /api/stt/audio`.

Results: `$HOME/.grok-desktop/spike-results.json` with `$HOME` = `filesDir/home`. FGS doff: start the panel, take the headset off ≥ 60s, confirm the Node pid in `node.pid` is still alive. See `SPIKE.md`.
