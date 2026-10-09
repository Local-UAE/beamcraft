param(
    [Parameter(Mandatory = $true)]
    [string]$BridgeJar,
    [string]$OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist')
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$bridgeJarPath = (Resolve-Path -LiteralPath $BridgeJar).Path
$versionMatch = Select-String -Path (Join-Path $repoRoot 'beamng\minecraft\gradle.properties') -Pattern '^version=(.+)$'
if (-not $versionMatch) {
    throw 'Could not read the crossover version from beamng\minecraft\gradle.properties.'
}
$version = $versionMatch.Matches[0].Groups[1].Value.Trim()

$prismVersion = '11.1.1'
$prismArchive = "PrismLauncher-Windows-MSVC-Portable-$prismVersion.zip"
$prismUrl = "https://github.com/PrismLauncher/PrismLauncher/releases/download/$prismVersion/$prismArchive"
$prismSha256 = 'AB35A770FB06D89D2CCC098079DB5DB329FB4E68F42B72BABD8B095EFDE3D2D7'
$prismLicenseUrl = "https://raw.githubusercontent.com/PrismLauncher/PrismLauncher/$prismVersion/LICENSE"
$fabricApiVersion = '0.116.17+1.21.1'
$fabricApiFile = "fabric-api-$fabricApiVersion.jar"
$fabricApiUrl = 'https://cdn.modrinth.com/data/P7dR8mSH/versions/Mys3P7lK/fabric-api-0.116.17%2B1.21.1.jar'
$fabricApiSha512 = '98C478217DA19181F0E4DF3EA6C2DA6BDBDA271C7544A4AEA048513A49B444F1FF5B71DC0DF6303017C48E8FF69B7BB6AF6179BDF0D0E09AB3C1EE08411857A8'
$fabricApiLicenseUrl = 'https://www.apache.org/licenses/LICENSE-2.0.txt'

$cacheDirectory = Join-Path ([IO.Path]::GetTempPath()) 'beamcraft-melty-package-cache'
New-Item -ItemType Directory -Force -Path $cacheDirectory | Out-Null
$prismPath = Join-Path $cacheDirectory $prismArchive
$fabricApiPath = Join-Path $cacheDirectory $fabricApiFile
$prismLicensePath = Join-Path $cacheDirectory 'PrismLauncher-LICENSE.txt'
$fabricApiLicensePath = Join-Path $cacheDirectory 'Apache-2.0.txt'

function Get-VerifiedDownload([string]$Uri, [string]$Path, [string]$Algorithm, [string]$ExpectedHash) {
    if (-not (Test-Path -LiteralPath $Path)) {
        Invoke-WebRequest -Uri $Uri -OutFile $Path -UseBasicParsing
    }
    $actualHash = (Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash
    if ($actualHash -ne $ExpectedHash) {
        Remove-Item -LiteralPath $Path -Force
        throw "Integrity check failed for $([IO.Path]::GetFileName($Path)); expected $ExpectedHash but got $actualHash."
    }
}

function Get-Download([string]$Uri, [string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        Invoke-WebRequest -Uri $Uri -OutFile $Path -UseBasicParsing
    }
    if ((Get-Item -LiteralPath $Path).Length -eq 0) {
        Remove-Item -LiteralPath $Path -Force
        throw "Downloaded file is empty: $Path"
    }
}

Get-VerifiedDownload $prismUrl $prismPath 'SHA256' $prismSha256
Get-VerifiedDownload $fabricApiUrl $fabricApiPath 'SHA512' $fabricApiSha512
Get-Download $prismLicenseUrl $prismLicensePath
Get-Download $fabricApiLicenseUrl $fabricApiLicensePath

$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
$staging = Join-Path ([IO.Path]::GetTempPath()) "beamcraft-melty-$([guid]::NewGuid().ToString('N'))"
$bundle = Join-Path $staging 'BeamCraft-Melty'
$prismRoot = Join-Path $bundle 'Prism'
$instanceRoot = Join-Path $prismRoot 'instances\BeamCraft'
$modsRoot = Join-Path $instanceRoot '.minecraft\mods'
$releaseName = "BeamCraft-Melty-v$version.zip"
$releasePath = Join-Path $outputPath $releaseName

try {
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

    function New-ForwardSlashZip([string]$ArchivePath, [string]$Directory) {
        $archive = [IO.Compression.ZipFile]::Open($ArchivePath, [IO.Compression.ZipArchiveMode]::Create)
        try {
            $separator = [IO.Path]::DirectorySeparatorChar
            $basePath = (Resolve-Path -LiteralPath $Directory).Path.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + $separator
            Get-ChildItem -LiteralPath $Directory -Recurse -File | Sort-Object FullName | ForEach-Object {
                $entryName = $_.FullName.Substring($basePath.Length) -replace '[\\/]', '/'
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                    $archive,
                    $_.FullName,
                    $entryName,
                    [IO.Compression.CompressionLevel]::Optimal
                ) | Out-Null
            }
        } finally {
            $archive.Dispose()
        }
    }

    New-Item -ItemType Directory -Force -Path $bundle | Out-Null
    Expand-Archive -LiteralPath $prismPath -DestinationPath $prismRoot
    if (-not (Test-Path -LiteralPath (Join-Path $prismRoot 'prismlauncher.exe'))) {
        throw 'The portable Prism archive did not contain prismlauncher.exe at its root.'
    }
    [IO.File]::WriteAllText((Join-Path $prismRoot 'portable.txt'), '')

    New-Item -ItemType Directory -Force -Path $modsRoot | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot 'beamng\minecraft\instances\BeamCraft\mmc-pack.json') -Destination $instanceRoot
    Copy-Item -LiteralPath (Join-Path $repoRoot 'beamng\minecraft\instances\BeamCraft\instance.cfg') -Destination $instanceRoot
    Copy-Item -LiteralPath $fabricApiPath -Destination $modsRoot
    Copy-Item -LiteralPath $bridgeJarPath -Destination (Join-Path $modsRoot "bng-bridge-$version.jar")

    $beamngMod = Join-Path $bundle 'BeamNG\mods\unpacked\mccrossover'
    Copy-Item -LiteralPath (Join-Path $repoRoot 'beamng\beamng-mod') -Destination $beamngMod -Recurse
    New-Item -ItemType Directory -Force -Path (Join-Path $bundle 'licenses') | Out-Null
    Copy-Item -LiteralPath $prismLicensePath -Destination (Join-Path $bundle 'licenses\PrismLauncher-GPL-3.0.txt')
    Copy-Item -LiteralPath $fabricApiLicensePath -Destination (Join-Path $bundle 'licenses\Apache-2.0.txt')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'beamng\LICENSE') -Destination (Join-Path $bundle 'licenses\BeamCraft-MIT.txt')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'beamng\THIRD_PARTY_NOTICES.md') -Destination (Join-Path $bundle 'licenses\BeamCraft-THIRD-PARTY-NOTICES.md')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'beamng\docs\melty-package\README.txt') -Destination (Join-Path $bundle 'README.txt')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'beamng\scripts\install-melty-package.ps1') -Destination (Join-Path $bundle 'install-beamcraft.ps1')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'beamng\scripts\play-melty-package.ps1') -Destination (Join-Path $bundle 'play-beamcraft.ps1')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'beamng\minecraft\instances\BeamCraft\README.txt') -Destination (Join-Path $bundle 'Prism\instances\BeamCraft\README.txt')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'beamng\scripts\install-beamcraft.bat') -Destination (Join-Path $bundle 'install-beamcraft.bat')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'beamng\scripts\play-beamcraft.bat') -Destination (Join-Path $bundle 'play-beamcraft.bat')

    if (Test-Path -LiteralPath $releasePath) {
        Remove-Item -LiteralPath $releasePath -Force
    }
    New-ForwardSlashZip $releasePath $bundle
    Write-Output "Created $releasePath"
    Write-Output "Prism Launcher $prismVersion; Minecraft 1.21.1; Fabric Loader 0.19.5; Fabric API $fabricApiVersion; BeamCraft $version."
}
finally {
    if (Test-Path -LiteralPath $staging) {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
}
