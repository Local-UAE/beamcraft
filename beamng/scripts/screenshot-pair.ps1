# Captures BeamNG's and Minecraft's client areas in ONE screen grab (same instant), and saves
# them side by side plus as two separate PNGs. Both windows must be visible (arrange-windows.ps1).
#
#   scripts\screenshot-pair.ps1 -Out C:\temp\pair     # -> pair.png, pair_bng.png, pair_mc.png
#   scripts\screenshot-pair.ps1 -Out C:\temp\seq -Count 5 -IntervalMs 200   # a short sequence
param(
    [string]$Out = (Join-Path $env:TEMP 'bng-pair'),
    [int]$Count = 1,
    [int]$IntervalMs = 250
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class PairWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
'@
[PairWin]::SetProcessDPIAware() | Out-Null

function Get-ClientRectScreen([string]$name) {
    $p = Get-Process -Name $name -ErrorAction Stop | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
    $r = New-Object PairWin+RECT; [PairWin]::GetClientRect($p.MainWindowHandle, [ref]$r) | Out-Null
    $pt = New-Object PairWin+POINT; [PairWin]::ClientToScreen($p.MainWindowHandle, [ref]$pt) | Out-Null
    return [System.Drawing.Rectangle]::new($pt.X, $pt.Y, $r.Right - $r.Left, $r.Bottom - $r.Top)
}

$b = Get-ClientRectScreen 'BeamNG.drive.x64'
$m = Get-ClientRectScreen 'java'
$u = [System.Drawing.Rectangle]::Union($b, $m)
for ($i = 0; $i -lt $Count; $i++) {
    $all = New-Object System.Drawing.Bitmap $u.Width, $u.Height
    $g = [System.Drawing.Graphics]::FromImage($all)
    $g.CopyFromScreen($u.X, $u.Y, 0, 0, $u.Size)
    $g.Dispose()
    $suffix = if ($Count -gt 1) { "_$i" } else { '' }
    $bb = $all.Clone([System.Drawing.Rectangle]::new($b.X - $u.X, $b.Y - $u.Y, $b.Width, $b.Height), $all.PixelFormat)
    $mm = $all.Clone([System.Drawing.Rectangle]::new($m.X - $u.X, $m.Y - $u.Y, $m.Width, $m.Height), $all.PixelFormat)
    $bb.Save("$Out${suffix}_bng.png"); $mm.Save("$Out${suffix}_mc.png")
    $pair = New-Object System.Drawing.Bitmap ($b.Width + $m.Width + 6), ([Math]::Max($b.Height, $m.Height))
    $pg = [System.Drawing.Graphics]::FromImage($pair)
    $pg.DrawImage($bb, 0, 0); $pg.DrawImage($mm, $b.Width + 6, 0); $pg.Dispose()
    $pair.Save("$Out$suffix.png")
    $all.Dispose(); $bb.Dispose(); $mm.Dispose(); $pair.Dispose()
    if ($i -lt $Count - 1) { Start-Sleep -Milliseconds $IntervalMs }
}
Write-Output "$Out (BeamNG $($b.Width)x$($b.Height), Minecraft $($m.Width)x$($m.Height), $Count capture(s))"
