#Requires -Version 5.1
<#
.SYNOPSIS
  Apply overlay/patches/*.diff onto the synced server/ + renderer/ tree, then copy questEntry.js.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$AssetsRoot = Join-Path $RepoRoot "app\src\main\assets\grok-desktop"
$PatchDir = Join-Path $RepoRoot "overlay\patches"
$QuestEntrySrc = Join-Path $RepoRoot "overlay\server\questEntry.js"
$AppUpdateSrc = Join-Path $RepoRoot "overlay\server\appUpdate.js"

$httpApi = Join-Path $AssetsRoot "server\httpApi.js"
if (-not (Test-Path -LiteralPath $httpApi)) {
    throw "Synced tree missing $httpApi. Run scripts/sync-desktop.ps1 first."
}
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw "git is required to apply overlay/patches."
}
if (-not (Test-Path -LiteralPath $QuestEntrySrc)) {
    throw "Missing $QuestEntrySrc"
}

$patches = @(Get-ChildItem -LiteralPath $PatchDir -Filter "*.diff" -ErrorAction SilentlyContinue | Sort-Object Name)
if ($patches.Count -eq 0) {
    throw "No overlay/patches/*.diff found"
}

Push-Location $RepoRoot
try {
    foreach ($p in $patches) {
        Write-Host "==> git apply $($p.Name)" -ForegroundColor Cyan
        & git apply --whitespace=nowarn --ignore-whitespace --directory=app/src/main/assets/grok-desktop -- $p.FullName
        if ($LASTEXITCODE -ne 0) {
            throw "git apply failed: $($p.Name)"
        }
    }
} finally {
    Pop-Location
}

$questDestDir = Join-Path $AssetsRoot "server"
New-Item -ItemType Directory -Force -Path $questDestDir | Out-Null
Copy-Item -LiteralPath $QuestEntrySrc -Destination (Join-Path $questDestDir "questEntry.js") -Force
Write-Host "==> copied overlay/server/questEntry.js"
if (Test-Path -LiteralPath $AppUpdateSrc) {
    Copy-Item -LiteralPath $AppUpdateSrc -Destination (Join-Path $questDestDir "appUpdate.js") -Force
    Write-Host "==> copied overlay/server/appUpdate.js"
}
