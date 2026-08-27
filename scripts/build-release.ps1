[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendDirectory = Join-Path $repositoryRoot 'backend'
$frontendDirectory = Join-Path $repositoryRoot 'frontend'
$distDirectory = Join-Path $repositoryRoot 'dist'
$releaseDirectory = Join-Path $distDirectory 'sportshop'
$stagingDirectory = Join-Path $distDirectory '.sportshop-staging'
$releaseDataDirectory = Join-Path $releaseDirectory 'data'

if ((Test-Path -LiteralPath $releaseDataDirectory) -and
    (Get-ChildItem -LiteralPath $releaseDataDirectory -Force | Select-Object -First 1)) {
    throw "Refusing to replace a release directory with non-empty data: $releaseDataDirectory"
}

function Invoke-CheckedStep {
    param(
        [Parameter(Mandatory)] [string]$Name,
        [Parameter(Mandatory)] [string]$WorkingDirectory,
        [Parameter(Mandatory)] [scriptblock]$Command
    )

    Write-Host "`n==> $Name"
    Push-Location $WorkingDirectory
    try {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & $Command
        $commandExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Pop-Location
    }
    if ($commandExitCode -ne 0) {
        throw "$Name failed with exit code $commandExitCode"
    }
}

$javaVersion = (& cmd.exe /d /c 'java -version 2>&1' | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch 'version "21\.') {
    throw 'Java 21 is required and java must be available on PATH.'
}

$mavenVersion = (& mvn -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $mavenVersion -notmatch 'Java version: 21(?:\.|,)') {
    throw 'Maven must run with Java 21. Check JAVA_HOME.'
}

$nodeVersion = (& node --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v24\.') {
    throw "Node.js 24 is required. Current version: $nodeVersion"
}

Invoke-CheckedStep 'Install frontend dependencies' $frontendDirectory { npm ci }
Invoke-CheckedStep 'Run backend tests' $backendDirectory { mvn test }
Invoke-CheckedStep 'Run frontend unit tests' $frontendDirectory { npm test -- --run }
Invoke-CheckedStep 'Check frontend types' $frontendDirectory { npm run type-check }
Invoke-CheckedStep 'Run end-to-end tests' $frontendDirectory { npm run test:e2e }
Invoke-CheckedStep 'Build clean single-JAR application' $backendDirectory { mvn clean package -DskipTests }

$applicationJar = Join-Path $backendDirectory 'target\sportshop-backend-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $applicationJar -PathType Leaf)) {
    throw "Application JAR was not generated: $applicationJar"
}

if (Test-Path -LiteralPath $stagingDirectory) {
    Remove-Item -LiteralPath $stagingDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path (Join-Path $stagingDirectory 'data') -Force | Out-Null
Copy-Item -LiteralPath $applicationJar -Destination (Join-Path $stagingDirectory 'sportshop.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start.ps1') -Destination (Join-Path $stagingDirectory 'start.ps1')
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'docs\operations\windows-deployment.md') -Destination (Join-Path $stagingDirectory 'README.md')

if (-not (Test-Path -LiteralPath $releaseDirectory)) {
    Move-Item -LiteralPath $stagingDirectory -Destination $releaseDirectory
}
else {
    New-Item -ItemType Directory -Path $releaseDataDirectory -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $stagingDirectory 'sportshop.jar') -Destination (Join-Path $releaseDirectory 'sportshop.jar') -Force
    Copy-Item -LiteralPath (Join-Path $stagingDirectory 'start.ps1') -Destination (Join-Path $releaseDirectory 'start.ps1') -Force
    Copy-Item -LiteralPath (Join-Path $stagingDirectory 'README.md') -Destination (Join-Path $releaseDirectory 'README.md') -Force
    Remove-Item -LiteralPath $stagingDirectory -Recurse -Force
}

Write-Host "`nRelease package created: $releaseDirectory"
