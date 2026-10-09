# Sends dev commands to the running Minecraft (DevCommands.java) and prints what it answered.
#
#   scripts\mc.ps1 status
#   scripts\mc.ps1 "run gamemode survival" "look 90 0" "key forward 40"
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Commands, [int]$WaitMs = 600)
$dir = Join-Path $env:TEMP 'bngmc'
New-Item -ItemType Directory -Force $dir | Out-Null
$out = Join-Path $dir 'mc_out.txt'
$before = if (Test-Path $out) { (Get-Item $out).Length } else { 0 }
[IO.File]::AppendAllText((Join-Path $dir 'mc_cmd.txt'), (($Commands -join "`n") + "`n"))
Start-Sleep -Milliseconds $WaitMs
if (Test-Path $out) {
    $fs = [IO.File]::Open($out, 'Open', 'Read', 'ReadWrite')
    try {
        $fs.Seek($before, 'Begin') | Out-Null
        (New-Object IO.StreamReader($fs)).ReadToEnd()
    } finally { $fs.Close() }
}
