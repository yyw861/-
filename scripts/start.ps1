[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,

    [System.Net.IPAddress]$BindAddress = '127.0.0.1'
)

$ErrorActionPreference = 'Stop'
$releaseDirectory = $PSScriptRoot
$applicationJar = Join-Path $releaseDirectory 'sportshop.jar'
$dataDirectory = Join-Path $releaseDirectory 'data'

if (-not (Test-Path -LiteralPath $applicationJar -PathType Leaf)) {
    throw "Application JAR not found: $applicationJar"
}

$javaVersion = (& cmd.exe /d /c 'java -version 2>&1' | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "21\.') {
    throw 'Java 21 is required and java must be available on PATH.'
}

New-Item -ItemType Directory -Path $dataDirectory -Force | Out-Null
$env:SPORTSHOP_DATA_DIR = $dataDirectory

Write-Host "Sport shop is starting on ${BindAddress}:$Port"
Write-Host "Data directory: $dataDirectory"

Push-Location $releaseDirectory
try {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & java -jar $applicationJar "--server.address=$BindAddress" "--server.port=$Port"
    $applicationExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
    Pop-Location
}
exit $applicationExitCode
