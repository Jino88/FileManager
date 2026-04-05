Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$patterns = @(
    "QuickShareClone.Desktop",
    "QuickShareClone.Server",
    "Microsoft.VisualStudio.ProjectSystem.Server.BuildHost.dll",
    "MSBuild.dll"
)

function Test-MatchesProjectPattern {
    param(
        [Parameter(Mandatory = $true)]
        $ProcessInfo
    )

    $commandLine = ""
    $executablePath = ""

    if ($null -ne $ProcessInfo.PSObject.Properties["CommandLine"]) {
        $commandLine = [string]$ProcessInfo.CommandLine
    }

    if ($null -ne $ProcessInfo.PSObject.Properties["ExecutablePath"]) {
        $executablePath = [string]$ProcessInfo.ExecutablePath
    }

    foreach ($pattern in $patterns) {
        if ($commandLine -like "*$pattern*" -or $executablePath -like "*$pattern*") {
            return $true
        }
    }

    return $false
}

$candidates = Get-CimInstance Win32_Process |
    Where-Object {
        $_.Name -in @("dotnet.exe", "QuickShareClone.Desktop.exe", "QuickShareClone.Server.exe") -and
        (Test-MatchesProjectPattern $_)
    }

foreach ($process in $candidates) {
    try {
        Write-Host "Stopping process $($process.ProcessId): $($process.Name)"
        Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
    } catch {
        Write-Warning "Failed to stop process $($process.ProcessId): $($_.Exception.Message)"
    }
}

$portOwners = Get-NetTCPConnection -LocalPort 5070 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique

foreach ($pid in $portOwners) {
    try {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $pid"
        if ($proc -and (Test-MatchesProjectPattern $proc)) {
            Write-Host "Stopping port 5070 owner $pid"
            Stop-Process -Id $pid -Force -ErrorAction Stop
        }
    } catch {
        Write-Warning ("Failed to stop port owner {0}: {1}" -f $pid, $_.Exception.Message)
    }
}
