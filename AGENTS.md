# Grok Desktop for Quest 3 — project guide

## READ THIS FIRST (fresh sessions)

If this session’s job is this Android app (`E:\Dev\GrokDesktopAndroid`), **read this file before changing code, re-scaffolding Gradle, or re-running `/design`.**

Do not start a new Android project. Do not rewrite `renderer/` in Compose. Do not treat nodejs-mobile-in-process as a Node host. Prefer small, targeted edits to the existing Kotlin shell + overlay + vendored binaries.

Desktop UI/server source of truth is **`E:\Dev\GrokDesktop`**. Copy it with `scripts/sync-desktop.ps1` (PR 3+). Do not edit that repo from this workspace unless the user asks.

### Source of truth (git)

| | |
|--|--|
| **Main / canonical repo** | https://github.com/dcasselwork123/GrokDesktopAndroidApp |
| **Local workspace** | `E:\Dev\GrokDesktopAndroid` (tracks `origin/main` on that repo) |
| **Design spec** | `DESIGN.md` (implement it; do not re-review it unless asked) |
| **Spike results** | `SPIKE.md` |

**Push policy:** This repo has **no GitHub PR workflow.** Implement on `main`, commit, and `git push origin main`. Do not open pull requests. Do not leave `execute-plan/*` (or other) branches on GitHub — those yellow “Compare & pull request” banners are leftover stacks, not something the user should click.

| Do this | Do not |
|---------|--------|
| Commit on `main` and push `origin/main` after a change that is done | Create GitHub PRs, Graphite stacks, or `execute-plan/<id>-pr-N-…` branches as the delivery |
| If `/execute-plan` was used: fast-forward `main` to the finished work, push `origin/main`, **delete** the stack branches locally and on `origin` | Leave compare-URL branches sitting on the repo |
| Stop and say so if push is blocked | `--force` / history rewrite |

Never force-push. Never commit secrets (`auth.json`, API keys, `config.json`). Never commit `*.so` / APKs (`fetch-runtime.ps1` produces them).

---

## Token budget — cut review loops

PR 1 burned a long implementer + reviewer loop (NDK compile, host ELF, three review rounds including nits). **That must not happen again.**

| Rule | Do this |
|------|---------|
| Design | `DESIGN.md` is locked enough to implement. **Do not re-run `/design` or a design-doc review** unless the user asks. |
| Execute | One implementer pass, **one reviewer pass**. |
| Reviewer | File **bugs only** (correctness, security, breakage). No nits. No style. No comment-wording. No speculative lifecycle essays. If it matches the PR description and compiles, approve. |
| Fix loop | Re-open **only** for bugs the first review missed or a fix that broke something. Stop after that. |
| Spike | Do **not** expand `SPIKE.md`. Device rows stay pending until a Quest is on `adb`. Do not rebuild Node from NDK unless binaries are missing and the user wants the NDK path. |
| Scope | Implement **one PR** from `DESIGN.md` unless the user names more. Do not “also polish” later PRs. |

Prefer implementing **on `main`** (one pass + bugs-only review), then push `origin/main`. If `/execute-plan` is used anyway, pass the instructions below **and** after it finishes: fast-forward `main`, push, delete the stack branches. `/execute-plan` otherwise loops until every nit is closed and leaves GitHub branch banners:

```text
--instructions "Reviewer: bugs only. One review round. No nits, no suggestions, no comment-wording. Approve if the PR description is met and it compiles."
```

---

## What this app is

Sideloadable **arm64-v8a** APK for Meta Quest 3: a 2D floating panel. Kotlin hosts a WebView on `http://127.0.0.1:<port>/`, starts a standalone Node **executable** (`libnode.so` via `libnodewrap.so`) that runs `questEntry.js` → `httpApi.js`, which `spawn`s bundled `libgrok.so`.

v1 is **not** Unity/OpenXR, not a Compose chat rewrite, not a PC sidecar, not Tailscale/phone remote.

Package: `dev.grokdesktop.quest`. `minSdk 32`, `targetSdk 34`.

---

## Current status

