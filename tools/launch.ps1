<#
.SYNOPSIS
    Starts Sifu through the Epic Games Launcher and waits for the game process.
#>
param([int]$TimeoutSeconds = 180)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'SifuInstall.psm1') -Force

if (Get-SifuProcess) { Write-Host 'Sifu is already running.'; return }

$manifest = Get-SifuEpicManifest
if (-not $manifest) { throw 'Sifu was not found in the Epic manifests.' }
$uri = 'com.epicgames.launcher://apps/{0}%3A{1}%3A{2}?action=launch&silent=true' -f `
    $manifest.CatalogNamespace, $manifest.CatalogItemId, $manifest.AppName
Start-Process $uri

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    $process = Get-SifuProcess
    if ($process) {
        Write-Host "Sifu started (pid $($process.Id))."
        return
    }
    Start-Sleep -Seconds 2
}
throw "Sifu did not start within $TimeoutSeconds s."
