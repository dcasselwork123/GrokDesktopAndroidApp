# PR 1 feasibility spike

Honest pass/fail from DESIGN.md. JNI nodejs-mobile `libnode.so` (no program interpreter) is an automatic **fail**, not a fallback.

Go/no-go: standalone Node **PIE executable** in `nativeLibraryDir` can `http.createServer` **and** `spawn(GROK_BIN)`. A musl grok that prints `--version` but cannot resolve `api.x.ai` is **GO with fallback B (bionic grok-build in the same APK slot)**.

## Artifacts (this run)

| Slot | Kind | Source (this run) |
|------|------|-------------------|
| `libnode.so` | ET_DYN PIE executable with `/system/bin/linker64` | **TERMUX** `nodejs-lts_24.18.0-1_aarch64.deb` `node` renamed. NDK r26c Node **v22.14.0** recipe is still the primary path in `scripts/fetch-runtime.ps1` (attempted; host-tool flags blocked the link — see below). **Not JNI.** |
| `libnodewrap.so` | PIE `setsid` + `execv` | NDK r26c, API 32, `-fPIE -pie -Wl,-z,max-page-size=16384` from `native/nodewrap.c` |
| `libc++_shared.so` | NDK/Termux STL | Termux `libc++_29` (rpath `$ORIGIN`) next to Node |
| `libgrok.so` | grok CLI | Official **musl static** `grok-1.0.13-linux-aarch64` from `https://x.ai/cli/stable` (135 641 288 bytes). ET_EXEC, statically linked, **no** bionic interpreter — expected for musl, not a JNI lib. Bionic pin: `xai-org/grok-build` `bc7f02eddd3d84085849dc19ed216f11c23b0571` via `$env:GROK_BIONIC=1`. |

Do **not** commit the `.so` files. Run `.\scripts\fetch-runtime.ps1` before assemble.

### NDK Node 22.14.0 attempt (primary recipe)

Executed, not sourced:

```text
./android-configure "$NDK_PATH" 32 arm64
```

NDK **r26c** (Pkg.Revision 26.2.11394342) in Docker `linux/amd64`. `android_configure.py` patched to append `--with-intl=small-icu`.

Host-side compile failures (x86_64 gcc building Node for Android arm64):

