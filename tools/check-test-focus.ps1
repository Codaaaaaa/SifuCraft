# File/identity integration checks with simulated OS observations. No game, input or focus changes.
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'SifuTestFocus.psm1') -Force
$taskModule = Get-Module SifuTestFocus
$taskOutput = Join-Path (Split-Path $PSScriptRoot -Parent) ('build/test-focus-check-' + [Guid]::NewGuid().ToString('N'))
& $taskModule {
    param($taskDirectory)
    $script:FocusRoot = $taskDirectory
    $script:SimulatedStart = [DateTime]::UtcNow.AddMinutes(-1)
    $script:SimulatedForeground = 42
    $script:Checks = 0
    function script:Get-Process {
        param($Id, $ErrorAction)
        [pscustomobject]@{Id=42;ProcessName='Sifu-Win64-Shipping';StartTime=$script:SimulatedStart}
    }
    function script:Get-SifuTestForeground {
        [pscustomobject]@{Pid=$script:SimulatedForeground;Process='simulated';Window=[IntPtr]1}
    }
    function Expect-Refusal([scriptblock]$action, [string]$why) {
        $taskRefused=$false
        try { $null=& $action } catch { $taskRefused=$true }
        if (-not $taskRefused) { throw "Expected refusal: $why" }
        $script:Checks++
    }
    function New-SimulatedSession {
        $taskId=[Guid]::NewGuid().ToString('N')
        $taskPath=Join-Path $script:FocusRoot $taskId
        $null=New-Item -ItemType Directory -Path $taskPath -Force
        $taskStart=([DateTimeOffset]$script:SimulatedStart.ToUniversalTime()).ToUnixTimeMilliseconds()
        [IO.File]::WriteAllText((Join-Path $taskPath 'session.properties'),"version=1`ngamePid=42`ngameStartMs=$taskStart`nsessionId=$taskId`n")
        [IO.File]::WriteAllText((Join-Path $script:FocusRoot 'active.txt'),$taskId)
        [IO.File]::WriteAllText((Join-Path $taskPath 'heartbeat'),'')
        [IO.File]::WriteAllText((Join-Path $taskPath 'watcher-ready'),'')
        Get-SifuTestFocusSession
    }
    Expect-Refusal { Assert-SifuTestFocus } 'no confirmation/session'
    Expect-Refusal { Start-SifuTestFocusSession -UserConfirmed:$false } 'false confirmation cannot start a watcher'
    $taskSession=New-SimulatedSession
    $null=Assert-SifuTestFocus; $script:Checks++
    $script:SimulatedForeground=99
    Expect-Refusal { Assert-SifuTestFocus } 'focus loss'
    if (-not (Test-Path -LiteralPath (Join-Path $taskSession.Directory 'blocked.txt'))) { throw 'Focus loss must persist' }
    $script:SimulatedForeground=42
    Expect-Refusal { Assert-SifuTestFocus } 'returning to game does not resume'
    [IO.File]::SetLastWriteTimeUtc((Join-Path $taskSession.Directory 'heartbeat'),[DateTime]::UtcNow)
    Expect-Refusal { Assert-SifuTestFocus } 'new heartbeat cannot clear an interrupted run'
    $taskOld=$taskSession
    $taskSession=New-SimulatedSession
    Expect-Refusal { Assert-SifuTestFocus -Session $taskOld } 'old session cannot be reused'
    $null=Assert-SifuTestFocus; $script:Checks++
    [IO.File]::SetLastWriteTimeUtc((Join-Path $taskSession.Directory 'heartbeat'),[DateTime]::UtcNow.AddSeconds(-2))
    Expect-Refusal { Assert-SifuTestFocus } 'dead watchdog'
    [IO.File]::SetLastWriteTimeUtc((Join-Path $taskSession.Directory 'heartbeat'),[DateTime]::UtcNow)
    Expect-Refusal { Assert-SifuTestFocus } 'late watchdog cannot resume'
    $taskSession=New-SimulatedSession
    $script:SimulatedStart=$script:SimulatedStart.AddSeconds(1)
    Expect-Refusal { Assert-SifuTestFocus } 'reused PID with different start time'
    $taskSession=New-SimulatedSession
    [IO.File]::SetLastWriteTimeUtc((Join-Path $taskSession.Directory 'heartbeat'),[DateTime]::UtcNow.AddSeconds(2))
    Expect-Refusal { Assert-SifuTestFocus } 'future heartbeat fails closed'
    Write-Output "PASS $script:Checks focus interlock checks; OS input/focus untouched. Artifacts: $taskDirectory"
} $taskOutput
