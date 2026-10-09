# Shows the crossover's lines from beamng.log (and errors), optionally following the file.
#
#   scripts\logs.ps1                 # last 60 [MCCROSS] / error lines of the test user folder's log
#   scripts\logs.ps1 -Follow         # keep printing new lines
#   scripts\logs.ps1 -All -Tail 200  # every line, not just ours
#   scripts\logs.ps1 -Player         # your normal user folder's log
param(
    [switch]$Follow,
    [switch]$All,
    [switch]$Player,
    [int]$Tail = 60
)
. "$PSScriptRoot\common.ps1"
$userPath = if ($Player) { Get-BeamngDefaultUserPath } else { Get-BeamngUserPath }
$log = Join-Path $userPath 'beamng.log'
if (-not (Test-Path $log)) { throw "no log at $log (has BeamNG run with this user folder?)" }
$pattern = if ($All) { '.' } else { 'MCCROSS|mccross|\|E\|' }
Write-Step $log
if ($Follow) {
    Get-Content $log -Tail $Tail -Wait | Where-Object { $_ -match $pattern }
} else {
    Get-Content $log | Where-Object { $_ -match $pattern } | Select-Object -Last $Tail
}
