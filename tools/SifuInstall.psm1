# Shared helpers for the deploy / launch scripts.

function Get-SifuEpicManifest {
    $dir = Join-Path $env:ProgramData 'Epic\EpicGamesLauncher\Data\Manifests'
    if (-not (Test-Path $dir)) { return $null }
    Get-ChildItem $dir -Filter *.item | ForEach-Object {
        Get-Content $_.FullName -Raw | ConvertFrom-Json
    } | Where-Object { $_.DisplayName -eq 'Sifu' } | Select-Object -First 1
}

function Resolve-SifuBinaries([string]$GameDir) {
    if (-not $GameDir) {
        $manifest = Get-SifuEpicManifest
        if (-not $manifest) { throw 'Sifu was not found in the Epic manifests; pass -GameDir <Sifu>\Sifu\Binaries\Win64' }
        $GameDir = Join-Path $manifest.InstallLocation 'Sifu\Binaries\Win64'
    }
    if (-not (Test-Path (Join-Path $GameDir 'Sifu-Win64-Shipping.exe'))) {
        throw "Sifu-Win64-Shipping.exe not found in $GameDir"
    }
    return (Resolve-Path $GameDir).Path
}

function Test-SifuCraftDll([string]$Path) {
    # Our module carries this banner string; anything else named dsound.dll belongs to someone else.
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $text = [System.Text.Encoding]::ASCII.GetString($bytes)
    return $text.Contains('SifuCraft native')
}

function Get-SifuProcess { Get-Process -Name 'Sifu-Win64-Shipping' -ErrorAction SilentlyContinue }

function Find-Jdk25 {
    if ($env:SIFUCRAFT_JAVA_HOME -and (Test-Path "$env:SIFUCRAFT_JAVA_HOME\bin\javac.exe")) { return $env:SIFUCRAFT_JAVA_HOME }
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Temurin'),
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Java'
    )
    foreach ($dir in $candidates) {
        if (-not (Test-Path $dir)) { continue }
        $jdk = Get-ChildItem $dir -Directory | Where-Object { $_.Name -match '^jdk-25' } |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($jdk -and (Test-Path "$($jdk.FullName)\bin\javac.exe")) { return $jdk.FullName }
    }
    throw 'JDK 25 not found; set SIFUCRAFT_JAVA_HOME to its folder.'
}

Export-ModuleMember -Function Get-SifuEpicManifest, Resolve-SifuBinaries, Test-SifuCraftDll, Get-SifuProcess, Find-Jdk25
