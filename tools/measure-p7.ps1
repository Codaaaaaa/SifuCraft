<#
.SYNOPSIS
    Samples an already-running SifuCraft pair; never launches, focuses, sends input, or closes a game.
.EXAMPLE
    powershell -File tools/measure-p7.ps1 -Seconds 180 -GpuCounters
.NOTES
    CPU percentages are normalized across logical processors. GPU counters are optional Windows
    counters: busiest-engine utilization and dedicated/shared bytes, not GPU frame time.
#>
param(
    [ValidateRange(10,7200)][int]$Seconds = 180,
    [ValidateRange(1,10)][int]$IntervalSeconds = 1,
    [int]$MinecraftPid = 0,
    [switch]$GpuCounters,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../build/p7-performance')
)
$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class P7MeasureForeground {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
}
'@
$taskGames = @(Get-Process -Name Sifu-Win64-Shipping -ErrorAction SilentlyContinue)
if ($taskGames.Count -ne 1) { throw 'Expected one already-running Sifu process. This tool does not start games.' }
$taskClients = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {
    $_.CommandLine -match '-Dsifucraft\.' -and $_.CommandLine -match '-Dfabric.dli.env=client' -and
    ($MinecraftPid -eq 0 -or $_.ProcessId -eq $MinecraftPid)
})
if ($taskClients.Count -ne 1) { throw 'Expected one SifuCraft Minecraft client; specify -MinecraftPid if necessary.' }
$taskProcesses = @($taskGames[0], (Get-Process -Id $taskClients[0].ProcessId))
$taskPrevious = @{}
$taskNames = @{}
$taskRows = [System.Collections.Generic.List[object]]::new()
$taskClock = [System.Diagnostics.Stopwatch]::StartNew()
$taskStarted = (Get-Date).ToUniversalTime().ToString('o')
$taskNextGpu = 0; $taskEndReason = 'completed'
$taskGpuMemory = @(); $taskGpuEngines = @(); $taskGpuAvailable = $GpuCounters.IsPresent
foreach ($taskProcess in $taskProcesses) {
    $taskNames[$taskProcess.Id]=$taskProcess.ProcessName
    $null=$taskProcess.Handle # retain this lifetime even if Windows later reuses its PID
    $taskPrevious[$taskProcess.Id] = @{Cpu=$taskProcess.TotalProcessorTime.TotalMilliseconds; At=0.0}
}
try { while ($taskClock.Elapsed.TotalSeconds -lt $Seconds) {
    Start-Sleep -Seconds $IntervalSeconds
    $taskElapsed = $taskClock.Elapsed.TotalSeconds
    if ($taskGpuAvailable -and $taskElapsed -ge $taskNextGpu) {
        try {
            $taskGpuMemory = @(Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUProcessMemory)
            $taskGpuEngines = @(Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine)
        } catch {
            Write-Warning 'GPU performance counters unavailable; those columns remain empty.'
            $taskGpuAvailable = $false
        }
        $taskNextGpu = $taskElapsed + 5
    }
    $taskElapsed = $taskClock.Elapsed.TotalSeconds
    [uint32]$taskForegroundPid=0
    $null=[P7MeasureForeground]::GetWindowThreadProcessId([P7MeasureForeground]::GetForegroundWindow(),[ref]$taskForegroundPid)
    foreach ($taskProcess in $taskProcesses) {
        $taskProcess.Refresh()
        if ($taskProcess.HasExited) { throw "Process $($taskProcess.Id) exited; no game was restarted." }
        $taskCpu = $taskProcess.TotalProcessorTime.TotalMilliseconds
        $taskLast = $taskPrevious[$taskProcess.Id]
        $taskUtilization = ($taskCpu-$taskLast.Cpu) / (($taskElapsed-$taskLast.At)*1000*[Environment]::ProcessorCount)*100
        $taskPrevious[$taskProcess.Id] = @{Cpu=$taskCpu; At=$taskElapsed}
        $taskMemory = @($taskGpuMemory | Where-Object { $_.Name -match "^pid_$($taskProcess.Id)_" })
        $taskEngines = @($taskGpuEngines | Where-Object { $_.Name -match "^pid_$($taskProcess.Id)_" })
        $taskRows.Add([pscustomobject]@{
            ElapsedSeconds=[Math]::Round($taskElapsed,3); Pid=$taskProcess.Id; Process=$taskNames[$taskProcess.Id]
            SifuInForeground=($taskForegroundPid -eq $taskGames[0].Id)
            CpuPercent=[Math]::Round($taskUtilization,2)
            WorkingSetMB=[Math]::Round($taskProcess.WorkingSet64/1MB,2)
            PrivateMB=[Math]::Round($taskProcess.PrivateMemorySize64/1MB,2)
            GpuBusiestEnginePercent=if($taskGpuAvailable -and $taskEngines.Count){($taskEngines | Measure-Object UtilizationPercentage -Maximum).Maximum}else{$null}
            GpuDedicatedMB=if($taskGpuAvailable -and $taskMemory.Count){[Math]::Round(($taskMemory | Measure-Object DedicatedUsage -Sum).Sum/1MB,2)}else{$null}
            GpuSharedMB=if($taskGpuAvailable -and $taskMemory.Count){[Math]::Round(($taskMemory | Measure-Object SharedUsage -Sum).Sum/1MB,2)}else{$null}
        })
    }
} } catch {
    $taskEndReason = $_.Exception.Message
    Write-Warning "Sampling ended early; partial measurements are preserved: $taskEndReason"
}
$taskDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $taskDirectory -Force
$taskStem = Join-Path $taskDirectory (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
$taskRows | Export-Csv -LiteralPath "$taskStem.csv" -NoTypeInformation -Encoding UTF8
$taskSummary = foreach ($taskProcess in $taskProcesses) {
    $taskSamples = @($taskRows | Where-Object Pid -eq $taskProcess.Id)
    [pscustomobject]@{
        Pid=$taskProcess.Id; Process=$taskNames[$taskProcess.Id]; Samples=$taskSamples.Count
        CpuAverage=($taskSamples | Measure-Object CpuPercent -Average).Average
        CpuPeak=($taskSamples | Measure-Object CpuPercent -Maximum).Maximum
        PrivatePeakMB=($taskSamples | Measure-Object PrivateMB -Maximum).Maximum
        WorkingSetPeakMB=($taskSamples | Measure-Object WorkingSetMB -Maximum).Maximum
    }
}
[pscustomobject]@{
    StartedUtc=$taskStarted; DurationSeconds=$taskClock.Elapsed.TotalSeconds
    EndReason=$taskEndReason
    LogicalProcessors=[Environment]::ProcessorCount; GpuCountersAvailable=$taskGpuAvailable
    Metrics=$taskSummary
    Notes='CPU uses logical-processor normalization. GPU values are cached for 5 seconds, missing counters are null. No GPU frame times are measured.'
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$taskStem.json" -Encoding UTF8
Write-Output "Saved existing-process measurements to $taskStem.csv and .json"
