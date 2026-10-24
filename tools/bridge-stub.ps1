<#
.SYNOPSIS
    Compiles and runs the Java bridge stub (the Minecraft endpoint stand-in) with JDK 25.
.EXAMPLE
    powershell -File tools/bridge-stub.ps1 --duration 600
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'SifuInstall.psm1') -Force
$root = Resolve-Path (Join-Path $PSScriptRoot '..')

$jdk = Find-Jdk25
$out = Join-Path $root 'build\java-stub'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force $out | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java -Path (Join-Path $root 'fabric\src\main\java\dev\sifucraft\bridge'),
    (Join-Path $root 'fabric\src\stub\java') | ForEach-Object { $_.FullName }
# "restricted" only flags the FFM calls the bridge exists to make; java runs with native access enabled.
& "$jdk\bin\javac.exe" --release 25 -Xlint:all,-restricted -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

& "$jdk\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp $out dev.sifucraft.stub.BridgeStub @args
exit $LASTEXITCODE
