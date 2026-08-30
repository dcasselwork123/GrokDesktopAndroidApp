#Requires -Version 5.1
<#
.SYNOPSIS
  Copy Grok Desktop server/ + renderer/ into APK assets, write SOURCE_REV, apply overlay.

.DESCRIPTION
  Default desktop root is E:\Dev\GrokDesktop (override with -DesktopRoot).
  Does not copy electron/ or node_modules. Must run before assembleDebug.
#>
[CmdletBinding()]
param(
    [string]$DesktopRoot = "E:\Dev\GrokDesktop"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$AssetsRoot = Join-Path $RepoRoot "app\src\main\assets\grok-desktop"

if (-not (Test-Path -LiteralPath $DesktopRoot)) {
    throw "Desktop source not found: $DesktopRoot. Pass -DesktopRoot or clone Grok Desktop to E:\Dev\GrokDesktop."
}
$serverSrc = Join-Path $DesktopRoot "server"
$rendererSrc = Join-Path $DesktopRoot "renderer"
if (-not (Test-Path -LiteralPath $serverSrc)) {
    throw "Missing $serverSrc"
}
if (-not (Test-Path -LiteralPath $rendererSrc)) {
    throw "Missing $rendererSrc"
}

Write-Host "==> Copying server/ and renderer/ from $DesktopRoot" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $AssetsRoot | Out-Null
foreach ($name in @("server", "renderer")) {
    $dest = Join-Path $AssetsRoot $name
    if (Test-Path -LiteralPath $dest) {
        Remove-Item -LiteralPath $dest -Recurse -Force
    }
    Copy-Item -LiteralPath (Join-Path $DesktopRoot $name) -Destination $dest -Recurse -Force
}

$rev = "unknown"
$dirty = ""
if (Get-Command git -ErrorAction SilentlyContinue) {
    $sha = & git -C $DesktopRoot rev-parse HEAD 2>$null
    if ($LASTEXITCODE -eq 0 -and $sha) {
        $rev = ([string]$sha).Trim()
        $status = & git -C $DesktopRoot status --porcelain --untracked-files=no -- server renderer
        if ($status) { $dirty = "-dirty" }
    }
}
$sourceRev = "$rev$dirty"
$revBytes = [System.Text.Encoding]::ASCII.GetBytes($sourceRev)
[System.IO.File]::WriteAllBytes((Join-Path $RepoRoot "SOURCE_REV"), $revBytes)
[System.IO.File]::WriteAllBytes((Join-Path $AssetsRoot "SOURCE_REV"), $revBytes)
Write-Host "SOURCE_REV $sourceRev"

& (Join-Path $PSScriptRoot "apply-overlay.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "apply-overlay.ps1 failed"
}

$questEntry = Join-Path $AssetsRoot "server\questEntry.js"
$httpApi = Join-Path $AssetsRoot "server\httpApi.js"
$indexHtml = Join-Path $AssetsRoot "renderer\index.html"
foreach ($p in @($questEntry, $httpApi, $indexHtml)) {
    if (-not (Test-Path -LiteralPath $p)) {
        throw "Overlay/sync did not produce $p"
    }
}
Write-Host "==> sync-desktop done" -ForegroundColor Green
