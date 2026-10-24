<#
.SYNOPSIS
    Runs the Fabric project's Gradle wrapper on JDK 25.
.EXAMPLE
    powershell -File tools/gradle.ps1 build
    powershell -File tools/gradle.ps1 runClient
    powershell -File tools/gradle.ps1 runStub --args="--duration 30"
#>
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'SifuInstall.psm1') -Force

$env:JAVA_HOME = Find-Jdk25
& (Join-Path $PSScriptRoot '..\fabric\gradlew.bat') -p (Join-Path $PSScriptRoot '..\fabric') @args
exit $LASTEXITCODE
