# Tests the F4 control switch end to end with synthetic key presses (SendInput), both games running:
#   1. focus Minecraft, press F4  -> Minecraft hides/pauses, BeamNG in front, BeamNG camera released
#   2. press F4 (BeamNG in front) -> Minecraft back in front, camera driven by Minecraft again
# Prints PASS/FAIL per step; exit code 0 only if everything passed.
param([int]$Vk = 0x73)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class HandoffWin {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
}
'@
function Press([byte]$vk) {
    [HandoffWin]::keybd_event($vk, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 120
    [HandoffWin]::keybd_event($vk, 0, 2, [UIntPtr]::Zero)
}
function Focus([IntPtr]$h) {
    [HandoffWin]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [HandoffWin]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)   # Alt tap: allows SetForegroundWindow
    [HandoffWin]::SetForegroundWindow($h) | Out-Null
    Start-Sleep -Milliseconds 400
}
function CamDriven {
    $py = Join-Path $BeamngRoot 'bridge\bngdiag.py'
    $line = python $py raw 0.4 | Where-Object { $_ -match '"t": "state"' } | Select-Object -Last 1
    if (-not $line) { return $null }
    return ($line | ConvertFrom-Json).cam.ovr
}
$mc = (Get-Process java | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1).MainWindowHandle
$bng = (Get-BngProcess | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1).MainWindowHandle
if (-not $mc -or -not $bng) { throw 'both games must be running' }
$log = Join-Path $BeamngRoot 'minecraft\run\logs\latest.log'
$ok = $true
function Check([string]$what, [bool]$cond) {
    Write-Host ("  {0,-55} {1}" -f $what, $(if ($cond) { 'PASS' } else { 'FAIL' })) -ForegroundColor $(if ($cond) { 'Green' } else { 'Red' })
    if (-not $cond) { $script:ok = $false }
}

Write-Step 'Before: Minecraft drives the camera'
# Never steal focus from whatever the user is doing: the test only runs if Minecraft is in front.
if ([HandoffWin]::GetForegroundWindow() -ne $mc) { Write-Warn2 'Minecraft is not the foreground window; click it (or run scripts\mc.ps1 ...) and rerun'; exit 2 }
Check 'BeamNG camera driven by Minecraft' ((CamDriven) -eq $true)

Write-Step 'F4 in Minecraft'
$n0 = (Select-String -Path $log -Pattern 'Control -> BeamNG').Count
Press $Vk
Start-Sleep -Milliseconds 1200
Check 'Minecraft logged "Control -> BeamNG"' ((Select-String -Path $log -Pattern 'Control -> BeamNG').Count -gt $n0)
Check 'BeamNG window in front' ([HandoffWin]::GetForegroundWindow() -eq $bng)
Check 'BeamNG has its own camera back' ((CamDriven) -eq $false)

Write-Step 'F4 in BeamNG'
$n1 = (Select-String -Path $log -Pattern 'Control -> Minecraft').Count
Press $Vk
Start-Sleep -Milliseconds 1500
Check 'Minecraft logged "Control -> Minecraft"' ((Select-String -Path $log -Pattern 'Control -> Minecraft').Count -gt $n1)
Check 'Minecraft window visible' ([HandoffWin]::IsWindowVisible($mc))
Check 'Minecraft window in front' ([HandoffWin]::GetForegroundWindow() -eq $mc)
Check 'BeamNG camera driven by Minecraft again' ((CamDriven) -eq $true)
if ($ok) { Write-Host 'PASS' -ForegroundColor Green; exit 0 } else { Write-Host 'FAIL' -ForegroundColor Red; exit 1 }
