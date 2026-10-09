# Starts Minecraft 1.21.1 with the crossover mod (Fabric dev launch, offline player "Steve").
# If BeamNG is running with the mod, Minecraft opens the "BeamNG Bridge" void world by itself.
#
#   scripts\run-minecraft.ps1                       # window on the main monitor, 120 fps cap
#   scripts\run-minecraft.ps1 -Screen second        # on the other monitor
#   scripts\run-minecraft.ps1 -MaxFps 0             # leave Minecraft's own frame cap alone
#   scripts\run-minecraft.ps1 -Window 0,0,1280,720  # x,y,w,h
#   scripts\run-minecraft.ps1 -Port 47021           # talk to BeamNG on another port
#   scripts\run-minecraft.ps1 -Mode host            # Minecraft hosts: superflat world, BeamNG cars drive in it
#                                                   # (start BeamNG with -Level smallgrid; docs/minecraft-host.md)
#   scripts\run-minecraft.ps1 -Mode terrain         # the same on Minecraft's normal terrain (BeamNG gets it as a terrain)
#   scripts\run-minecraft.ps1 -Mode terrain -World bng-house   # a save of your own in minecraft\run\saves, its name ending in [BeamNG]
#   scripts\run-minecraft.ps1 -NoControllerMods     # without Controlify (controller support for the character)
#   scripts\run-minecraft.ps1 -NoShaderMods         # without Sodium and Iris (shader packs)
param(
    [string]$Window = '',
    [ValidateSet('main', 'second')][string]$Screen = 'main',
    # Minecraft and BeamNG share the GPU: uncapped (260) Minecraft held BeamNG to 68-111 fps and its
    # state stream to 46-53 Hz, the car's jitter; at 120 BeamNG kept 120 fps and 60 Hz (2026-10-03)
    [int]$MaxFps = 120,
    [int]$Port = 47020,
    [ValidateSet('bridge', 'host', 'terrain')][string]$Mode = 'bridge',
    [string]$World = '',
    [switch]$NoControllerMods,
    [switch]$NoShaderMods
)
. "$PSScriptRoot\common.ps1"
$env:JAVA_HOME = Get-Java21Home
if (-not $Window) {
    Add-Type -AssemblyName System.Windows.Forms
    $other = [System.Windows.Forms.Screen]::AllScreens | Where-Object { -not $_.Primary } | Select-Object -First 1
    $s = if ($Screen -eq 'second' -and $other) { $other } else { [System.Windows.Forms.Screen]::PrimaryScreen }
    $b = $s.WorkingArea
    $Window = "$($b.X + 20),$($b.Y + 40),$([Math]::Min(1600, $b.Width - 40)),$([Math]::Min(900, $b.Height - 80))"
}
New-Item -ItemType Directory -Force (Join-Path $env:TEMP 'bngmc') | Out-Null
$optionsFile = Join-Path $BeamngRoot 'minecraft\run\options.txt'
if ($MaxFps -gt 0 -and (Test-Path $optionsFile)) {
    # Minecraft writes options.txt back on exit, so the cap is set again at every start (UTF-8, no BOM)
    $lines = (Get-Content $optionsFile) -replace '^maxFps:\d+$', "maxFps:$MaxFps"
    [IO.File]::WriteAllLines($optionsFile, $lines)
}
# Personal settings stay out of git: minecraft/run/ is ignored. bngbridge-local.properties may hold
#   username=YourName   (the offline player's name)
#   skin=YourName       (whose public Minecraft skin to wear)
$local = @{}
$localFile = Join-Path $BeamngRoot 'minecraft\run\bngbridge-local.properties'
if (Test-Path $localFile) {
    foreach ($line in Get-Content $localFile) {
        if ($line -match '^\s*([a-z]+)\s*=\s*([A-Za-z0-9_]{1,16})\s*$') { $local[$Matches[1]] = $Matches[2] }
    }
}
# Mods that go in minecraft/run/mods, not into Gradle (Fabric Loader remaps that folder itself):
# Controlify plays the character with a controller (the car's own controls take over while
# seated); it uses Fabric's newer class tweaker format, which this Loom can't read. Pinned
# Modrinth versions, checked against Modrinth's SHA-512 before use. Sodium and Iris come through
# Gradle instead (build.gradle: Iris's mixins fail from run/mods in a dev environment); here only
# a shader pack for Iris.
$modsDir = Join-Path $BeamngRoot 'minecraft\run\mods'
function Install-Modrinth([string]$Name, [string]$Version, [string]$Dir) {
    New-Item -ItemType Directory -Force $Dir | Out-Null
    $info = Invoke-RestMethod "https://api.modrinth.com/v2/version/$Version"
    $file = $info.files | Where-Object { $_.primary } | Select-Object -First 1
    if (-not $file) { $file = $info.files[0] }
    $dest = Join-Path $Dir $file.filename
    if (Test-Path $dest) { return }
    Write-Step "Downloading $Name into $(Split-Path $Dir -Leaf)"
    $tmp = "$dest.part"
    Invoke-WebRequest -Uri $file.url -OutFile $tmp -UseBasicParsing
    $sha = (Get-FileHash -Algorithm SHA512 $tmp).Hash.ToLower()
    if ($sha -ne $file.hashes.sha512) {
        Remove-Item $tmp -Force
        throw "${Name}: SHA-512 doesn't match Modrinth's, not installed"
    }
    Move-Item $tmp $dest -Force
}
$controllerMods = @(
    @{ Name = 'Controlify 3.0.1+lts'; Version = 'y9bu5RxH' },
    @{ Name = 'YetAnotherConfigLib 3.8.2'; Version = 'o3cDn8Vp' }
)
if ($NoControllerMods) {
    Get-ChildItem $modsDir -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(controlify|yet_another_config_lib)' } | Remove-Item -Force
} else {
    foreach ($m in $controllerMods) { Install-Modrinth $m.Name $m.Version $modsDir }
    # A fresh install gets the controller layout the crossover is played with (minecraft\
    # controlify-defaults.json: B / circle sneaks, so it gets you out of a car; bumpers attack and
    # use; triggers change the hotbar slot). An existing controlify.json is left alone.
    $controlify = Join-Path $BeamngRoot 'minecraft\run\config\controlify.json'
    if (-not (Test-Path $controlify)) {
        New-Item -ItemType Directory -Force (Split-Path $controlify) | Out-Null
        Copy-Item (Join-Path $BeamngRoot 'minecraft\controlify-defaults.json') $controlify
        Write-Ok 'controller layout for the crossover set up (Controlify)'
    }
}
if (-not $NoShaderMods) {
    # a shader pack to start with (Options > Video Settings > Shader Packs picks one)
    Install-Modrinth 'Complementary Reimagined r5.9.3' 'Bqen1mJX' (Join-Path $BeamngRoot 'minecraft\run\shaderpacks')
}

$extra = @()
if ($local['username']) { $extra += "-PmcUser=$($local['username'])" }
if ($local['skin']) { $extra += "-PbngSkin=$($local['skin'])" }
if ($NoShaderMods) { $extra += '-PnoShaderMods' }
if ($World) {
    if (-not (Test-Path (Join-Path $BeamngRoot "minecraft\run\saves\$World\level.dat"))) { throw "No save '$World' in minecraft\run\saves" }
    $extra += "-PbngWorld=$World"
}
Write-Step "Minecraft (JDK $env:JAVA_HOME), window $Window, BeamNG port $Port, mode $Mode$(if ($World) { ", world $World" })"
Push-Location (Join-Path $BeamngRoot 'minecraft')
try {
    & .\gradlew.bat --console=plain runClient "-PmcWindow=$Window" "-PbngPort=$Port" "-PbngMode=$Mode" @extra
} finally {
    Pop-Location
}
