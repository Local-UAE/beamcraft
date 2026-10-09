$ErrorActionPreference = 'Stop'

$packageRoot = $PSScriptRoot
$localRoot = Join-Path $env:LOCALAPPDATA 'BeamCraft'
$prismSource = Join-Path $packageRoot 'Prism'
$prismTarget = Join-Path $localRoot 'Prism'
$modSource = Join-Path $packageRoot 'BeamNG\mods\unpacked\mccrossover'
$modTarget = Join-Path $localRoot 'bng-userfolder\current\mods\unpacked\mccrossover'

if (-not (Test-Path -LiteralPath (Join-Path $prismSource 'prismlauncher.exe'))) {
    throw "Portable Prism Launcher is missing from $prismSource."
}
if (-not (Test-Path -LiteralPath (Join-Path $prismSource 'instances\BeamCraft\mmc-pack.json'))) {
    throw 'The BeamCraft Prism instance is missing its mmc-pack.json manifest.'
}
if (-not (Test-Path -LiteralPath $modSource)) {
    throw "The BeamNG extension files are missing from $modSource."
}

New-Item -ItemType Directory -Force -Path $localRoot | Out-Null
if (-not (Test-Path -LiteralPath $prismTarget)) {
    Copy-Item -LiteralPath $prismSource -Destination $prismTarget -Recurse
} else {
    $bundledInstance = Join-Path $prismSource 'instances\BeamCraft'
    $installedInstance = Join-Path $prismTarget 'instances\BeamCraft'
    if (-not (Test-Path -LiteralPath $installedInstance)) {
        New-Item -ItemType Directory -Force -Path $installedInstance | Out-Null
    }
    Get-ChildItem -LiteralPath $bundledInstance -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $installedInstance -Recurse -Force
    }
    if (-not (Test-Path -LiteralPath (Join-Path $prismTarget 'portable.txt'))) {
        [IO.File]::WriteAllText((Join-Path $prismTarget 'portable.txt'), '')
    }
}

$modParent = Split-Path -Parent $modTarget
New-Item -ItemType Directory -Force -Path $modParent | Out-Null
robocopy $modSource $modTarget /MIR /NJH /NJS /NP /NFL /NDL | Out-Null
if ($LASTEXITCODE -ge 8) {
    throw "Could not install the BeamNG extension (robocopy exit code $LASTEXITCODE)."
}
$global:LASTEXITCODE = 0

Write-Host 'BeamCraft files are installed in a separate BeamNG user folder.'
Write-Host 'Opening Prism Launcher. Sign in with the Microsoft account that owns Minecraft: Java Edition.'
Start-Process -FilePath (Join-Path $prismTarget 'prismlauncher.exe') -WorkingDirectory $prismTarget
