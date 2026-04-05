Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $projectRoot "ANDROID"
$apkPath = Join-Path $androidRoot "app\build\outputs\apk\debug\app-debug.apk"
$packageName = "com.quickshareclone.android"
$activityName = "com.quickshareclone.android/.ShareReceiverActivity"

function Get-GradleCommand {
    $stable = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch 'milestone|rc|preview' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName

    if ($stable) {
        return $stable
    }

    $fallback = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists" -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName

    if (-not $fallback) {
        throw "Gradle executable was not found. Open the Android project in Android Studio once or install Gradle."
    }

    return $fallback
}

$adb = (Get-Command adb -ErrorAction Stop).Source
$devices = & $adb devices
$onlineDevice = $devices | Select-String -Pattern "device$"
if (-not $onlineDevice) {
    throw "No Android device/emulator is connected. Start a device and retry."
}

$gradle = Get-GradleCommand
Push-Location $androidRoot
try {
    Write-Host "Building Android debug APK with Gradle daemon enabled for faster repeat builds..."
    & $gradle "assembleDebug"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed."
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path $apkPath)) {
    throw "APK not found at $apkPath"
}

& $adb install -r $apkPath
if ($LASTEXITCODE -ne 0) {
    throw "ADB install failed."
}

& $adb shell am start -n $activityName
if ($LASTEXITCODE -ne 0) {
    throw "Failed to launch Android activity."
}

Write-Host "Android app installed and launched successfully."
