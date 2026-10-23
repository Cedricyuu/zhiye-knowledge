#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskSelectionPath = Join-Path $taskProjectRoot '.runtime\database.json'
if (Test-Path -LiteralPath $taskSelectionPath) {
    $taskSelection = Get-Content -LiteralPath $taskSelectionPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($taskSelection.type -eq 'mysql') { Write-Host 'Project MySQL is already selected; no import was repeated.'; return }
}
& (Join-Path $PSScriptRoot 'start-mysql.ps1')
& (Join-Path $PSScriptRoot 'stop.ps1') -KeepModel -KeepDatabase
$taskSourceFile = Join-Path $taskProjectRoot 'data\knowledge.mv.db'
if (Test-Path -LiteralPath $taskSourceFile) {
    $taskBackupDir = Join-Path $taskProjectRoot ('.runtime\backups\before-mysql-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6))
    New-Item -ItemType Directory -Path $taskBackupDir | Out-Null
    Copy-Item -LiteralPath $taskSourceFile -Destination (Join-Path $taskBackupDir 'knowledge.mv.db')
    Write-Host ('H2 backup saved to '+$taskBackupDir)
}
$taskH2Driver = Join-Path $taskProjectRoot '.cache\m2\com\h2database\h2\2.3.232\h2-2.3.232.jar'
$taskMysqlDriver = Join-Path $taskProjectRoot '.cache\m2\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar'
if (-not (Test-Path -LiteralPath $taskH2Driver) -or -not (Test-Path -LiteralPath $taskMysqlDriver)) { throw 'Build the project before importing the database.' }
$taskConfig = Get-Content -LiteralPath (Join-Path $taskProjectRoot '.runtime\mysql.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$taskPreviousPassword = [Environment]::GetEnvironmentVariable('KB_MIGRATION_DB_PASSWORD','Process')
try {
    $env:KB_MIGRATION_DB_PASSWORD = $taskConfig.password
    & java '-Dfile.encoding=UTF-8' -cp ($taskH2Driver+';'+$taskMysqlDriver) (Join-Path $PSScriptRoot 'MigrateH2ToMysql.java') $taskProjectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Database import failed. H2 and its backup are preserved; MySQL was not selected.' }
} finally { [Environment]::SetEnvironmentVariable('KB_MIGRATION_DB_PASSWORD',$taskPreviousPassword,'Process') }
[pscustomobject]@{type='mysql';selectedAt=[DateTimeOffset]::Now.ToString('o')} | ConvertTo-Json |
    Set-Content -LiteralPath $taskSelectionPath -Encoding UTF8
Write-Host 'MySQL selected. Run scripts/start.ps1; accounts, documents, vectors and history have been preserved.'
