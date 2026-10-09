$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = Join-Path $projectRoot 'build\libs\beamcraft-0.2.0.jar'
$fabricApiPath = Join-Path $projectRoot '.tools\fabric-api-0.92.2+1.20.1.jar'
$minecraftRoot = Join-Path $env:APPDATA '.minecraft'
$modsRoots = @(
    (Join-Path $minecraftRoot 'mods')
)

if (-not (Test-Path $jarPath)) {
    Write-Error "BeamCraft JAR not found at $jarPath. Run ./gradlew build first."
    exit 1
}

if (-not (Test-Path $fabricApiPath)) {
    Write-Error "Fabric API not found at $fabricApiPath. Download Fabric API 0.92.2+1.20.1 from Fabric's Maven repository first."
    exit 1
}

$target = $null
foreach ($modsRoot in $modsRoots) {
    if ($modsRoot -and (Test-Path $modsRoot)) {
        $target = $modsRoot
        break
    }
}

if (-not $target) {
    Write-Host 'No Minecraft mods folder was found.'
    Write-Host 'Install Fabric for Minecraft 1.20.1 and rerun this script.'
    Write-Host 'Expected paths:'
    foreach ($modsRoot in $modsRoots) { Write-Host "  $modsRoot" }
    exit 1
}

New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item -Force $jarPath $target
Copy-Item -Force $fabricApiPath $target
Write-Host "BeamCraft installed to $target"
Write-Host "File: $(Join-Path $target (Split-Path -Leaf $jarPath))"
Write-Host "Dependency: $(Join-Path $target (Split-Path -Leaf $fabricApiPath))"
