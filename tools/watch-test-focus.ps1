param([Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{32}$')][string]$SessionId)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'SifuTestFocus.psm1') -Force
Watch-SifuTestFocusSession -SessionId $SessionId
