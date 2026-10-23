# MySQL 8.0's Windows option parser cannot reliably reopen a non-ASCII datadir.
# A project-specific temporary junction supplies ASCII paths; the data stays in this workspace.
function Get-ProjectMysqlPath {
    param([string]$ProjectRoot)
    if ($ProjectRoot -notmatch '[^\x00-\x7F]') { return $ProjectRoot }
    $taskHash = [Security.Cryptography.SHA256]::Create()
    try { $taskSuffix = [BitConverter]::ToString($taskHash.ComputeHash([Text.Encoding]::UTF8.GetBytes($ProjectRoot.ToUpperInvariant()))).Replace('-','').Substring(0,16).ToLowerInvariant() }
    finally { $taskHash.Dispose() }
    $taskAlias = Join-Path ([IO.Path]::GetTempPath()) ('zhiye-mysql-'+$taskSuffix)
    if ($taskAlias -match '[^\x00-\x7F]') { throw 'MySQL needs an ASCII temporary path on this Windows installation.' }
    if (Test-Path -LiteralPath $taskAlias) {
        $taskLink = Get-Item -LiteralPath $taskAlias -Force
        if ($taskLink.LinkType -ne 'Junction' -or @($taskLink.Target).Count -ne 1 -or
            -not [string]::Equals([IO.Path]::GetFullPath(@($taskLink.Target)[0]),$ProjectRoot,[StringComparison]::OrdinalIgnoreCase)) {
            throw 'The project MySQL path alias is occupied by another directory. No files were changed.'
        }
    } else {
        New-Item -ItemType Junction -Path $taskAlias -Target $ProjectRoot | Out-Null
    }
    return $taskAlias
}

function Invoke-ProjectMysql {
    param([string]$Executable, [string]$ClientConfiguration, [string[]]$SqlArguments)
    # MySQL 8.0 reads .mylogin.cnf even with --defaults-file. Suppress that unrelated credential source.
    $taskPreviousLoginFile = [Environment]::GetEnvironmentVariable('MYSQL_TEST_LOGIN_FILE','Process')
    try {
        $env:MYSQL_TEST_LOGIN_FILE = $ClientConfiguration+'.no-login'
        & $Executable ('--defaults-file='+$ClientConfiguration) @SqlArguments
        if ($LASTEXITCODE -ne 0) { throw ('Project MySQL client failed with exit code '+$LASTEXITCODE+'.') }
    } finally { [Environment]::SetEnvironmentVariable('MYSQL_TEST_LOGIN_FILE',$taskPreviousLoginFile,'Process') }
}
