$ErrorActionPreference = 'Stop'
$modJar = 'C:\Users\moham\AppData\Roaming\.minecraft\mods\beamcraft-0.1.0.jar'
$mcApp = 'shell:AppsFolder\Microsoft.MinecraftJavaEdition_8wekyb3d8bbwe'

if (-not (Test-Path $modJar)) {
    Write-Error "BeamCraft mod jar not found at $modJar. Run build first."
    exit 1
}

Write-Host 'Launching Minecraft Java Edition with the BeamCraft mod installed.'
Start-Process explorer.exe $mcApp
