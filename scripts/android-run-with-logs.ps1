Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot

& (Join-Path $projectRoot "scripts\build-install-run-android.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "Android build/install/run step failed."
}

& (Join-Path $projectRoot "scripts\android-logcat.ps1")
