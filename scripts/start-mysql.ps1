#Requires -Version 5.1
param([string]$MySqlBin)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskRuntimeDir = Join-Path $taskProjectRoot '.runtime'
$taskDataDir = Join-Path $taskProjectRoot 'data\mysql'
$taskConfigPath = Join-Path $taskRuntimeDir 'mysql.json'
$taskRecordPath = Join-Path $taskRuntimeDir 'mysql-process.json'
New-Item -ItemType Directory -Path $taskRuntimeDir -Force | Out-Null
. (Join-Path $PSScriptRoot 'mysql-path.ps1')
$taskLaunchRoot = Get-ProjectMysqlPath $taskProjectRoot
$taskLaunchData = Join-Path $taskLaunchRoot 'data\mysql'

function New-DatabasePassword {
    $taskBytes = New-Object byte[] 32
    $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
    return [BitConverter]::ToString($taskBytes).Replace('-','').ToLowerInvariant()
}

if (Test-Path -LiteralPath $taskConfigPath) {
    $taskConfig = Get-Content -LiteralPath $taskConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($MySqlBin -and -not [string]::Equals([IO.Path]::GetFullPath($MySqlBin), (Split-Path -Parent $taskConfig.executable), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'This database already uses a recorded MySQL installation. An upgrade requires a separate backup and migration.'
    }
} else {
    if (Test-Path -LiteralPath $taskDataDir) {
        throw 'A MySQL data directory exists without its project configuration. Restore .runtime/mysql.json; no data was changed.'
    }
    $taskServerPath = if ($MySqlBin) { Join-Path $MySqlBin 'mysqld.exe' } else { (Get-Command mysqld.exe -ErrorAction Stop).Source }
    $taskVersion = & $taskServerPath --version
    if ($LASTEXITCODE -ne 0 -or $taskVersion -notmatch 'Ver 8\.') { throw 'MySQL 8.x is required for this local setup.' }
    $taskConfig = [pscustomobject]@{
        projectRoot = $taskProjectRoot; executable = $taskServerPath; version = $taskVersion.Trim()
        port = 13307; database = 'ai_knowledge'; username = 'knowledge'
        password = (New-DatabasePassword); rootPassword = (New-DatabasePassword)
    }
    $taskConfig | ConvertTo-Json | Set-Content -LiteralPath $taskConfigPath -Encoding UTF8
}
if (-not [string]::Equals($taskConfig.projectRoot,$taskProjectRoot,[StringComparison]::OrdinalIgnoreCase) -or
    [int]$taskConfig.port -ne 13307 -or $taskConfig.database -ne 'ai_knowledge' -or $taskConfig.username -ne 'knowledge') {
    throw 'Unrecognized project database configuration. No database was started.'
}
$taskServerPath = $taskConfig.executable
$taskBinDir = Split-Path -Parent $taskServerPath
$taskClientPath = Join-Path $taskBinDir 'mysql.exe'
if (-not (Test-Path -LiteralPath $taskServerPath) -or -not (Test-Path -LiteralPath $taskClientPath)) { throw 'The recorded MySQL installation is missing.' }

# Client passwords stay in ignored files rather than command lines or console output.
$taskClientConfig = Join-Path $taskRuntimeDir 'mysql-client.ini'
$taskAdminConfig = Join-Path $taskRuntimeDir 'mysql-admin.ini'
foreach ($taskClientEntry in @(@($taskClientConfig,'knowledge',$taskConfig.password),@($taskAdminConfig,'root',$taskConfig.rootPassword))) {
    $taskClientText = "[client]`nhost=127.0.0.1`nport=13307`nprotocol=TCP`nuser=$($taskClientEntry[1])`npassword=$($taskClientEntry[2])`ndefault-character-set=utf8mb4`n"
    [IO.File]::WriteAllText($taskClientEntry[0],$taskClientText,[Text.UTF8Encoding]::new($false))
}
function Test-ProjectDatabase {
    $taskProbeInfo = [Diagnostics.ProcessStartInfo]::new()
    $taskProbeInfo.FileName = $taskClientPath
    $taskProbeInfo.Arguments = '--defaults-file="'+(Join-Path $taskLaunchRoot '.runtime\mysql-client.ini')+'" --connect-timeout=2 --batch --skip-column-names "--execute=SELECT 1"'
    $taskProbeInfo.EnvironmentVariables['MYSQL_TEST_LOGIN_FILE'] = (Join-Path $taskLaunchRoot '.runtime\mysql-no-login')
    $taskProbeInfo.UseShellExecute = $false
    $taskProbeInfo.CreateNoWindow = $true
    $taskProbeInfo.RedirectStandardOutput = $true
    $taskProbeInfo.RedirectStandardError = $true
    $taskProbe = [Diagnostics.Process]::new()
    $taskProbe.StartInfo = $taskProbeInfo
    try {
        [void]$taskProbe.Start()
        if (-not $taskProbe.WaitForExit(5000)) { $taskProbe.Kill(); return $false }
        return $taskProbe.ExitCode -eq 0 -and $taskProbe.StandardOutput.ReadToEnd().Trim() -eq '1'
    } finally { $taskProbe.Dispose() }
}

if (Test-Path -LiteralPath $taskRecordPath) {
    $taskRecorded = Get-Content -LiteralPath $taskRecordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $taskExisting = Get-CimInstance Win32_Process -Filter ('ProcessId = '+[int]$taskRecorded.pid)
    if ($taskExisting) {
        $taskRecordedStart = [DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParse([string]$taskRecorded.started, [ref]$taskRecordedStart)) {
            throw 'The recorded database start time is invalid. No process was changed.'
        }
        # Windows may reuse an old PID for another program after a new user session.
        # Ignore that stale identity; the port check below still prevents duplicate servers.
        $taskActualStart = [DateTimeOffset]$taskExisting.CreationDate
        if ([Math]::Abs(($taskActualStart - $taskRecordedStart).TotalSeconds) -gt 1) {
            Write-Host 'The previous database PID was reused. Leaving that process untouched and checking the project port.'
            $taskExisting = $null
        }
    }
    if ($taskExisting) {
        if (-not [string]::Equals($taskExisting.ExecutablePath,$taskServerPath,[StringComparison]::OrdinalIgnoreCase) -or
            -not $taskExisting.CommandLine -or $taskExisting.CommandLine.IndexOf($taskLaunchData,[StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw 'The recorded database process no longer matches this project. No process was changed.'
        }
        if (-not (Test-ProjectDatabase)) { throw 'The project database process exists but is not ready. Inspect .runtime/mysql.err.log.' }
        $taskLeftoverBootstrap = Join-Path $taskRuntimeDir 'mysql-bootstrap.sql'
        if (Test-Path -LiteralPath $taskLeftoverBootstrap) { Remove-Item -LiteralPath $taskLeftoverBootstrap -Force }
        Write-Host 'The project MySQL is already ready at 127.0.0.1:13307.'
        return
    }
}
if (@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object { $_.Port -eq 13307 }).Count) {
    throw 'Port 13307 is occupied by another process. No database was changed.'
}
$taskBaseDir = Split-Path -Parent $taskBinDir
# --no-defaults is first: never read another installation's my.ini or data directory.
$taskCommonArguments = @('--no-defaults',('--basedir="'+$taskBaseDir+'"'),('--datadir="'+$taskLaunchData+'"'),
    '--character-set-server=utf8mb4','--collation-server=utf8mb4_0900_ai_ci')
if (-not (Test-Path -LiteralPath (Join-Path $taskDataDir 'mysql.ibd'))) {
    if ((Test-Path -LiteralPath $taskDataDir) -and @(Get-ChildItem -LiteralPath $taskDataDir -Force).Count) {
        throw 'The data directory contains an incomplete initialization. Inspect the init log; it will not be overwritten.'
    }
    New-Item -ItemType Directory -Path $taskDataDir -Force | Out-Null
    $taskInit = Start-Process -FilePath $taskServerPath -ArgumentList ($taskCommonArguments + @('--initialize','--console')) -WorkingDirectory $taskProjectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $taskRuntimeDir 'mysql.init.out.log') -RedirectStandardError (Join-Path $taskRuntimeDir 'mysql.init.err.log')
    if (-not $taskInit.WaitForExit(60000)) { throw 'MySQL initialization is still running. Wait for it to finish before retrying; inspect .runtime/mysql.init.err.log.' }
    if ($taskInit.ExitCode -ne 0) { throw 'MySQL initialization failed. Inspect .runtime/mysql.init.err.log; existing data was not deleted.' }
}
$taskBootstrapPath = Join-Path $taskRuntimeDir 'mysql-bootstrap.sql'
$taskBootstrap = @"
ALTER USER 'root'@'localhost' IDENTIFIED BY '$($taskConfig.rootPassword)';
CREATE DATABASE IF NOT EXISTS ai_knowledge CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'knowledge'@'localhost' IDENTIFIED BY '$($taskConfig.password)';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON ai_knowledge.* TO 'knowledge'@'localhost';
"@
[IO.File]::WriteAllText($taskBootstrapPath,$taskBootstrap,[Text.UTF8Encoding]::new($false))
$taskServerArguments = $taskCommonArguments + @('--console','--bind-address=127.0.0.1','--port=13307','--mysqlx=OFF',
    '--default-time-zone=+00:00','--innodb-buffer-pool-size=128M','--max-connections=30','--local-infile=OFF',
    '--secure-file-priv=NULL',('--pid-file="'+(Join-Path $taskLaunchData 'project.pid')+'"'),
    ('--init-file="'+(Join-Path $taskLaunchRoot '.runtime\mysql-bootstrap.sql')+'"'))
$taskProcess = Start-Process -FilePath $taskServerPath -ArgumentList $taskServerArguments -WorkingDirectory $taskProjectRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $taskRuntimeDir 'mysql.out.log') -RedirectStandardError (Join-Path $taskRuntimeDir 'mysql.err.log')
[pscustomobject]@{pid=$taskProcess.Id;executable=$taskServerPath;dataDirectory=$taskDataDir;launchDirectory=$taskLaunchRoot;port=13307;started=$taskProcess.StartTime.ToString('o')} |
    ConvertTo-Json | Set-Content -LiteralPath $taskRecordPath -Encoding UTF8
$taskReady = $false
$taskDeadline = [DateTime]::UtcNow.AddSeconds(45)
while ([DateTime]::UtcNow -lt $taskDeadline) {
    if ($taskProcess.HasExited) { throw ('Project MySQL exited with code '+$taskProcess.ExitCode+'. Inspect .runtime/mysql.err.log.') }
    if (Test-ProjectDatabase) { $taskReady=$true; break }
    Start-Sleep -Milliseconds 500
}
if (-not $taskReady) { throw 'Project MySQL readiness timed out. Inspect .runtime/mysql.err.log.' }
Remove-Item -LiteralPath $taskBootstrapPath -Force
Write-Host 'Project MySQL is ready at 127.0.0.1:13307; data stays in data/mysql. Credentials are in ignored .runtime/mysql.json.'
