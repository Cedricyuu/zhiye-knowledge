#Requires -Version 5.1
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $taskProjectRoot
try {
    if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) { throw 'Maven is not on PATH.' }
    if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { throw 'Node.js/npm is not on PATH.' }
    Push-Location -LiteralPath (Join-Path $taskProjectRoot 'frontend')
    try {
        & npm.cmd ci --cache (Join-Path $taskProjectRoot '.cache\npm') --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend regression tests failed.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    $taskMavenArguments = @('-B', '-ntp', 'package')
    if ($SkipTests) { $taskMavenArguments += '-DskipTests' }
    & mvn.cmd @taskMavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
    Write-Host 'Build complete: backend\target\knowledge-api-0.1.0.jar'
} finally { Pop-Location }