| PR | Status |
|----|--------|
| **1** Feasibility spike (Node exec, grok exec, W^X, FGS, `SPIKE.md`) | **Done** on `main`. Host ELF PASS. Device rows need Quest 3 + `adb`. |
| **2** 2D panel + specialUse FGS + WebView shell | **Done** on `main`. |
| **3** Vendor JS + `questEntry.js` loopback + workspace cwd | **Done** on `main`. |
| **4** Device-code login + account bubble | **Done** on `main`. Fixture is redacted sample until a Quest is on `adb`. |
| **5** Chat SSE + sessions + ACP + toybox shell checklist | **Done** on `main`. On-device `uname; ls; wget` row is **PENDING** until a Quest is on `adb`. |
| 6–10 | Not started. Spec is `DESIGN.md` § PR Plan. |

**Node artifact in this APK:** Termux **nodejs-lts 24.18.0** PIE + `/system/bin/linker64` (not JNI). NDK r26c Node v22.14.0 remains the documented primary recipe; host-tool flags blocked the link (see `SPIKE.md`). Node ≥ 21 is required (global `WebSocket`).

**Grok artifact:** official musl `linux-aarch64` as `libgrok.so`. If on-device DNS to `api.x.ai` fails, same slot via `$env:GROK_BIONIC=1` — no `/sdcard` resolv patch.

---

## Hard constraints (do not “fix”)

- **Standalone Node PIE in `nativeLibraryDir` is the only host.** JNI nodejs-mobile cannot `child_process.spawn`. If exec fails, the port is blocked — do not pivot to xAI HTTP chat or a Kotlin `httpApi`.
- Start **`libnodewrap.so`** (`setsid` + `execv`), never `ProcessBuilder` Node directly. Grok spawn stays **non-detached**. Stop with `Os.kill(-nodePid)` then SIGKILL after 3s.
- Exec from `nativeLibraryDir` only (W^X). Do not exec from `filesDir`.
- Bind **127.0.0.1 only**. Skip `createServer` startup `rebind()` (Tailscale/LAN). Do not run `server/index.js` as the process entry (20s rebind poll).
- Login is **`grok login --device-auth`**, not `--oauth`, as the v1 primary.
- Default grok cwd is **`$HOME/workspace`**, never the extracted JS tree.
- `questEntry.js` lives at `filesDir/app/server/questEntry.js`; `staticDir = path.join(__dirname, "..", "renderer")`.
- Overlay is **in-place unified diffs** on nested functions. Do not add sibling wrap files for `buildArgs` / `startGrokLogin`.
- FGS type is **`specialUse`**, not `dataSync`.
- `allowBackup=false`. Never log tokens.

---

## Layout

```
GrokDesktopAndroid/
├── AGENTS.md
├── DESIGN.md
├── SPIKE.md
├── README.md
├── app/                         # Gradle app, Kotlin, jniLibs (*.so gitignored)
├── native/nodewrap.c
├── overlay/server/questEntry.js # process entry (loopback httpApi)
├── overlay/patches/             # in-place diffs on synced server/ + renderer/
├── scripts/fetch-runtime.ps1    # Docker: NDK Node recipe + Termux fallback + grok
├── scripts/sync-desktop.ps1     # copy E:\Dev\GrokDesktop then apply overlay
└── SOURCE_REV                   # written by sync-desktop.ps1
```

---

## Build / install

Docker Desktop Linux engine is required for `fetch-runtime.ps1`. JDK 17 + Android SDK 34 were used under `%LOCALAPPDATA%\GrokDesktopAndroid\jdk` and `%LOCALAPPDATA%\Android\Sdk` on the original spike machine.

```powershell
cd E:\Dev\GrokDesktopAndroid
.\scripts\fetch-runtime.ps1
.\scripts\sync-desktop.ps1
.\gradlew.bat :app:assembleDebug
.\scripts\adb-install.ps1
```

`.so` files and APKs are gitignored. Re-run `fetch-runtime.ps1` after a clean clone.

On-device: grant `POST_NOTIFICATIONS`. The panel auto-starts the FGS. Pull `$HOME/.grok-desktop/spike-results.json` (`run-as`) if checking PR 1 rows. FGS doff ≥ 60s is in `SPIKE.md`.

---

## Do not

- Re-scaffold the Gradle project or change the application id.
- Vendor a JNI `libnode.so` and call it GO.
- Enable remote / Tailscale / 📱 UI.
- `git pull` as an in-app update (APK sideload only).
- Commit `vendor/`, `*.so`, or `app/build/`.
- Expand remaining PRs into extra “proof” matrices unless a GO path is actually blocked.
