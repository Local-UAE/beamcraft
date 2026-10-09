$ErrorActionPreference = 'Stop'

$localRoot = Join-Path $env:LOCALAPPDATA 'BeamCraft'
$userRoot = Join-Path $localRoot 'bng-userfolder'
$prismRoot = Join-Path $localRoot 'Prism'
$prismExe = Join-Path $prismRoot 'prismlauncher.exe'
$modPath = Join-Path $userRoot 'current\mods\unpacked\mccrossover'

if (-not (Test-Path -LiteralPath $prismExe) -or -not (Test-Path -LiteralPath $modPath)) {
    throw 'BeamCraft is not set up. Run install-beamcraft.ps1 from the package first, then sign in to Prism.'
}
if (Get-Process -Name 'BeamNG.drive.x64' -ErrorAction SilentlyContinue) {
    throw 'Close BeamNG.drive before starting BeamCraft.'
}

$steamPath = $null
foreach ($key in @('HKCU:\Software\Valve\Steam', 'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam', 'HKLM:\SOFTWARE\Valve\Steam')) {
    try {
        $item = Get-ItemProperty -Path $key -ErrorAction Stop
        foreach ($name in @('SteamPath', 'InstallPath')) {
            if ($item.$name -and (Test-Path -LiteralPath $item.$name)) {
                $steamPath = (Resolve-Path -LiteralPath $item.$name).Path
                break
            }
        }
    } catch {
        continue
    }
    if ($steamPath) { break }
}
if (-not $steamPath) {
    $fallback = Join-Path ${env:ProgramFiles(x86)} 'Steam'
    if (Test-Path -LiteralPath $fallback) { $steamPath = $fallback }
}
if (-not $steamPath) {
    throw 'Steam was not found. Install and sign in to Steam, then run this launcher again.'
}

$libraries = @($steamPath)
$libraryFile = Join-Path $steamPath 'steamapps\libraryfolders.vdf'
if (Test-Path -LiteralPath $libraryFile) {
    foreach ($match in (Select-String -Path $libraryFile -Pattern '"path"\s+"([^"]+)"').Matches) {
        $libraries += $match.Groups[1].Value -replace '\\\\', '\'
    }
}
$beamngExe = $null
foreach ($library in ($libraries | Select-Object -Unique)) {
    $manifest = Join-Path $library 'steamapps\appmanifest_284160.acf'
    if (-not (Test-Path -LiteralPath $manifest)) { continue }
    $entry = Select-String -Path $manifest -Pattern '"installdir"\s+"([^"]+)"'
    if (-not $entry) { continue }
    $candidate = Join-Path $library "steamapps\common\$($entry.Matches[0].Groups[1].Value)\BeamNG.drive.exe"
    if (Test-Path -LiteralPath $candidate) {
        $beamngExe = $candidate
        break
    }
}
if (-not $beamngExe) {
    throw 'BeamNG.drive was not found in the registered Steam libraries. Install it through Steam before playing.'
}

$beamngRoot = Split-Path -Parent $beamngExe
$arguments = @('-userpath', $userRoot, '-level', 'smallgrid', '-vehicle', 'pickup')
Start-Process -FilePath $beamngExe -ArgumentList $arguments -WorkingDirectory $beamngRoot | Out-Null
Start-Sleep -Seconds 8
Start-Process -FilePath $prismExe -ArgumentList @('--launch', 'BeamCraft') -WorkingDirectory $prismRoot | Out-Null
