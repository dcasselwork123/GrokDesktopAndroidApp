#Requires -Version 5.1
<#
.SYNOPSIS
  Sideload the debug APK onto a USB device and start MainActivity.

.DESCRIPTION
  Matches:
    adb -d install -r app\build\outputs\apk\debug\app-debug.apk
    adb -d shell am start -n dev.grokdesktop.quest/.MainActivity
  Assembles the debug APK first if it is missing. Fails if adb or a USB device is missing.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$Apk = Join-Path $RepoRoot "app\build\outputs\apk\debug\app-debug.apk"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb not found. Install Android platform-tools and add them to PATH."
}

$adbDevices = adb devices
$hasUsbDevice = $false
foreach ($line in $adbDevices) {
    if ($line -match "\tdevice$") {
        $hasUsbDevice = $true
        break
    }
}
if (-not $hasUsbDevice) {
    throw "No USB adb device (adb -d). Connect Quest 3 with debugging enabled."
}

if (-not (Test-Path $Apk)) {
    Write-Host "APK missing; assembling debug..." -ForegroundColor Cyan
    Push-Location $RepoRoot
    try {
        & .\gradlew.bat :app:assembleDebug
        if ($LASTEXITCODE -ne 0) {
            throw "assembleDebug failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $Apk)) {
    throw "APK not found at $Apk"
}

Write-Host "adb -d install -r $Apk" -ForegroundColor Cyan
adb -d install -r $Apk
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed with exit code $LASTEXITCODE"
}

Write-Host "adb -d shell am start -n dev.grokdesktop.quest/.MainActivity" -ForegroundColor Cyan
adb -d shell am start -n dev.grokdesktop.quest/.MainActivity
if ($LASTEXITCODE -ne 0) {
    throw "am start failed with exit code $LASTEXITCODE"
}