1. `v8_libbase` host `stack_trace_posix.cc`: `backtrace` / `backtrace_symbols` undeclared — NDK clang was used for `obj.host`. Workaround in script: `make CC.host=gcc CXX.host=g++`.
2. `node_js2c.host.mk`: `g++: error: unrecognized command-line option '-mbranch-protection=standard'` ([nodejs/node#52512](https://github.com/nodejs/node/issues/52512)). Workaround in script: `GYP_DEFINES host_arch=x64` plus `sed` strip of that flag from `*.host.mk`.

Until those host-tool flags are fully green, this APK vendors the Termux **PIE** `node` (interpreter `/system/bin/linker64`). Re-run `fetch-runtime.ps1` to retry NDK; a successful `out/Release/node` overwrites the same `libnode.so` slot.

## Matrix

| Check | Pass | Else | Result |
|-------|------|------|--------|
| `readelf -l libnode.so` is ET_DYN **PIE executable** with `/system/bin/linker64` interpreter | GO | Wrong artifact (JNI lib). NO-GO for that file. | **PASS (host).** `file`: `ELF 64-bit LSB shared object, ARM aarch64, dynamically linked, interpreter /system/bin/linker64`. Type `DYN`. Origin **TERMUX** `nodejs-lts 24.18.0`. |
| `exec` `libnode.so --version` from `nativeLibraryDir` | GO path | NO-GO — port blocked | **PENDING (needs Quest 3 + adb)** |
| Control: copy same binary to `filesDir` and `exec` | **must** fail `EACCES` | We do not understand W^X | **PENDING (needs Quest 3 + adb)** — APK button “Re-run W^X test” writes `wx-results.json` |
| Node `http.createServer` bind `127.0.0.1:0` | required | NO-GO | **PENDING (needs Quest 3 + adb)** — spike JS retries 3847,+1,+2,0 |
| Node `spawn(process.env.GROK_BIN, ["--version"])` exit 0 | required | NO-GO | **PENDING (needs Quest 3 + adb)** |
| `libgrok.so --version` from `nativeLibraryDir` | required | try bionic rebuild (B) | **PENDING (needs Quest 3 + adb)** — host `file`: musl static ET_EXEC aarch64 |
| `ls -l /bin/sh /system/bin/sh`; `echo $SHELL` | note | set `SHELL=/system/bin/sh` | **PENDING (needs Quest 3 + adb)** — env already `SHELL=/system/bin/sh` |
| `/etc/resolv.conf` contains a nameserver | note | expect musl DNS fail | **PENDING (needs Quest 3 + adb)** |
| grok `getaddrinfo api.x.ai` (or `grok --version` then a tiny net probe) | primary musl OK | fallback B **bionic grok-build** (same APK `libgrok.so`). No `/sdcard` 16-byte patch | **PENDING (needs Quest 3 + adb)** |
| TLS `https://api.x.ai` from grok (CLI bundles rustls roots — verify) | required for login/chat | report CA; do not assume Android `/apex/.../cacerts` | **PENDING (needs Quest 3 + adb)** — Node also probes `https://api.x.ai` in spike JS |
| `readelf -l` 16 KiB LOAD alignment | note (future API 35) | not a v1 blocker | **NOTE (host).** `libnode.so` / `libnodewrap.so` LOAD `Align 0x4000` (16 KiB). `libgrok.so` musl LOAD `Align 0x10000` (64 KiB). Not a v1 blocker. |
| FGS doff: Node pid alive after 60s headset-off | required for v1 lifecycle | fix FGS type/wake lock before chat | **PENDING (needs Quest 3 + adb)** |
| `grok -p hi` | **not PR 1** | needs `auth.json` (PR 4+) | skipped |

## Device commands

After `fetch-runtime` + `assembleDebug`:

```powershell
adb -d install -r app\build\outputs\apk\debug\app-debug.apk
adb -d shell am start -n dev.grokdesktop.quest/.MainActivity
```

In the spike dashboard: grant `POST_NOTIFICATIONS`, tap **Start runtime**. Copy results (clipboard) or pull:

```powershell
adb -d shell run-as dev.grokdesktop.quest cat files/home/.grok-desktop/spike-results.json
adb -d shell run-as dev.grokdesktop.quest cat files/home/.grok-desktop/wx-results.json
adb -d shell run-as dev.grokdesktop.quest cat files/home/.grok-desktop/runtime.json
adb -d shell run-as dev.grokdesktop.quest cat files/home/.grok-desktop/node.pid
```

### FGS doff (≥ 60s)

1. Start runtime. Confirm notification `Grok Desktop` / `Runtime on 127.0.0.1:<port>` (channel `grok-runtime`, ongoing, not silent).
2. Note pid from the dashboard / `node.pid`.
3. Take the Quest 3 headset off and wait **≥ 60 seconds**.
4. Put the headset back on. Pid must still exist:

```powershell
adb -d shell run-as dev.grokdesktop.quest cat files/home/.grok-desktop/node.pid
adb -d shell pidof dev.grokdesktop.quest:runtime
```

The APK keeps `NodeRuntimeService` as `foregroundServiceType="specialUse"` in process `:runtime` for this test; it cannot be auto-passed without a device.

## Host-side ELF log

Docker `linux/amd64` `readelf -l` / `file` (script: `scripts/linux-verify-elf.sh`):

```
libnode.so:  ELF 64-bit LSB shared object, ARM aarch64, dynamically linked,
             interpreter /system/bin/linker64, stripped
             Type: DYN (Shared object file)
             INTERP: /system/bin/linker64   PASS
             LOAD Align 0x4000 (16 KiB)
             origin: TERMUX nodejs-lts 24.18.0-1 aarch64 (node renamed)
             NOT a JNI lib (has program interpreter)

libnodewrap.so: ELF 64-bit LSB pie executable, ARM aarch64, dynamically linked,
             interpreter /system/bin/linker64, stripped
             Type: DYN (Position-Independent Executable file)
             INTERP: /system/bin/linker64   PASS
             LOAD Align 0x4000 (16 KiB)
             origin: NDK r26c aarch64-linux-android32-clang -fPIE -pie -Wl,-z,max-page-size=16384

libgrok.so:  ELF 64-bit LSB executable, ARM aarch64, statically linked, stripped
             Type: EXEC (Executable file)
             no bionic interpreter — PASS as official musl linux-aarch64
             LOAD Align 0x10000
             origin: https://x.ai/cli/grok-1.0.13-linux-aarch64  (135641288 bytes)
```

## Notes

- No `/sdcard` resolv patch (targetSdk 34 scoped storage; path is not 16 bytes).
- `libbusybox.so` is optional and not required for PR 1 GO. Default `SHELL=/system/bin/sh`.
- Kotlin always starts **`libnodewrap.so`**, never `ProcessBuilder` on Node directly (no `setsid` → `kill(-pid)` misses grok).
- `grok -p hi` is not this PR.
- Debug APK (this machine): `app\build\outputs\apk\debug\app-debug.apk` (gitignored).
