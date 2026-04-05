Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$adb = (Get-Command adb -ErrorAction Stop).Source
$packageName = "com.quickshareclone.android"

& $adb wait-for-device | Out-Null
& $adb logcat -c

Write-Host "Waiting for Android process: $packageName"

$appPid = $null
for ($i = 0; $i -lt 30 -and -not $appPid; $i++) {
    $appPid = (& $adb shell pidof -s $packageName).Trim()
    if (-not $appPid) {
        Start-Sleep -Seconds 1
    }
}

if (-not $appPid) {
    throw "Could not find running process for $packageName"
}

Write-Host "Streaming logcat for $packageName (pid=$appPid)"
& $adb logcat --pid $appPid QuickShareClone:D QuickShareDiscovery:D QuickShareUpload:D *:S
