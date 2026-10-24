<#
.SYNOPSIS
    Installs or removes the SifuCraft dsound.dll proxy in Sifu's Binaries\Win64 folder.
.EXAMPLE
    powershell -File tools/deploy.ps1
    powershell -File tools/deploy.ps1 -Stage 1
    powershell -File tools/deploy.ps1 -Uninstall
.PARAMETER Stage
    Rollout stage written to SifuCraft\stage (0 = read-only, 5 = P2 complete, 6 = P4 blocks, 7 = P5 enemies).
    Omit to keep the current one.
#>
param(
    [string]$GameDir,
    [string]$Dll,
    [ValidateRange(-1, 7)][int]$Stage = -1,
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'SifuInstall.psm1') -Force

$GameDir = Resolve-SifuBinaries $GameDir
$target = Join-Path $GameDir 'dsound.dll'

if (Get-SifuProcess) { throw 'Sifu is running; close it before deploying or uninstalling.' }

if (Test-Path $target) {
    if (-not (Test-SifuCraftDll $target)) {
        throw "$target is not a SifuCraft build (another mod?). Remove it yourself if that is intended."
    }
}

if ($Uninstall) {
    if (Test-Path $target) {
        Remove-Item $target
        Write-Host "Removed $target"
    } else {
        Write-Host 'Nothing to remove.'
    }
    return
}

if (-not $Dll) { $Dll = Join-Path $PSScriptRoot '..\build\mingw-release\sifu-native\dsound.dll' }
if (-not (Test-Path $Dll)) { throw "Build output not found: $Dll (run: cmake --build --preset mingw-release)" }
if (-not (Test-SifuCraftDll $Dll)) { throw "$Dll does not look like a SifuCraft build" }

Copy-Item $Dll $target -Force
Write-Host "Installed $target"
if ($Stage -ge 0) {
    $control = Join-Path $GameDir 'SifuCraft'
    New-Item -ItemType Directory -Force $control | Out-Null
    Set-Content -Path (Join-Path $control 'stage') -Value $Stage -Encoding ascii
    Write-Host "Rollout stage: $Stage"
}
Write-Host "Logs: $(Join-Path $GameDir 'SifuCraft\logs')"
