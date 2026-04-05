Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$serverRoot = Join-Path $projectRoot "PC\QuickShareClone.Server"

Push-Location $serverRoot
try {
    dotnet run
}
finally {
    Pop-Location
}
