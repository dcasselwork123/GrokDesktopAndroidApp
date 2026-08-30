#Requires -Version 5.1
<#
.SYNOPSIS
  Vendor arm64-v8a Node 22 (PIE executable), libnodewrap.so, libc++_shared.so, and grok CLI.

.DESCRIPTION
  Primary path: NDK r26c + Node v22.14.0 android-configure executed (NOT sourced) inside Docker.
  Termux aarch64 debs in vendor/termux-node/ are an optional developer shortcut.
  Official musl grok linux-aarch64 is downloaded unless GROK_BIONIC=1 (gated cargo build).

  Do not commit the .so files. Run this before .\gradlew.bat :app:assembleDebug.
#>
[CmdletBinding()]
param(
    [switch]$SkipNode,
    [switch]$SkipGrok,
    [int]$Jobs = 6
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$JniDir = Join-Path $RepoRoot "app\src\main\jniLibs\arm64-v8a"
$CacheRoot = Join-Path $env:LOCALAPPDATA "GrokDesktopAndroid\cache"
$DockerPlatform = "linux/amd64"
$NodeVer = "v22.14.0"
$NdkVer = "r26c"
# xai-org/grok-build pin (bionic fallback only; do not cargo-build unless requested)
$GrokBuildRev = "bc7f02eddd3d84085849dc19ed216f11c23b0571"
$GrokChannelPrimary = "https://x.ai/cli"
$GrokChannelFallback = "https://storage.googleapis.com/grok-build-public-artifacts/cli"

New-Item -ItemType Directory -Force -Path $JniDir | Out-Null
New-Item -ItemType Directory -Force -Path $CacheRoot | Out-Null

function Write-Step([string]$msg) {
    Write-Host "==> $msg" -ForegroundColor Cyan
}

function Invoke-Docker([string]$dockerArgs, [string]$failMessage) {
    $cmd = "docker $dockerArgs"
    Write-Host $cmd -ForegroundColor DarkGray
    cmd /c $cmd
    if ($LASTEXITCODE -ne 0) {
        throw "$failMessage (exit $LASTEXITCODE)"
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is required (Linux engine) to cross-compile Node / inspect ELF."
}

# ---------------------------------------------------------------------------
# Grok CLI (musl linux-aarch64) unless GROK_BIONIC=1
# ---------------------------------------------------------------------------
$wantBionic = ($env:GROK_BIONIC -eq "1")
if (-not $SkipGrok -and -not $wantBionic) {
    Write-Step "Resolve grok CLI version (stable channel)"
    $version = $null
    $base = $null
    foreach ($u in @($GrokChannelPrimary, $GrokChannelFallback)) {
        try {
            $version = (Invoke-WebRequest -Uri "$u/stable" -UseBasicParsing -TimeoutSec 30).Content.Trim()
            if ($version) {
                $base = $u
                break
            }
        } catch {
            Write-Host "channel $u/stable failed: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    if (-not $version) {
        throw "Could not read grok stable version from x.ai or GCS fallback"
    }
    Write-Host "grok stable version: $version (base $base)"
    $artifactName = "grok-$version-linux-aarch64"
    $destCache = Join-Path $CacheRoot $artifactName
    if (-not (Test-Path $destCache) -or (Get-Item $destCache).Length -lt 1MB) {
        $urls = @(
            "$base/$artifactName",
            "$GrokChannelPrimary/$artifactName",
            "$GrokChannelFallback/$artifactName"
        ) | Select-Object -Unique
        $ok = $false
        foreach ($url in $urls) {
            Write-Host "GET $url"
            try {
                curl.exe -L --fail --retry 3 -o $destCache $url
                if ((Test-Path $destCache) -and (Get-Item $destCache).Length -gt 1MB) {
                    $ok = $true
                    break
                }
            } catch {
                Write-Host "  failed: $($_.Exception.Message)" -ForegroundColor Yellow
            }
        }
        if (-not $ok) {
            throw "Failed to download $artifactName"
        }
    }
    Copy-Item -Force $destCache (Join-Path $JniDir "libgrok.so")
    Set-Content -Path (Join-Path $JniDir "libgrok.origin.txt") -Value "MUSL $artifactName from $base" -NoNewline
    Write-Host "installed libgrok.so ($((Get-Item (Join-Path $JniDir 'libgrok.so')).Length) bytes)"
}

if ($wantBionic -and -not $SkipGrok) {
    Write-Step "GROK_BIONIC=1 — build xai-org/grok-build $GrokBuildRev for aarch64-linux-android32"
    Write-Host @"
Recipe (gated; multi-hour cargo). SOURCE_REV pin: $GrokBuildRev
  Host: Linux (this Docker invocation)
  NDK: r26c
  Target: aarch64-linux-android
  API: 32
  rustup target add aarch64-linux-android
  export ANDROID_NDK_HOME=/opt/cache/android-ndk-r26c
  LINKER=`$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android32-clang
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=`$LINKER
  export TARGET_CC=`$LINKER
  export RUSTFLAGS='-C link-arg=-pie -C link-arg=-Wl,-z,max-page-size=16384'
  git clone --depth 1 https://github.com/xai-org/grok-build.git
  git checkout $GrokBuildRev
  cargo build --release --target aarch64-linux-android -p xai-grok-pager-bin
  # Artifact is xai-grok-pager; official installs ship it as grok.
  # Copy as app/src/main/jniLibs/arm64-v8a/libgrok.so
"@
    $bionicSh = @"
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates git build-essential pkg-config >/dev/null
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
. `$HOME/.cargo/env
rustup target add aarch64-linux-android
NDK=/opt/cache/android-ndk-r26c
export ANDROID_NDK_HOME=`$NDK
LINKER=`$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android32-clang
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=`$LINKER
export TARGET_CC=`$LINKER
export RUSTFLAGS='-C link-arg=-pie -C link-arg=-Wl,-z,max-page-size=16384'
SRC=/opt/cache/grok-build
if [ ! -d "`$SRC/.git" ]; then
  git clone https://github.com/xai-org/grok-build.git `$SRC
fi
cd `$SRC
git fetch --depth 1 origin $GrokBuildRev || git fetch origin
git checkout $GrokBuildRev
cargo build --release --target aarch64-linux-android -p xai-grok-pager-bin
BIN=`$SRC/target/aarch64-linux-android/release/xai-grok-pager
if [ ! -f "`$BIN" ]; then
  BIN=`$(find `$SRC/target/aarch64-linux-android/release -maxdepth 1 -type f -executable | head -n1)
fi
cp -f "`$BIN" /work/app/src/main/jniLibs/arm64-v8a/libgrok.so
echo "BIONIC $GrokBuildRev" > /work/app/src/main/jniLibs/arm64-v8a/libgrok.origin.txt
"@
    $bionicFile = Join-Path $CacheRoot "bionic-build.sh"
    Set-Content -Path $bionicFile -Value $bionicSh -Encoding utf8NoBOM
    $repoUnix = ($RepoRoot -replace '\\', '/')
    docker run --rm --platform $DockerPlatform `
        -v grok-android-runtime:/opt/cache `
        -v "${RepoRoot}:/work" `
        rust:bookworm `
        bash /work/scripts/linux-bionic-grok.sh
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Bionic grok-build failed. Leaving recipe in scripts for retry." -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------
# Node NDK build + libnodewrap (Docker linux/amd64, NDK r26c, API 32, arm64)
# ---------------------------------------------------------------------------
if (-not $SkipNode) {
    Write-Step "NDK r26c Node $NodeVer + libnodewrap.so (Docker $DockerPlatform)"
    $envArg = ""
    if ($SkipNode) { $envArg = "-e SKIP_NODE_BUILD=1" }
    $jobsEnv = "-e JOBS=$Jobs -e NODE_SRC=/opt/cache/node-$NodeVer -e CACHE_DIR=/opt/cache -e WORK_DIR=/work"
    docker run --rm --platform $DockerPlatform `
        -v grok-android-runtime:/opt/cache `
        -v "${RepoRoot}:/work" `
        -e "SKIP_NODE_BUILD=$(if ($SkipNode) { '1' } else { '0' })" `
        -e "JOBS=$Jobs" `
        -e "NODE_SRC=/opt/cache/node-$NodeVer" `
        -e "CACHE_DIR=/opt/cache" `
        -e "WORK_DIR=/work" `
        -e "TERMUX_DIR=/work/vendor/termux-node" `
        debian:bookworm-slim `
        bash /work/scripts/linux-build-runtime.sh
    if ($LASTEXITCODE -ne 0) {
        throw "linux-build-runtime.sh failed"
    }
}

# ---------------------------------------------------------------------------
# Host-side ELF checks (hard-fail JNI-only libnode.so with no interpreter)
# ---------------------------------------------------------------------------
Write-Step "readelf -l / file via Docker (must show PIE + /system/bin/linker64)"
docker run --rm --platform $DockerPlatform `
    -v "${RepoRoot}:/work" `
    debian:bookworm-slim `
    bash /work/scripts/linux-verify-elf.sh
if ($LASTEXITCODE -ne 0) {
    throw "ELF verification failed: libnode.so must be a PIE executable with /system/bin/linker64. JNI nodejs-mobile libs are a hard fail."
}

Write-Step "fetch-runtime complete"
Get-ChildItem $JniDir | Format-Table Name, Length
Write-Host "Next: .\gradlew.bat :app:assembleDebug"
