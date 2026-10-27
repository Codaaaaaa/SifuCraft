# Live test automation must never reclaim focus after the user switches away.
# A new session is created only after an explicit reply from the user.
$ErrorActionPreference = 'Stop'
$script:FocusRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'build/test-focus'

if (-not ('SifuTestForeground' -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class SifuTestForeground {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
}
'@
}

function Get-SifuTestForeground {
    [uint32]$taskForegroundId = 0
    $taskWindow = [SifuTestForeground]::GetForegroundWindow()
    $null = [SifuTestForeground]::GetWindowThreadProcessId($taskWindow, [ref]$taskForegroundId)
    $taskProcess = Get-Process -Id $taskForegroundId -ErrorAction SilentlyContinue
    [pscustomobject]@{ Pid = $taskForegroundId; Window = $taskWindow; Process = $taskProcess.ProcessName }
}

function Get-SifuTestFocusSession {
    $taskActive = Join-Path $script:FocusRoot 'active.txt'
    if (-not (Test-Path -LiteralPath $taskActive)) { return $null }
    $taskId = [IO.File]::ReadAllText($taskActive).Trim()
    if ($taskId -notmatch '^[a-f0-9]{32}$') { throw 'Invalid test focus session' }
    $taskDirectory = Join-Path $script:FocusRoot $taskId
    $taskValues = ConvertFrom-StringData ([IO.File]::ReadAllText((Join-Path $taskDirectory 'session.properties')))
    [pscustomobject]@{ Directory = $taskDirectory; Id = $taskId; GamePid = [int]$taskValues.gamePid; StartMs = [long]$taskValues.gameStartMs }
}

function Suspend-SifuTestFocus {
    param([string]$Reason = 'User is using the computer', $Session = (Get-SifuTestFocusSession))
    if ($null -eq $Session) { return }
    $taskBlock = Join-Path $Session.Directory 'blocked.txt'
    # Never clear this latch, including when the user returns to the game.
    if (-not (Test-Path -LiteralPath $taskBlock)) {
        [IO.File]::WriteAllText($taskBlock, ('{0:o} {1}' -f [DateTime]::UtcNow, $Reason))
    }
}

function Assert-SifuTestFocus {
    param($Session = (Get-SifuTestFocusSession))
    if ($null -eq $Session) { throw 'Live testing paused: explicit user confirmation is required before creating a focus session.' }
    $taskCurrent = Get-SifuTestFocusSession
    if ($null -eq $taskCurrent -or $taskCurrent.Id -ne $Session.Id) {
        Suspend-SifuTestFocus -Session $Session -Reason 'Focus session was replaced'
        throw 'Live testing paused: this session has been replaced.'
    }
    $taskBlock = Join-Path $Session.Directory 'blocked.txt'
    if (Test-Path -LiteralPath $taskBlock) { throw "Live testing paused; ask the user and wait for confirmation. $([IO.File]::ReadAllText($taskBlock))" }
    $taskGame = Get-Process -Id $Session.GamePid -ErrorAction SilentlyContinue
    $taskFocus = Get-SifuTestForeground
    $taskHeartbeat = Get-Item -LiteralPath (Join-Path $Session.Directory 'heartbeat') -ErrorAction SilentlyContinue
    $taskValid = $taskGame -and $taskGame.ProcessName -eq 'Sifu-Win64-Shipping' -and
        ([DateTimeOffset]$taskGame.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds() -eq $Session.StartMs -and
        $taskFocus.Pid -eq $Session.GamePid -and (Test-Path -LiteralPath (Join-Path $Session.Directory 'watcher-ready')) -and $taskHeartbeat -and
        ([DateTime]::UtcNow - $taskHeartbeat.LastWriteTimeUtc).TotalMilliseconds -ge 0 -and
        ([DateTime]::UtcNow - $taskHeartbeat.LastWriteTimeUtc).TotalMilliseconds -le 1500
    if (-not $taskValid) {
        Suspend-SifuTestFocus -Session $Session -Reason "Focus/identity/watchdog lost; foreground=$($taskFocus.Process)/$($taskFocus.Pid)"
        throw 'Live testing paused: focus or watchdog lost. Ask the user and wait for confirmation; do not focus the game automatically.'
    }
    return $Session
}

function Start-SifuTestFocusSession {
    [CmdletBinding()]
    param([Parameter(Mandatory)][switch]$UserConfirmed)
    if (-not $UserConfirmed) { throw 'An actual user reply is required; elapsed time and foreground changes are not confirmation.' }
    $taskGames = @(Get-Process -Name Sifu-Win64-Shipping -ErrorAction SilentlyContinue)
    $taskFocus = Get-SifuTestForeground
    if ($taskGames.Count -ne 1 -or $taskFocus.Pid -ne $taskGames[0].Id) {
        throw 'Confirmed test session requires the game in the foreground. This module never changes focus.'
    }
    $taskId = [Guid]::NewGuid().ToString('N')
    $taskDirectory = Join-Path $script:FocusRoot $taskId
    $null = New-Item -ItemType Directory -Path $taskDirectory -Force
    $taskStart = ([DateTimeOffset]$taskGames[0].StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
    [IO.File]::WriteAllText((Join-Path $taskDirectory 'session.properties'), "version=1`ngamePid=$($taskGames[0].Id)`ngameStartMs=$taskStart`nsessionId=$taskId`n")
    [IO.File]::WriteAllText((Join-Path $taskDirectory 'heartbeat'), '')
    [IO.File]::WriteAllText((Join-Path $script:FocusRoot 'active.txt'), $taskId)
    $taskWatch = Join-Path $PSScriptRoot 'watch-test-focus.ps1'
    $taskWatchArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"{0}"' -f $taskWatch), '-SessionId', $taskId)
    $taskWatcher = Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList $taskWatchArgs
    [IO.File]::WriteAllText((Join-Path $taskDirectory 'watcher-pid.txt'), [string]$taskWatcher.Id)
    $taskDeadline = [DateTime]::UtcNow.AddSeconds(10)
    while (-not (Test-Path -LiteralPath (Join-Path $taskDirectory 'watcher-ready'))) {
        if ($taskWatcher.HasExited -or [DateTime]::UtcNow -ge $taskDeadline) {
            Suspend-SifuTestFocus -Reason 'Focus watchdog did not become ready'
            throw 'Focus watchdog unavailable; live testing remains paused.'
        }
        Start-Sleep -Milliseconds 25
    }
    Assert-SifuTestFocus
}

function Watch-SifuTestFocusSession {
    param([Parameter(Mandatory)][string]$SessionId)
    $taskSession = Get-SifuTestFocusSession
    if ($null -eq $taskSession -or $taskSession.Id -ne $SessionId) { return }
    # Startup is the only point that initializes the heartbeat. Existing latches
    # are never removed, and later stale heartbeats are never renewed.
    if (Test-Path -LiteralPath (Join-Path $taskSession.Directory 'blocked.txt')) { return }
    [IO.File]::SetLastWriteTimeUtc((Join-Path $taskSession.Directory 'heartbeat'), [DateTime]::UtcNow)
    [IO.File]::WriteAllText((Join-Path $taskSession.Directory 'watcher-ready'), '')
    while ($true) {
        try {
            $taskCurrent = Get-SifuTestFocusSession
            if ($null -eq $taskCurrent -or $taskCurrent.Id -ne $SessionId) { return }
            # Validate before updating the heartbeat, so a dead/late watchdog cannot rearm itself.
            $null = Assert-SifuTestFocus -Session $taskSession
            [IO.File]::SetLastWriteTimeUtc((Join-Path $taskSession.Directory 'heartbeat'), [DateTime]::UtcNow)
        } catch { return }
        Start-Sleep -Milliseconds 20
    }
}

Export-ModuleMember -Function Get-SifuTestForeground, Get-SifuTestFocusSession, Suspend-SifuTestFocus, Assert-SifuTestFocus, Start-SifuTestFocusSession, Watch-SifuTestFocusSession
