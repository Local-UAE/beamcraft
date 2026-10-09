$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $projectRoot 'beamng-mod'
$beamngUserRoot = Join-Path $env:LOCALAPPDATA 'BeamNG\BeamNG.drive\current'
$destination = Join-Path $beamngUserRoot 'mods\unpacked\beamcraft_bridge'
$sourceGeExtension = Join-Path $source 'lua\ge\extensions\beamcraftBridge.lua'
$sourceVehicleExtension = Join-Path $source 'lua\vehicle\extensions\beamcraftBridgeVehicle.lua'

if (-not (Test-Path $sourceGeExtension) -or -not (Test-Path $sourceVehicleExtension)) {
    Write-Error "BeamCraft bridge source is missing under $source."
    exit 1
}

if (-not (Test-Path $beamngUserRoot)) {
    Write-Error "BeamNG user-data directory not found at $beamngUserRoot. Launch BeamNG.drive once, then rerun this script."
    exit 1
}

New-Item -ItemType Directory -Force -Path (Join-Path $destination 'lua\ge\extensions') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $destination 'lua\vehicle\extensions') | Out-Null
Copy-Item -Force -LiteralPath $sourceGeExtension -Destination (Join-Path $destination 'lua\ge\extensions\beamcraftBridge.lua')
Copy-Item -Force -LiteralPath $sourceVehicleExtension -Destination (Join-Path $destination 'lua\vehicle\extensions\beamcraftBridgeVehicle.lua')
Copy-Item -Force -LiteralPath (Join-Path $source 'info.json') -Destination (Join-Path $destination 'info.json')
Write-Host "BeamCraft Bridge installed to $destination"
Write-Host "In BeamNG.drive, open the Lua console and run: extensions.load('beamcraftBridge')"
Write-Host "Then enter a vehicle; its vehicle Lua extension starts the local telemetry endpoint."
