#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskRecordPath = Join-Path $taskProjectRoot '.runtime\mysql-process.json'
if (-not (Test-Path -LiteralPath $taskRecordPath)) { Write-Host 'No project MySQL process is recorded.'; return }
$taskRecord = Get-Content -LiteralPath $taskRecordPath -Raw -Encoding UTF8 | ConvertFrom-Json
$taskExpectedData = [IO.Path]::GetFullPath((Join-Path $taskProjectRoot 'data\mysql'))
. (Join-Path $PSScriptRoot 'mysql-path.ps1')
$taskLaunchRoot = Get-ProjectMysqlPath $taskProjectRoot
if (-not [string]::Equals($taskRecord.dataDirectory,$taskExpectedData,[StringComparison]::OrdinalIgnoreCase) -or [int]$taskRecord.port -ne 13307) {
    throw 'Unrecognized database record. No database was stopped.'
}
$taskProcess = Get-CimInstance Win32_Process -Filter ('ProcessId = '+[int]$taskRecord.pid)
if (-not $taskProcess) { Write-Host 'The project MySQL is already stopped.'; return }
if (-not [string]::Equals($taskProcess.ExecutablePath,$taskRecord.executable,[StringComparison]::OrdinalIgnoreCase) -or
    -not $taskProcess.CommandLine -or $taskProcess.CommandLine.IndexOf((Join-Path $taskLaunchRoot 'data\mysql'),[StringComparison]::OrdinalIgnoreCase) -lt 0) {
    throw 'Database process identity no longer matches. No database was stopped.'
}
$taskAdmin = Join-Path (Split-Path -Parent $taskRecord.executable) 'mysqladmin.exe'
$taskAdminConfig = Join-Path $taskLaunchRoot '.runtime\mysql-admin.ini'
Invoke-ProjectMysql -Executable $taskAdmin -ClientConfiguration $taskAdminConfig -SqlArguments @('--connect-timeout=3','shutdown')
$taskNativeProcess = Get-Process -Id ([int]$taskRecord.pid) -ErrorAction SilentlyContinue
if ($taskNativeProcess -and -not $taskNativeProcess.WaitForExit(20000)) { throw 'MySQL is still shutting down. Inspect .runtime/mysql.err.log.' }
Write-Host 'Stopped this project MySQL gracefully.'
