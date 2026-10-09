# Captures a window's client area to a PNG (default: BeamNG's main window). Used by tests to look at
# what each game is showing.
#
#   scripts\screenshot.ps1 -Out C:\temp\bng.png
#   scripts\screenshot.ps1 -Process javaw -Out C:\temp\mc.png
param(
    [string]$Out = (Join-Path $env:TEMP 'bng-shot.png'),
    [string]$Process = 'BeamNG.drive.x64'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class ShotWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
'@
[ShotWin]::SetProcessDPIAware() | Out-Null
$p = Get-Process -Name $Process -ErrorAction Stop | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if (-not $p) { throw "$Process has no main window" }
$r = New-Object ShotWin+RECT
[ShotWin]::GetClientRect($p.MainWindowHandle, [ref]$r) | Out-Null
$pt = New-Object ShotWin+POINT
[ShotWin]::ClientToScreen($p.MainWindowHandle, [ref]$pt) | Out-Null
$w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($pt.X, $pt.Y, 0, 0, (New-Object System.Drawing.Size $w, $h))
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "$Out ${w}x${h} at $($pt.X),$($pt.Y)"
